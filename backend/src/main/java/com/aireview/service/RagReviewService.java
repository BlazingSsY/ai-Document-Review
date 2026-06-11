package com.aireview.service;

import com.aireview.entity.AiModelConfig;
import com.aireview.entity.DocumentBlock;
import com.aireview.entity.ReviewTask;
import com.aireview.entity.Rule;
import com.aireview.entity.RuleCheck;
import com.aireview.repository.DocumentVectorRepository;
import com.aireview.repository.ReviewTaskMapper;
import com.aireview.repository.RuleCheckMapper;
import com.aireview.review.ReviewResultSchema;
import com.aireview.review.llm.JsonExtractor;
import com.aireview.util.ChunkUtils;
import com.aireview.util.WordParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagReviewService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final RuleService ruleService;
    private final RuleCheckMapper ruleCheckMapper;
    private final DocumentVectorRepository documentVectorRepository;
    private final AiModelService aiModelService;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    @Qualifier("ragCheckExecutor")
    private Executor ragCheckExecutor;

    @Value("${review.rag.block-max-chars:1800}")
    private int blockMaxChars;

    @Value("${review.rag.embedding-batch-size:24}")
    private int embeddingBatchSize;

    @Value("${review.rag.recall-top-k:20}")
    private int recallTopK;

    @Value("${review.rag.rerank-top-n:6}")
    private int rerankTopN;

    @Value("${review.rag.vector-index.enabled:true}")
    private boolean vectorIndexEnabled;

    @Value("${review.rag.vector-index.hnsw-ef-search:100}")
    private int hnswEfSearch;

    @Value("${review.rag.vector-index.binary-candidate-multiplier:4}")
    private int binaryCandidateMultiplier;

    @Value("${review.rag.failed-check-retry-attempts:1}")
    private int failedCheckRetryAttempts;

    @Value("${review.rag.check-concurrency:4}")
    private int checkConcurrency;

    /**
     * Parses and vectorizes the uploaded document before any checklist item is sent
     * to the chat model. This makes document preparation an explicit pipeline stage.
     */
    public PreparedDocument prepareDocumentVectors(ReviewTask task) throws Exception {
        String taskId = task.getId();
        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "RAG: 正在解析上传文档...", 8);

        List<WordParser.Chapter> rawChapters = WordParser.parseChapters(task.getFilePath());
        if (rawChapters.isEmpty() || rawChapters.stream().allMatch(ch -> ch.getContent().isBlank())) {
            throw new RuntimeException("Document content is empty or cannot be parsed");
        }
        int firstRealIdx = ChunkUtils.findFirstRealChapterIndex(rawChapters);
        List<WordParser.Chapter> chapters = firstRealIdx > 0
                ? new ArrayList<>(rawChapters.subList(firstRealIdx, rawChapters.size()))
                : rawChapters;

        AiModelConfig embeddingModel = aiModelService.getFirstEnabledModelByType(AiModelService.MODEL_TYPE_EMBEDDING);
        if (embeddingModel == null) {
            throw new IllegalStateException("RAG review requires an enabled embedding model");
        }

        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "RAG: 正在构建原文分块...", 15);
        List<DocumentBlock> blocks = buildBlocks(taskId, chapters);
        if (blocks.isEmpty()) {
            throw new IllegalStateException("No source blocks were produced from the uploaded document");
        }
        documentVectorRepository.deleteByTaskId(taskId);

        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "RAG: 正在向量化 " + blocks.size() + " 个原文分块...", 25);
        embedBlocks(blocks, embeddingModel);
        documentVectorRepository.saveAll(blocks);
        int embeddingDimension = blocks.get(0).getEmbeddingDimension();
        String vectorIndexStrategy = vectorIndexEnabled
                ? documentVectorRepository.ensureHnswIndex(embeddingModel.getModelName(), embeddingDimension)
                : "exact";
        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "RAG: 文档向量化完成，pgvector " + vectorIndexStrategy
                        + " 索引已就绪（维度 " + embeddingDimension + "）", 34);

        return new PreparedDocument(List.copyOf(blocks), embeddingModel,
                embeddingDimension, vectorIndexStrategy);
    }

    public void executeReview(ReviewTask task, String runStamp) throws Exception {
        executeReview(task, runStamp, prepareDocumentVectors(task));
    }

    public void executeReview(ReviewTask task, String runStamp,
                              PreparedDocument preparedDocument) throws Exception {
        String taskId = task.getId();
        AiModelConfig chatModel = aiModelService.getEnabledModel(task.getSelectedModel());
        AiModelConfig embeddingModel = preparedDocument.embeddingModel();
        AiModelConfig rerankerModel =
                aiModelService.getFirstEnabledModelByType(AiModelService.MODEL_TYPE_RERANKER);
        List<DocumentBlock> blocks = preparedDocument.blocks();

        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "RAG: 正在加载检查规则...", 38);
        List<CheckPlan> plans = buildCheckPlans(task.getScenarioId());
        if (plans.isEmpty()) {
            throw new RuntimeException("No active rule checks found for scenario: " + task.getScenarioId());
        }

        List<IndexedPlan> indexedPlans = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            indexedPlans.add(new IndexedPlan(i, plans.get(i)));
        }

        List<CheckAttempt> finalAttempts = new ArrayList<>(runCheckPass(
                taskId, indexedPlans, plans.size(), chatModel, embeddingModel, rerankerModel,
                0, "首轮审查", 40, 82));
        int initialFailedCount = (int) finalAttempts.stream().filter(CheckAttempt::failed).count();
        int retriedCheckCount = 0;

        for (int retry = 1; retry <= Math.max(0, failedCheckRetryAttempts); retry++) {
            List<IndexedPlan> failedPlans = finalAttempts.stream()
                    .filter(CheckAttempt::failed)
                    .map(attempt -> new IndexedPlan(attempt.index(), plans.get(attempt.index())))
                    .toList();
            if (failedPlans.isEmpty()) break;

            retriedCheckCount += failedPlans.size();
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "首轮审查完成，开始补审 " + failedPlans.size() + " 个失败项"
                            + "（第 " + retry + " 次）", 84);
            List<CheckAttempt> retryAttempts = runCheckPass(
                    taskId, failedPlans, plans.size(), chatModel, embeddingModel, rerankerModel,
                    retry, "失败项补审", 84, 92);
            for (CheckAttempt attempt : retryAttempts) {
                finalAttempts.set(attempt.index(), attempt);
            }
        }

        List<Map<String, Object>> allCheckResults = new ArrayList<>();
        List<Map<String, Object>> chunkResults = new ArrayList<>();
        Map<String, Integer> statusCounts = new TreeMap<>();
        int remainingFailedCount = 0;
        int rerankedCount = 0;

        for (int i = 0; i < plans.size(); i++) {
            CheckPlan plan = plans.get(i);
            CheckAttempt attempt = finalAttempts.get(i);
            if (attempt.reranked()) rerankedCount++;
            Map<String, Object> check;
            if (attempt.failed()) {
                remainingFailedCount++;
                check = buildFailedCheck(plan, attempt);
            } else {
                check = attempt.result();
            }
            allCheckResults.add(check);
            statusCounts.merge(String.valueOf(check.get("status")), 1, Integer::sum);

            Map<String, Object> chunkResult = new LinkedHashMap<>();
            chunkResult.put("chunk", i + 1);
            chunkResult.put("chapterTitle", plan.checkQuestion());
            chunkResult.put("totalChunks", plans.size());
            chunkResult.put("sourceRefs", check.get("sourceRefs"));
            chunkResult.put("appliedRules", List.of(plan.ruleName()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("summary", String.valueOf(check.getOrDefault("reason", "")));
            result.put("issues", new ArrayList<>());
            result.put("passed_items", new ArrayList<>());
            result.put("check_results", List.of(check));
            chunkResult.put("result", result);
            chunkResults.add(chunkResult);
        }

        Map<String, Object> aiResult = new LinkedHashMap<>();
        aiResult.put("reviewMode", "rag");
        aiResult.put("runStamp", runStamp);
        aiResult.put("modelName", chatModel.getModelName());
        aiResult.put("embeddingModel", embeddingModel.getModelName());
        aiResult.put("rerankerModel", rerankerModel != null ? rerankerModel.getModelName() : null);
        aiResult.put("totalChunks", blocks.size());
        aiResult.put("chunkResults", chunkResults);
        aiResult.put("totalIssues", 0);
        aiResult.put("allIssues", new ArrayList<>());
        aiResult.put("totalCheckResults", allCheckResults.size());
        aiResult.put("allCheckResults", allCheckResults);
        aiResult.put("checkStatusCounts", statusCounts);
        aiResult.put("originalSources", blocks.stream().map(this::toOriginalSource).toList());
        aiResult.put("sourceTextMode", "original_word_document_blocks");
        Map<String, Object> retrievalStats = new LinkedHashMap<>();
        retrievalStats.put("engine", "pgvector");
        retrievalStats.put("indexStrategy", preparedDocument.vectorIndexStrategy());
        retrievalStats.put("embeddingDimension", preparedDocument.embeddingDimension());
        retrievalStats.put("blockCount", blocks.size());
        retrievalStats.put("checkCount", plans.size());
        retrievalStats.put("checkConcurrency", checkConcurrency);
        retrievalStats.put("recallTopK", recallTopK);
        retrievalStats.put("rerankTopN", rerankTopN);
        retrievalStats.put("hnswEfSearch", hnswEfSearch);
        retrievalStats.put("rerankedChecks", rerankedCount);
        retrievalStats.put("initialFailedChecks", initialFailedCount);
        retrievalStats.put("retriedChecks", retriedCheckCount);
        retrievalStats.put("recoveredChecks", initialFailedCount - remainingFailedCount);
        retrievalStats.put("remainingFailedChecks", remainingFailedCount);
        aiResult.put("retrievalStats", retrievalStats);

        task.setAiResult(aiResult);
        updateTaskStatus(task, ReviewTask.STATUS_COMPLETED, null);
        webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_COMPLETED,
                remainingFailedCount == 0
                        ? "RAG 审查完成：" + plans.size() + " 个检查项"
                        : "RAG 审查完成：" + plans.size() + " 个检查项，其中 "
                                + remainingFailedCount + " 个补审后仍需人工复核");
    }

    private List<CheckAttempt> runCheckPass(
            String taskId,
            List<IndexedPlan> indexedPlans,
            int totalPlanCount,
            AiModelConfig chatModel,
            AiModelConfig embeddingModel,
            AiModelConfig rerankerModel,
            int retryNumber,
            String phaseName,
            int progressStart,
            int progressEnd) {
        if (indexedPlans.isEmpty()) return List.of();

        AtomicInteger completed = new AtomicInteger();
        List<CompletableFuture<CheckAttempt>> futures = indexedPlans.stream()
                .map(indexed -> CompletableFuture.supplyAsync(
                                () -> executeCheck(taskId, indexed, totalPlanCount, chatModel,
                                        embeddingModel, rerankerModel, retryNumber),
                                ragCheckExecutor)
                        .whenComplete((ignored, error) -> {
                            int done = completed.incrementAndGet();
                            int progress = progressStart + (int) Math.round(
                                    (double) done / indexedPlans.size() * (progressEnd - progressStart));
                            try {
                                webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                                        "RAG " + phaseName + "：" + done + "/" + indexedPlans.size(),
                                        progress);
                            } catch (Exception progressError) {
                                log.debug("Failed to publish RAG check progress for task {}: {}",
                                        taskId, progressError.getMessage());
                            }
                        }))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private CheckAttempt executeCheck(
            String taskId,
            IndexedPlan indexed,
            int totalPlanCount,
            AiModelConfig chatModel,
            AiModelConfig embeddingModel,
            AiModelConfig rerankerModel,
            int retryNumber) {
        List<ScoredBlock> evidence = List.of();
        boolean reranked = false;
        try {
            String query = buildRetrievalQuery(indexed.plan());
            List<ScoredBlock> recalled = recall(taskId, query, embeddingModel, recallTopK);
            evidence = rerank(query, recalled, rerankerModel, rerankTopN);
            reranked = rerankerModel != null && !evidence.isEmpty();
            int seedSequence = indexed.index() + 1 + retryNumber * totalPlanCount;
            Map<String, Object> result = reviewCheckWithEvidence(
                    chatModel, indexed.plan(), evidence, taskId, seedSequence);
            return CheckAttempt.success(indexed.index(), result, reranked);
        } catch (Exception e) {
            log.warn("RAG check failed: task={}, check={}, retry={}, error={}",
                    taskId, indexed.plan().checkCode(), retryNumber, rootErrorMessage(e));
            return CheckAttempt.failure(indexed.index(), evidence, reranked, e);
        }
    }

    private Map<String, Object> buildFailedCheck(CheckPlan plan, CheckAttempt attempt) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("check_code", plan.checkCode());
        check.put("rule_code", plan.ruleCode());
        check.put("check_question", plan.checkQuestion());
        check.put("status", "Review");
        check.put("reason", "单项审查失败，自动补审后仍未成功：" + rootErrorMessage(attempt.error()));
        check.put("evidence", "");
        check.put("missing_items", List.of("模型审查结果"));
        check.put("suggestion", "请检查模型服务状态后对该项执行人工复核或重新审查。");
        check.put("confidence", "needs_review");
        check.put("location", attempt.evidence().isEmpty()
                ? "" : attempt.evidence().get(0).block().getSectionPath());
        check.put("sourceTitle", attempt.evidence().isEmpty()
                ? "" : attempt.evidence().get(0).block().getSectionPath());
        check.put("sourceRefs", attempt.evidence().stream().map(this::toSourceRef).toList());
        check.put("retrievalScores",
                attempt.evidence().stream().map(this::toRetrievalScore).toList());
        check.put("reviewError", rootErrorMessage(attempt.error()));
        check.put("retryExhausted", true);
        return check;
    }

    private String rootErrorMessage(Throwable error) {
        if (error == null) return "unknown error";
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) message = current.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private List<DocumentBlock> buildBlocks(String taskId, List<WordParser.Chapter> chapters) {
        List<DocumentBlock> blocks = new ArrayList<>();
        int seq = 1;
        for (int chapterIdx = 0; chapterIdx < chapters.size(); chapterIdx++) {
            WordParser.Chapter chapter = chapters.get(chapterIdx);
            List<String> pieces = splitChapter(chapter.getContent());
            int blockIdx = 1;
            for (String piece : pieces) {
                String text = (chapter.getTitle() == null || chapter.getTitle().isBlank())
                        ? piece
                        : chapter.getTitle() + "\n\n" + piece;
                if (text.isBlank()) continue;
                DocumentBlock block = new DocumentBlock();
                block.setTaskId(taskId);
                block.setBlockId("BLOCK-" + String.format("%05d", seq++));
                block.setBlockType("paragraph");
                block.setChapterIndex(chapterIdx + 1);
                block.setBlockIndex(blockIdx++);
                block.setSectionPath(chapter.getTitle());
                block.setTextContent(text);
                block.setTextHash(sha1(text));
                blocks.add(block);
            }
        }
        return blocks;
    }

    private List<String> splitChapter(String content) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n");
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : normalized.split("\\n\\s*\\n|\\n(?=<table)|\\n(?=\\|)")) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            if (current.length() > 0 && current.length() + part.length() + 2 > blockMaxChars) {
                pieces.add(current.toString().trim());
                current.setLength(0);
            }
            if (part.length() > blockMaxChars) {
                for (int start = 0; start < part.length(); start += blockMaxChars) {
                    int end = Math.min(part.length(), start + blockMaxChars);
                    pieces.add(part.substring(start, end).trim());
                }
            } else {
                if (current.length() > 0) current.append("\n\n");
                current.append(part);
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString().trim());
        }
        return pieces;
    }

    private void embedBlocks(List<DocumentBlock> blocks, AiModelConfig embeddingModel) throws Exception {
        int batchSize = Math.max(1, embeddingBatchSize);
        Integer detectedDimension = null;
        for (int start = 0; start < blocks.size(); start += batchSize) {
            int end = Math.min(blocks.size(), start + batchSize);
            List<DocumentBlock> batch = blocks.subList(start, end);
            List<String> texts = batch.stream().map(DocumentBlock::getTextContent).toList();
            List<List<Double>> vectors = aiModelService.embedTexts(embeddingModel, texts);
            for (int i = 0; i < batch.size(); i++) {
                List<Double> vector = vectors.get(i);
                if (vector.isEmpty()) {
                    throw new IllegalStateException("Embedding API returned an empty vector");
                }
                Integer configuredDimension = embeddingModel.getEmbeddingDimension();
                if (configuredDimension != null && configuredDimension > 0
                        && configuredDimension != vector.size()) {
                    throw new IllegalStateException("Embedding dimension mismatch: configured "
                            + configuredDimension + ", actual " + vector.size());
                }
                if (detectedDimension == null) {
                    detectedDimension = vector.size();
                } else if (detectedDimension != vector.size()) {
                    throw new IllegalStateException("Embedding API returned inconsistent dimensions: "
                            + detectedDimension + " and " + vector.size());
                }
                DocumentBlock block = batch.get(i);
                block.setEmbeddingModel(embeddingModel.getModelName());
                block.setEmbeddingVector(objectMapper.writeValueAsString(vector));
                block.setEmbeddingDimension(vector.size());
            }
        }
    }

    private List<CheckPlan> buildCheckPlans(Long scenarioId) {
        List<Rule> rules = ruleService.getRulesByScenarioId(scenarioId);
        List<CheckPlan> plans = new ArrayList<>();
        int auto = 1;
        for (Rule rule : rules) {
            List<RuleCheck> checks = ruleCheckMapper.findActiveByRuleId(rule.getId());
            String ruleCode = firstNonBlank(rule.getRuleCode(), "R-AUTO-" + String.format("%03d", auto++));
            if (checks.isEmpty()) {
                plans.add(new CheckPlan(rule, null, ruleCode, ruleCode + "-C001",
                        firstNonBlank(rule.getDescription(), rule.getRuleName()),
                        rule.getContent(), "other"));
                continue;
            }
            for (RuleCheck check : checks) {
                plans.add(new CheckPlan(rule, check, ruleCode,
                        firstNonBlank(check.getCheckCode(), ruleCode + "-C" + String.format("%03d", plans.size() + 1)),
                        check.getQuestion(),
                        check.getPassCriteria(),
                        firstNonBlank(check.getCategory(), "other")));
            }
        }
        return plans;
    }

    private String buildRetrievalQuery(CheckPlan plan) {
        return String.join("\n",
                plan.ruleName(),
                plan.ruleCode(),
                plan.checkCode(),
                plan.checkQuestion(),
                plan.passCriteria(),
                plan.ruleContent());
    }

    private List<ScoredBlock> recall(String taskId, String query, AiModelConfig embeddingModel, int topK) throws Exception {
        List<Double> queryVector = aiModelService.embedText(embeddingModel, query);
        return documentVectorRepository.findNearest(
                        taskId,
                        embeddingModel.getModelName(),
                        queryVector,
                        Math.max(1, topK),
                        hnswEfSearch,
                        binaryCandidateMultiplier)
                .stream()
                .map(item -> new ScoredBlock(item.block(), item.score(), "pgvector"))
                .toList();
    }

    private List<ScoredBlock> rerank(String query, List<ScoredBlock> recalled,
                                      AiModelConfig rerankerModel, int topN) {
        if (recalled.isEmpty()) return List.of();
        int limit = Math.max(1, Math.min(topN, recalled.size()));
        if (rerankerModel == null) {
            return recalled.stream().limit(limit).toList();
        }
        try {
            List<String> documents = recalled.stream().map(item -> item.block().getTextContent()).toList();
            List<AiModelService.RerankResult> ranked = aiModelService.rerank(rerankerModel, query, documents, limit);
            List<ScoredBlock> out = new ArrayList<>();
            for (AiModelService.RerankResult result : ranked) {
                ScoredBlock base = recalled.get(result.index());
                out.add(new ScoredBlock(base.block(), result.score(), "reranker"));
            }
            return out.isEmpty() ? recalled.stream().limit(limit).toList() : out;
        } catch (Exception e) {
            log.warn("Reranker failed, falling back to vector order: {}", e.getMessage());
            return recalled.stream().limit(limit).toList();
        }
    }

    private Map<String, Object> reviewCheckWithEvidence(AiModelConfig chatModel, CheckPlan plan,
                                                        List<ScoredBlock> evidence, String taskId,
                                                        int sequence) throws Exception {
        String systemPrompt = """
                You are an airborne document review assistant.
                Judge the check item strictly from the supplied evidence blocks.
                If evidence is insufficient, return Review rather than guessing.
                Output JSON following the provided schema.
                """;
        String userPrompt = buildReviewPrompt(plan, evidence);
        AiCallOptions options = AiCallOptions.builder()
                .temperature(0.0)
                .topP(1.0)
                .maxTokensOverride(4096)
                .seed(stableSeed(taskId, sequence))
                .structuredSchema(com.alibaba.fastjson2.JSON.parseObject(
                        com.alibaba.fastjson2.JSON.toJSONString(ReviewResultSchema.schema())))
                .structuredSchemaName(ReviewResultSchema.SCHEMA_NAME)
                .build();

        String response = aiModelService.callAiModel(chatModel, systemPrompt, userPrompt, options);
        Map<String, Object> parsed = parseJson(response);
        Map<String, Object> check = extractFirstCheck(parsed);
        check.putIfAbsent("check_code", plan.checkCode());
        check.putIfAbsent("rule_code", plan.ruleCode());
        check.putIfAbsent("check_question", plan.checkQuestion());
        check.put("status", normalizeCheckStatus(check.get("status")));
        check.putIfAbsent("reason", "");
        check.putIfAbsent("evidence", "");
        check.putIfAbsent("missing_items", new ArrayList<>());
        check.putIfAbsent("suggestion", "");
        check.putIfAbsent("confidence", evidence.isEmpty() ? "needs_review" : "medium");
        check.putIfAbsent("location", evidence.isEmpty() ? "" : evidence.get(0).block().getSectionPath());
        check.put("sourceTitle", evidence.isEmpty() ? "" : evidence.get(0).block().getSectionPath());
        check.put("sourceRefs", evidence.stream().map(this::toSourceRef).toList());
        check.put("retrievalScores", evidence.stream().map(this::toRetrievalScore).toList());
        return check;
    }

    private String buildReviewPrompt(CheckPlan plan, List<ScoredBlock> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rule code: ").append(plan.ruleCode()).append('\n');
        sb.append("Rule name: ").append(plan.ruleName()).append('\n');
        sb.append("Check code: ").append(plan.checkCode()).append('\n');
        sb.append("Check question: ").append(plan.checkQuestion()).append('\n');
        sb.append("Pass criteria: ").append(plan.passCriteria()).append("\n\n");
        sb.append("Evidence blocks:\n");
        if (evidence.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (ScoredBlock item : evidence) {
                DocumentBlock block = item.block();
                sb.append("[").append(block.getBlockId()).append("] ")
                        .append(block.getSectionPath()).append(" score=")
                        .append(String.format(Locale.ROOT, "%.4f", item.score()))
                        .append(" via ").append(item.reason()).append('\n')
                        .append(block.getTextContent()).append("\n\n");
            }
        }
        sb.append("Return one check_results item for the check code above. ");
        sb.append("The evidence field must quote or summarize only the supplied evidence blocks.");
        return sb.toString();
    }

    private Map<String, Object> parseJson(String response) {
        JsonNode node = JsonExtractor.extract(response, objectMapper);
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFirstCheck(Map<String, Object> parsed) {
        Object checksObj = parsed.get("check_results");
        if (checksObj instanceof List<?> checks && !checks.isEmpty() && checks.get(0) instanceof Map<?, ?> first) {
            return new LinkedHashMap<>((Map<String, Object>) first);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> toSourceRef(ScoredBlock item) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("sourceId", item.block().getBlockId());
        ref.put("title", item.block().getSectionPath());
        ref.put("sectionPath", item.block().getSectionPath());
        ref.put("reason", item.reason());
        ref.put("score", item.score());
        return ref;
    }

    private Map<String, Object> toRetrievalScore(ScoredBlock item) {
        Map<String, Object> score = new LinkedHashMap<>();
        score.put("sourceId", item.block().getBlockId());
        score.put("score", item.score());
        score.put("reason", item.reason());
        return score;
    }

    private Map<String, Object> toOriginalSource(DocumentBlock block) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("sourceId", block.getBlockId());
        source.put("type", "original_block");
        source.put("title", block.getSectionPath());
        source.put("sectionPath", block.getSectionPath());
        source.put("text", block.getTextContent());
        source.put("textLength", block.getTextContent() == null ? 0 : block.getTextContent().length());
        source.put("estimatedTokens", ChunkUtils.estimateTokens(block.getTextContent()));
        return source;
    }

    private String normalizeCheckStatus(Object raw) {
        if (raw == null) return "Review";
        String value = raw.toString().trim();
        if (ReviewResultSchema.CHECK_STATUS_ENUM.contains(value)) return value;
        String lower = value.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "pass", "passed" -> "Pass";
            case "partial", "partially_passed" -> "Partial";
            case "fail", "failed" -> "Fail";
            case "n/a", "na", "not_applicable", "not applicable" -> "N/A";
            default -> "Review";
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String sha1(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Objects.hashCode(text));
        }
    }

    private long stableSeed(String taskId, int sequence) {
        String src = taskId + ":rag:" + sequence;
        long value = 1125899906842597L;
        for (int i = 0; i < src.length(); i++) {
            value = 31 * value + src.charAt(i);
        }
        return Math.abs(value == Long.MIN_VALUE ? 0L : value);
    }

    private void updateTaskStatus(ReviewTask task, String status, String failReason) {
        task.setStatus(status);
        task.setFailReason(failReason);
        task.setUpdatedAt(LocalDateTime.now());
        reviewTaskMapper.updateById(task);
    }

    private record CheckPlan(Rule rule, RuleCheck check, String ruleCode, String checkCode,
                             String checkQuestion, String passCriteria, String category) {
        String ruleName() {
            return rule != null ? rule.getRuleName() : "";
        }

        String ruleContent() {
            return rule != null ? rule.getContent() : "";
        }
    }

    public record PreparedDocument(List<DocumentBlock> blocks,
                                   AiModelConfig embeddingModel,
                                   int embeddingDimension,
                                   String vectorIndexStrategy) {
    }

    private record IndexedPlan(int index, CheckPlan plan) {
    }

    private record CheckAttempt(int index,
                                Map<String, Object> result,
                                List<ScoredBlock> evidence,
                                boolean reranked,
                                Exception error) {
        static CheckAttempt success(int index, Map<String, Object> result, boolean reranked) {
            return new CheckAttempt(index, result, List.of(), reranked, null);
        }

        static CheckAttempt failure(int index, List<ScoredBlock> evidence,
                                    boolean reranked, Exception error) {
            return new CheckAttempt(index, null, List.copyOf(evidence), reranked, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    private record ScoredBlock(DocumentBlock block, double score, String reason) {
    }
}
