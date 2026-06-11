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

    public void executeReview(ReviewTask task, String runStamp) throws Exception {
        String taskId = task.getId();
        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "RAG: parsing source document...", 8);

        List<WordParser.Chapter> rawChapters = WordParser.parseChapters(task.getFilePath());
        if (rawChapters.isEmpty() || rawChapters.stream().allMatch(ch -> ch.getContent().isBlank())) {
            throw new RuntimeException("Document content is empty or cannot be parsed");
        }
        int firstRealIdx = ChunkUtils.findFirstRealChapterIndex(rawChapters);
        List<WordParser.Chapter> chapters = firstRealIdx > 0
                ? new ArrayList<>(rawChapters.subList(firstRealIdx, rawChapters.size()))
                : rawChapters;

        AiModelConfig chatModel = aiModelService.getEnabledModel(task.getSelectedModel());
        AiModelConfig embeddingModel = aiModelService.getFirstEnabledModelByType(AiModelService.MODEL_TYPE_EMBEDDING);
        if (embeddingModel == null) {
            throw new IllegalStateException("RAG review requires an enabled embedding model");
        }
        AiModelConfig rerankerModel = aiModelService.getFirstEnabledModelByType(AiModelService.MODEL_TYPE_RERANKER);

        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "RAG: building original document blocks...", 15);
        List<DocumentBlock> blocks = buildBlocks(taskId, chapters);
        documentVectorRepository.deleteByTaskId(taskId);

        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "RAG: embedding " + blocks.size() + " source block(s)...", 25);
        embedBlocks(blocks, embeddingModel);
        documentVectorRepository.saveAll(blocks);
        int embeddingDimension = blocks.get(0).getEmbeddingDimension();
        String vectorIndexStrategy = vectorIndexEnabled
                ? documentVectorRepository.ensureHnswIndex(embeddingModel.getModelName(), embeddingDimension)
                : "exact";
        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "RAG: pgvector " + vectorIndexStrategy + " retrieval ready (dimension "
                        + embeddingDimension + ")", 34);

        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "RAG: loading checklist rules...", 38);
        List<CheckPlan> plans = buildCheckPlans(task.getScenarioId());
        if (plans.isEmpty()) {
            throw new RuntimeException("No active rule checks found for scenario: " + task.getScenarioId());
        }

        List<Map<String, Object>> allCheckResults = new ArrayList<>();
        List<Map<String, Object>> chunkResults = new ArrayList<>();
        Map<String, Integer> statusCounts = new TreeMap<>();
        int completed = 0;
        int rerankedCount = 0;

        for (CheckPlan plan : plans) {
            completed++;
            String query = buildRetrievalQuery(plan);
            List<ScoredBlock> recalled = recall(taskId, query, embeddingModel, recallTopK);
            List<ScoredBlock> evidence = rerank(query, recalled, rerankerModel, rerankTopN);
            if (rerankerModel != null && !evidence.isEmpty()) {
                rerankedCount++;
            }

            Map<String, Object> check = reviewCheckWithEvidence(chatModel, plan, evidence, taskId, completed);
            allCheckResults.add(check);
            statusCounts.merge(String.valueOf(check.get("status")), 1, Integer::sum);

            Map<String, Object> chunkResult = new LinkedHashMap<>();
            chunkResult.put("chunk", completed);
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

            int progress = 40 + (int) Math.round((double) completed / plans.size() * 50);
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "RAG: reviewed " + completed + "/" + plans.size() + " check item(s)", progress);
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
        retrievalStats.put("indexStrategy", vectorIndexStrategy);
        retrievalStats.put("embeddingDimension", embeddingDimension);
        retrievalStats.put("blockCount", blocks.size());
        retrievalStats.put("checkCount", plans.size());
        retrievalStats.put("recallTopK", recallTopK);
        retrievalStats.put("rerankTopN", rerankTopN);
        retrievalStats.put("hnswEfSearch", hnswEfSearch);
        retrievalStats.put("rerankedChecks", rerankedCount);
        aiResult.put("retrievalStats", retrievalStats);

        task.setAiResult(aiResult);
        updateTaskStatus(task, ReviewTask.STATUS_COMPLETED, null);
        webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_COMPLETED,
                "RAG review completed: " + plans.size() + " check item(s)");
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

    private record ScoredBlock(DocumentBlock block, double score, String reason) {
    }
}
