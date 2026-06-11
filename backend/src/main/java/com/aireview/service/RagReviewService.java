package com.aireview.service;

import com.aireview.dto.ManualCheckDecisionRequest;
import com.aireview.dto.PageResponse;
import com.aireview.dto.ReviewTaskDTO;
import com.aireview.entity.AiModelConfig;
import com.aireview.entity.RagDocumentBlock;
import com.aireview.entity.RagReviewAuditLog;
import com.aireview.entity.RagReviewTask;
import com.aireview.entity.RagRule;
import com.aireview.entity.RagRuleCheck;
import com.aireview.repository.DocumentVectorRepository;
import com.aireview.repository.RagReviewAuditLogMapper;
import com.aireview.repository.RagReviewTaskMapper;
import com.aireview.repository.RagRuleCheckMapper;
import com.aireview.review.ReviewResultSchema;
import com.aireview.review.llm.JsonExtractor;
import com.aireview.util.ChunkUtils;
import com.aireview.util.WordParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagReviewService {

    private final RagReviewTaskMapper ragReviewTaskMapper;
    private final RagReviewAuditLogMapper ragReviewAuditLogMapper;
    private final RagRuleService ragRuleService;
    private final RagRuleCheckMapper ragRuleCheckMapper;
    private final DocumentVectorRepository documentVectorRepository;
    private final AiModelService aiModelService;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.documents-dir}")
    private String documentsDir;

    /** Tracks cancelled task IDs so the async loop can exit early. */
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

    /**
     * Self-reference to invoke {@code @Async} methods through the Spring proxy.
     * Same reason as in {@link ReviewService}: {@code this.executeReviewAsync(...)}
     * bypasses AOP and would run synchronously on the caller's thread.
     */
    @Lazy
    @Autowired
    private RagReviewService self;

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

    // ==============================================================================
    //  Controller-facing API (task CRUD + manual decisions + exports).
    //  Mirrors the surface of chunk's {@link ReviewService} so the RAG controller is
    //  a thin parallel of the chunk controller. The differences are purely table-level
    //  (rag_review_tasks / rag_review_audit_logs) and the pipeline that runs underneath
    //  (RAG: vector recall + per-check eval, defined further down in this file).
    // ==============================================================================

    /**
     * Submit a RAG review task: upload the document and start async processing.
     */
    public ReviewTaskDTO submitReview(MultipartFile file, Long scenarioId,
                                      String selectedModel, Long userId) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || (!originalFilename.endsWith(".doc") && !originalFilename.endsWith(".docx"))) {
            throw new IllegalArgumentException("Only Word documents (.doc, .docx) are supported");
        }

        Path uploadDir = Path.of(documentsDir);
        Files.createDirectories(uploadDir);
        String savedFileName = UUID.randomUUID() + "_" + originalFilename;
        Path savedPath = uploadDir.resolve(savedFileName);
        Files.write(savedPath, file.getBytes());

        RagReviewTask task = new RagReviewTask();
        task.setUserId(userId);
        task.setFileName(originalFilename);
        task.setFilePath(savedPath.toString());
        task.setScenarioId(scenarioId);
        task.setSelectedModel(selectedModel);
        task.setStatus(RagReviewTask.STATUS_PENDING);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        ragReviewTaskMapper.insert(task);

        log.info("RAG review task created: {} for file {} using model {}",
                task.getId(), originalFilename, selectedModel);

        // Use the proxy so @Async actually fires.
        self.executeReviewAsync(task.getId());
        return toDTO(task);
    }

    public ReviewTaskDTO getTask(String taskId, Long userId) {
        RagReviewTask task = ragReviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only view your own tasks");
        }
        return toDTO(task);
    }

    public PageResponse<ReviewTaskDTO> listTasks(Long userId, int page, int size, String status) {
        Page<RagReviewTask> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<RagReviewTask> query = new LambdaQueryWrapper<>();
        query.eq(RagReviewTask::getUserId, userId);
        if (status != null && !status.isBlank()) {
            query.eq(RagReviewTask::getStatus, status.toUpperCase());
        }
        query.orderByDesc(RagReviewTask::getCreatedAt);
        Page<RagReviewTask> result = ragReviewTaskMapper.selectPage(pageParam, query);
        List<ReviewTaskDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    /**
     * Lightweight listing for cross-pipeline merges. Returns recent tasks (sorted
     * desc) up to a limit, with the {@code reviewMode} field populated. Used by the
     * unified workbench list endpoint.
     */
    public List<ReviewTaskDTO> recentTasksForUser(Long userId, int limit) {
        LambdaQueryWrapper<RagReviewTask> query = new LambdaQueryWrapper<>();
        query.eq(RagReviewTask::getUserId, userId);
        query.orderByDesc(RagReviewTask::getCreatedAt);
        Page<RagReviewTask> pageParam = new Page<>(1, Math.max(1, limit));
        return ragReviewTaskMapper.selectPage(pageParam, query).getRecords()
                .stream().map(this::toDTO).toList();
    }

    public Map<String, Object> getStats(Long userId) {
        LambdaQueryWrapper<RagReviewTask> baseQuery = new LambdaQueryWrapper<>();
        baseQuery.eq(RagReviewTask::getUserId, userId);
        long total = ragReviewTaskMapper.selectCount(baseQuery);
        long completed = ragReviewTaskMapper.selectCount(
                new LambdaQueryWrapper<RagReviewTask>()
                        .eq(RagReviewTask::getUserId, userId)
                        .eq(RagReviewTask::getStatus, RagReviewTask.STATUS_COMPLETED));
        long processing = ragReviewTaskMapper.selectCount(
                new LambdaQueryWrapper<RagReviewTask>()
                        .eq(RagReviewTask::getUserId, userId)
                        .eq(RagReviewTask::getStatus, RagReviewTask.STATUS_PROCESSING));
        long failed = ragReviewTaskMapper.selectCount(
                new LambdaQueryWrapper<RagReviewTask>()
                        .eq(RagReviewTask::getUserId, userId)
                        .eq(RagReviewTask::getStatus, RagReviewTask.STATUS_FAILED));
        long todayCount = ragReviewTaskMapper.selectCount(
                new LambdaQueryWrapper<RagReviewTask>()
                        .eq(RagReviewTask::getUserId, userId)
                        .ge(RagReviewTask::getCreatedAt, LocalDateTime.now().toLocalDate().atStartOfDay()));
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("completed", completed);
        stats.put("processing", processing);
        stats.put("failed", failed);
        stats.put("todayCount", todayCount);
        return stats;
    }

    public void deleteTask(String taskId, Long userId) {
        RagReviewTask task = requireOwnedTask(taskId, userId);
        if (RagReviewTask.STATUS_PROCESSING.equals(task.getStatus())) {
            throw new IllegalArgumentException("Cannot delete a task that is currently processing. Cancel it first.");
        }
        ragReviewTaskMapper.deleteById(taskId);
        log.info("RAG review task deleted: {}", taskId);
    }

    public void cancelTask(String taskId, Long userId) {
        RagReviewTask task = requireOwnedTask(taskId, userId);
        String status = task.getStatus();
        if (!RagReviewTask.STATUS_PENDING.equals(status) && !RagReviewTask.STATUS_PROCESSING.equals(status)) {
            throw new IllegalArgumentException("Only pending or processing tasks can be cancelled");
        }
        cancelledTasks.add(taskId);
        updateTaskStatus(task, RagReviewTask.STATUS_CANCELLED, "User cancelled");
        webSocketService.sendTaskUpdate(taskId, RagReviewTask.STATUS_CANCELLED, "Task cancelled by user");
        log.info("RAG review task cancelled: {}", taskId);
    }

    public ReviewTaskDTO reReview(String taskId, Long userId) {
        RagReviewTask original = requireOwnedTask(taskId, userId);
        RagReviewTask task = new RagReviewTask();
        task.setUserId(userId);
        task.setFileName(original.getFileName());
        task.setFilePath(original.getFilePath());
        task.setScenarioId(original.getScenarioId());
        task.setSelectedModel(original.getSelectedModel());
        task.setStatus(RagReviewTask.STATUS_PENDING);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        ragReviewTaskMapper.insert(task);
        log.info("RAG re-review task created: {} from original: {}", task.getId(), taskId);
        self.executeReviewAsync(task.getId());
        return toDTO(task);
    }

    /**
     * RAG retry-failed-checks: re-runs the full task with the same scenario/model.
     *
     * <p>Why a full re-run (not selective retry like chunk): RAG operates per atomic
     * check, and a single check costs only one AI call. Selective retry would require
     * pickle-loading individual check states. Re-running the whole document is simpler
     * and only marginally more expensive — the vector index is reused; recall is
     * deterministic; only the LLM eval pass repeats.
     */
    public ReviewTaskDTO retryFailedChunks(String taskId, Long userId) {
        RagReviewTask task = requireOwnedTask(taskId, userId);
        if (RagReviewTask.STATUS_PROCESSING.equals(task.getStatus())) {
            throw new IllegalArgumentException("Task is currently processing");
        }
        updateTaskStatus(task, RagReviewTask.STATUS_PROCESSING, null);
        webSocketService.sendTaskProgress(taskId, RagReviewTask.STATUS_PROCESSING,
                "RAG 重新审查所有检查项...", 5);
        self.executeReviewAsync(taskId);
        return toDTO(task);
    }

    public ReviewTaskDTO updateManualCheckDecision(String taskId, Long userId,
                                                    ManualCheckDecisionRequest request) {
        RagReviewTask task = requireOwnedTask(taskId, userId);
        if (task.getAiResult() == null) {
            throw new IllegalArgumentException("No review result available for manual decision");
        }
        if (request == null || request.getCheckCode() == null || request.getCheckCode().isBlank()) {
            throw new IllegalArgumentException("checkCode is required");
        }

        Map<String, Object> aiResult = new LinkedHashMap<>(task.getAiResult());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allCheckResults = aiResult.get("allCheckResults") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : new ArrayList<>();
        Map<String, Object> target = ReviewExportUtil.findCheckResult(
                allCheckResults, request.getCheckCode(), request.getSourceChunk());
        if (target == null) {
            throw new IllegalArgumentException("Check result not found: " + request.getCheckCode());
        }

        Map<String, Object> before = new LinkedHashMap<>(target);
        String finalStatus = normalizeCheckStatus(request.getFinalStatus());
        target.put("manualStatus", finalStatus);
        target.put("manualAccepted", request.getAccepted());
        target.put("manualComment", request.getComment() == null ? "" : request.getComment());
        target.put("manualReviewerId", userId);
        target.put("manualReviewedAt", LocalDateTime.now().toString());

        ReviewExportUtil.syncChunkCheckResult(aiResult, target);
        aiResult.put("manualReviewSummary", ReviewExportUtil.buildManualReviewSummary(allCheckResults));
        task.setAiResult(aiResult);
        task.setUpdatedAt(LocalDateTime.now());
        ragReviewTaskMapper.updateById(task);

        RagReviewAuditLog audit = new RagReviewAuditLog();
        audit.setTaskId(taskId);
        audit.setUserId(userId);
        audit.setAction("manual_check_decision");
        audit.setTargetType("check_result");
        audit.setTargetId(String.valueOf(target.get("check_code")));
        audit.setBeforeValue(before);
        audit.setAfterValue(new LinkedHashMap<>(target));
        audit.setComment(request.getComment());
        audit.setCreatedAt(LocalDateTime.now());
        ragReviewAuditLogMapper.insert(audit);

        return toDTO(task);
    }

    public List<Map<String, Object>> listAuditLogs(String taskId, Long userId) {
        requireOwnedTask(taskId, userId);
        List<RagReviewAuditLog> logs = ragReviewAuditLogMapper.findByTaskId(taskId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RagReviewAuditLog logEntry : logs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", logEntry.getId());
            row.put("taskId", logEntry.getTaskId());
            row.put("userId", logEntry.getUserId());
            row.put("action", logEntry.getAction());
            row.put("targetType", logEntry.getTargetType());
            row.put("targetId", logEntry.getTargetId());
            row.put("beforeValue", logEntry.getBeforeValue());
            row.put("afterValue", logEntry.getAfterValue());
            row.put("comment", logEntry.getComment());
            row.put("createdAt", logEntry.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    public byte[] exportAuditJson(String taskId, Long userId) throws IOException {
        return ReviewExportUtil.toAuditJson(listAuditLogs(taskId, userId), objectMapper);
    }

    public byte[] exportResultToExcel(String taskId, Long userId) throws IOException {
        RagReviewTask task = requireOwnedTask(taskId, userId);
        return ReviewExportUtil.toExcel(task.getAiResult());
    }

    public byte[] exportReviewReportDocx(String taskId, Long userId) throws IOException {
        RagReviewTask task = requireOwnedTask(taskId, userId);
        return ReviewExportUtil.toReportDocx(task.getFileName(), task.getId(),
                task.getSelectedModel(), task.getStatus(), task.getAiResult(),
                listAuditLogs(taskId, userId));
    }

    private RagReviewTask requireOwnedTask(String taskId, Long userId) {
        RagReviewTask task = ragReviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only access your own tasks");
        }
        return task;
    }

    private ReviewTaskDTO toDTO(RagReviewTask task) {
        ReviewTaskDTO dto = new ReviewTaskDTO();
        dto.setId(task.getId());
        dto.setUserId(task.getUserId());
        dto.setFileName(task.getFileName());
        dto.setScenarioId(task.getScenarioId());
        dto.setSelectedModel(task.getSelectedModel());
        dto.setStatus(task.getStatus());
        dto.setAiResult(task.getAiResult());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setFailReason(task.getFailReason());
        dto.setReviewMode("RAG");
        return dto;
    }

    // ============================ Pipeline ============================
    // Everything below is the AI execution pipeline. Called from
    // executeReviewAsync(taskId) only.

    /**
     * Parses and vectorizes the uploaded document before any checklist item is sent
     * to the chat model. This makes document preparation an explicit pipeline stage.
     */
    public PreparedDocument prepareDocumentVectors(RagReviewTask task) throws Exception {
        String taskId = task.getId();
        webSocketService.sendTaskProgress(taskId, RagReviewTask.STATUS_PROCESSING,
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

        webSocketService.sendTaskProgress(taskId, RagReviewTask.STATUS_PROCESSING,
                "RAG: 正在构建原文分块...", 15);
        List<RagDocumentBlock> blocks = buildBlocks(taskId, chapters);
        if (blocks.isEmpty()) {
            throw new IllegalStateException("No source blocks were produced from the uploaded document");
        }
        documentVectorRepository.deleteByTaskId(taskId);

        webSocketService.sendTaskProgress(taskId, RagReviewTask.STATUS_PROCESSING,
                "RAG: 正在向量化 " + blocks.size() + " 个原文分块...", 25);
        embedBlocks(blocks, embeddingModel);
        documentVectorRepository.saveAll(blocks);
        int embeddingDimension = blocks.get(0).getEmbeddingDimension();
        String vectorIndexStrategy = vectorIndexEnabled
                ? documentVectorRepository.ensureHnswIndex(embeddingModel.getModelName(), embeddingDimension)
                : "exact";
        webSocketService.sendTaskProgress(taskId, RagReviewTask.STATUS_PROCESSING,
                "RAG: 文档向量化完成，pgvector " + vectorIndexStrategy
                        + " 索引已就绪（维度 " + embeddingDimension + "）", 34);

        return new PreparedDocument(List.copyOf(blocks), embeddingModel,
                embeddingDimension, vectorIndexStrategy);
    }

    public void executeReview(RagReviewTask task, String runStamp) throws Exception {
        executeReview(task, runStamp, prepareDocumentVectors(task));
    }

    /**
     * Public async entry for the RAG pipeline. Called by {@code RagReviewController}
     * (or by the chunk-side controller via dispatch). Loads the task from
     * {@code rag_review_tasks} and runs through {@link #prepareDocumentVectors} →
     * {@link #executeReview}.
     */
    @Async("reviewTaskExecutor")
    public void executeReviewAsync(String taskId) {
        RagReviewTask task = ragReviewTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("RAG task not found for async execution: {}", taskId);
            return;
        }
        String runStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        try {
            updateTaskStatus(task, RagReviewTask.STATUS_PROCESSING, null);
            webSocketService.sendTaskProgress(taskId, RagReviewTask.STATUS_PROCESSING,
                    "RAG 审查启动，开始文档向量化...", 5);
            executeReview(task, runStamp);
        } catch (Exception e) {
            log.error("RAG review task failed: {}", taskId, e);
            updateTaskStatus(task, RagReviewTask.STATUS_FAILED, e.getMessage());
            webSocketService.sendTaskUpdate(taskId, RagReviewTask.STATUS_FAILED,
                    "审查失败: " + e.getMessage());
        }
    }

    public void executeReview(RagReviewTask task, String runStamp,
                              PreparedDocument preparedDocument) throws Exception {
        String taskId = task.getId();
        AiModelConfig chatModel = aiModelService.getEnabledModel(task.getSelectedModel());
        AiModelConfig embeddingModel = preparedDocument.embeddingModel();
        AiModelConfig rerankerModel =
                aiModelService.getFirstEnabledModelByType(AiModelService.MODEL_TYPE_RERANKER);
        List<RagDocumentBlock> blocks = preparedDocument.blocks();

        webSocketService.sendTaskProgress(taskId, RagReviewTask.STATUS_PROCESSING,
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
            webSocketService.sendTaskProgress(taskId, RagReviewTask.STATUS_PROCESSING,
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
        updateTaskStatus(task, RagReviewTask.STATUS_COMPLETED, null);
        webSocketService.sendTaskUpdate(taskId, RagReviewTask.STATUS_COMPLETED,
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
                                webSocketService.sendTaskProgress(taskId, RagReviewTask.STATUS_PROCESSING,
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

    private List<RagDocumentBlock> buildBlocks(String taskId, List<WordParser.Chapter> chapters) {
        List<RagDocumentBlock> blocks = new ArrayList<>();
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
                RagDocumentBlock block = new RagDocumentBlock();
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

    private void embedBlocks(List<RagDocumentBlock> blocks, AiModelConfig embeddingModel) throws Exception {
        int batchSize = Math.max(1, embeddingBatchSize);
        Integer detectedDimension = null;
        for (int start = 0; start < blocks.size(); start += batchSize) {
            int end = Math.min(blocks.size(), start + batchSize);
            List<RagDocumentBlock> batch = blocks.subList(start, end);
            List<String> texts = batch.stream().map(RagDocumentBlock::getTextContent).toList();
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
                RagDocumentBlock block = batch.get(i);
                block.setEmbeddingModel(embeddingModel.getModelName());
                block.setEmbeddingVector(objectMapper.writeValueAsString(vector));
                block.setEmbeddingDimension(vector.size());
            }
        }
    }

    private List<CheckPlan> buildCheckPlans(Long scenarioId) {
        List<RagRule> rules = ragRuleService.getRulesByScenarioId(scenarioId);
        List<CheckPlan> plans = new ArrayList<>();
        int auto = 1;
        for (RagRule rule : rules) {
            List<RagRuleCheck> checks = ragRuleCheckMapper.findActiveByRuleId(rule.getId());
            String ruleCode = firstNonBlank(rule.getRuleCode(), "R-AUTO-" + String.format("%03d", auto++));
            if (checks.isEmpty()) {
                plans.add(new CheckPlan(rule, null, ruleCode, ruleCode + "-C001",
                        firstNonBlank(rule.getDescription(), rule.getRuleName()),
                        rule.getContent(), "other"));
                continue;
            }
            for (RagRuleCheck check : checks) {
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
                RagDocumentBlock block = item.block();
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

    private Map<String, Object> toOriginalSource(RagDocumentBlock block) {
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

    private void updateTaskStatus(RagReviewTask task, String status, String failReason) {
        task.setStatus(status);
        task.setFailReason(failReason);
        task.setUpdatedAt(LocalDateTime.now());
        ragReviewTaskMapper.updateById(task);
    }

    private record CheckPlan(RagRule rule, RagRuleCheck check, String ruleCode, String checkCode,
                             String checkQuestion, String passCriteria, String category) {
        String ruleName() {
            return rule != null ? rule.getRuleName() : "";
        }

        String ruleContent() {
            return rule != null ? rule.getContent() : "";
        }
    }

    public record PreparedDocument(List<RagDocumentBlock> blocks,
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

    private record ScoredBlock(RagDocumentBlock block, double score, String reason) {
    }
}
