package com.aireview.review.chunk.service;

import com.aireview.common.dto.PageResponse;
import com.aireview.review.dto.ManualCheckDecisionRequest;
import com.aireview.review.dto.ReviewTaskDTO;
import com.aireview.modelconfig.entity.AiModelConfig;
import com.aireview.review.chunk.entity.ReviewTask;
import com.aireview.review.chunk.entity.ReviewAuditLog;
import com.aireview.rule.entity.Rule;
import com.aireview.rule.entity.RuleCheck;
import com.aireview.review.chunk.repository.ReviewAuditLogMapper;
import com.aireview.review.chunk.repository.ReviewTaskMapper;
import com.aireview.common.websocket.WebSocketService;
import com.aireview.export.ReviewExportUtil;
import com.aireview.modelconfig.service.AiApiException;
import com.aireview.modelconfig.service.AiCallOptions;
import com.aireview.modelconfig.service.AiModelService;
import com.aireview.rule.service.RuleService;
import com.aireview.rule.repository.RuleCheckMapper;
import com.aireview.rule.repository.RuleMapper;
import com.aireview.review.core.ReviewResultSchema;
import com.aireview.review.llm.ThinkingModeDetector;
import com.aireview.document.ChapterReferenceResolver;
import com.aireview.document.ChunkUtils;
import com.aireview.document.DocumentEvidenceLocator;
import com.aireview.document.DocumentSourceMapper;
import com.aireview.rule.engine.RuleDispatcher;
import com.aireview.rule.engine.RuleMetadata;
import com.aireview.rule.engine.RuleParser;
import com.aireview.document.WordParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final RuleService ruleService;
    private final AiModelService aiModelService;
    private final WebSocketService webSocketService;
    private final RuleCheckMapper ruleCheckMapper;
    private final RuleMapper ruleMapper;
    private final ReviewAuditLogMapper reviewAuditLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Self-reference to invoke {@code @Async} methods through the Spring proxy.
     * Calling {@code this.executeReviewAsync(...)} bypasses the AOP proxy, which makes
     * the call run synchronously on the request thread — that's exactly what was
     * blocking the upload response and leaving the dashboard modal stuck open.
     * {@code @Lazy} breaks the bean's self-referential init cycle.
     */
    @Lazy
    @Autowired
    private ReviewService self;

    /**
     * 切片级并行调用 AI 的独立线程池，配置见 {@code AsyncConfig.chunkReviewExecutor()}。
     * 用字段注入而不是构造器注入，以避免与 Lombok @RequiredArgsConstructor 的 @Qualifier
     * 拷贝行为冲突 —— 项目里 {@code self} 也是同样的字段注入模式。
     */
    @Autowired
    @Qualifier("chunkReviewExecutor")
    private Executor chunkReviewExecutor;

    /** Tracks cancelled task IDs so the async loop can exit early. */
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

    @Value("${file.documents-dir}")
    private String documentsDir;

    @Value("${review.retry.max-attempts}")
    private int maxRetryAttempts;

    @Value("${review.retry.interval-ms}")
    private long retryIntervalMs;

    @Value("${review.chunk.max-tokens}")
    private int maxChunkTokens;

    @Value("${review.chunk.overlap-tokens}")
    private int overlapTokens;

    @Value("${review.parallel.chunk-concurrency}")
    private int chunkConcurrency;

    @Value("${review.dispatch.basic-only-max-chapter:6}")
    private int basicOnlyMaxChapter;

    /**
     * 收敛性审查的统一参数。这些值不放配置是因为它们是「跨模型收敛」契约本身：
     * 改一动就会让历史 ai_result 与新结果失去可比性，所以集中放在代码里。
     */
    private static final double CONVERGENCE_TEMPERATURE = 0.0;
    private static final double CONVERGENCE_TOP_P = 1.0;
    private static final int CONVERGENCE_MAX_TOKENS = 8192;
    /** JSON 解析失败时的最大整体尝试次数（含首次）；每次换种子重新调用模型。 */
    private static final int JSON_PARSE_MAX_ATTEMPTS = 3;
    /** 单切片 prompt 中规则部分的硬上限。超过则按 rule_code asc 截断。 */
    private static final int RULE_BUDGET_TOKENS = 6000;

    /**
     * Submit a review task: upload the document and start async processing.
     *
     * @param file          the Word document to review
     * @param scenarioId    the scenario containing review rules
     * @param selectedModel the AI model to use
     * @param userId        the submitting user's ID
     * @return the created review task DTO
     */
    public ReviewTaskDTO submitReview(MultipartFile file, Long scenarioId,
                                      String selectedModel, Long userId) throws IOException {
        return submitReview(file, scenarioId, selectedModel, userId, true);
    }

    public ReviewTaskDTO submitReview(MultipartFile file, Long scenarioId,
                                      String selectedModel, Long userId,
                                      boolean qualityCheckEnabled) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || (!originalFilename.endsWith(".doc") && !originalFilename.endsWith(".docx"))) {
            throw new IllegalArgumentException("Only Word documents (.doc, .docx) are supported");
        }

        // Save the uploaded file
        Path uploadDir = Path.of(documentsDir);
        Files.createDirectories(uploadDir);
        String savedFileName = UUID.randomUUID() + "_" + originalFilename;
        Path savedPath = uploadDir.resolve(savedFileName);
        Files.write(savedPath, file.getBytes());

        // Create the review task record
        ReviewTask task = new ReviewTask();
        task.setUserId(userId);
        task.setFileName(originalFilename);
        task.setFilePath(savedPath.toString());
        task.setScenarioId(scenarioId);
        task.setSelectedModel(selectedModel);
        task.setQualityCheckEnabled(qualityCheckEnabled);
        task.setStatus(ReviewTask.STATUS_PENDING);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        reviewTaskMapper.insert(task);

        log.info("Review task created: {} for file {} using model {}",
                task.getId(), originalFilename, selectedModel);

        // Start async processing — must go through the proxy so @Async actually fires.
        self.executeReviewAsync(task.getId());

        return toDTO(task);
    }

    /**
     * Get a review task by ID.
     */
    public ReviewTaskDTO getTask(String taskId, Long userId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only view your own tasks");
        }
        return toDTO(task, true);
    }

    /**
     * Lightweight detail for fast first paint: the check matrix (with rule metadata)
     * and all summary scalars, but WITHOUT the heavy {@code originalSources} /
     * {@code chunkResults}. The frontend pulls those lazily via {@link #getSources}.
     */
    public ReviewTaskDTO getTaskLight(String taskId, Long userId) {
        // SQL 层投影掉 originalSources/chunkResults，不读取/反序列化整条大 ai_result。
        ReviewTask task = reviewTaskMapper.selectLightById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only view your own tasks");
        }
        return toLightDetailDTO(task);
    }

    /**
     * The heavy source-tracing payload split out of the detail response:
     * {@code originalSources} (rebuilt from the document only if not already cached)
     * plus the raw {@code chunkResults}. Fetched on demand by the workspace page.
     */
    public Map<String, Object> getSources(String taskId, Long userId) {
        // SQL 层只取 originalSources/chunkResults 两个大字段，其它内容不反序列化。
        ReviewTask task = reviewTaskMapper.selectSourcesById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only view your own tasks");
        }
        Map<String, Object> sources = new LinkedHashMap<>();
        Map<String, Object> aiResult = task.getAiResult();
        // jsonb_build_object 总会带上 key（值可能为 null），故用显式判空而非 getOrDefault。
        Object cached = aiResult == null ? null : aiResult.get("originalSources");
        sources.put("originalSources", cached != null ? cached : buildOriginalSources(task));
        Object chunkResults = aiResult == null ? null : aiResult.get("chunkResults");
        sources.put("chunkResults", chunkResults != null ? chunkResults : new ArrayList<>());
        return sources;
    }

    /**
     * List review tasks for a user with pagination and optional status filter.
     */
    public PageResponse<ReviewTaskDTO> listTasks(Long userId, int page, int size, String status) {
        Page<ReviewTask> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ReviewTask> query = new LambdaQueryWrapper<>();
        query.eq(ReviewTask::getUserId, userId);
        if (status != null && !status.isBlank()) {
            query.eq(ReviewTask::getStatus, status.toUpperCase());
        }
        query.orderByDesc(ReviewTask::getCreatedAt);
        Page<ReviewTask> result = reviewTaskMapper.selectPage(pageParam, query);
        List<ReviewTaskDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    /**
     * Get review statistics for a user.
     */
    public Map<String, Object> getStats(Long userId) {
        LambdaQueryWrapper<ReviewTask> baseQuery = new LambdaQueryWrapper<>();
        baseQuery.eq(ReviewTask::getUserId, userId);
        long total = reviewTaskMapper.selectCount(baseQuery);

        LambdaQueryWrapper<ReviewTask> completedQuery = new LambdaQueryWrapper<>();
        completedQuery.eq(ReviewTask::getUserId, userId)
                      .eq(ReviewTask::getStatus, ReviewTask.STATUS_COMPLETED);
        long completed = reviewTaskMapper.selectCount(completedQuery);

        LambdaQueryWrapper<ReviewTask> processingQuery = new LambdaQueryWrapper<>();
        processingQuery.eq(ReviewTask::getUserId, userId)
                       .eq(ReviewTask::getStatus, ReviewTask.STATUS_PROCESSING);
        long processing = reviewTaskMapper.selectCount(processingQuery);

        LambdaQueryWrapper<ReviewTask> failedQuery = new LambdaQueryWrapper<>();
        failedQuery.eq(ReviewTask::getUserId, userId)
                   .eq(ReviewTask::getStatus, ReviewTask.STATUS_FAILED);
        long failed = reviewTaskMapper.selectCount(failedQuery);

        LambdaQueryWrapper<ReviewTask> todayQuery = new LambdaQueryWrapper<>();
        todayQuery.eq(ReviewTask::getUserId, userId)
                  .ge(ReviewTask::getCreatedAt, LocalDateTime.now().toLocalDate().atStartOfDay());
        long todayCount = reviewTaskMapper.selectCount(todayQuery);

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("completed", completed);
        stats.put("processing", processing);
        stats.put("failed", failed);
        stats.put("todayCount", todayCount);
        return stats;
    }

    /**
     * Delete a review task (only non-processing tasks).
     */
    public void deleteTask(String taskId, Long userId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only delete your own tasks");
        }
        if (ReviewTask.STATUS_PROCESSING.equals(task.getStatus())) {
            throw new IllegalArgumentException("Cannot delete a task that is currently processing. Cancel it first.");
        }
        reviewTaskMapper.deleteById(taskId);
        log.info("Review task deleted: {}", taskId);
    }

    public ReviewTaskDTO updateManualCheckDecision(String taskId, Long userId,
                                                   ManualCheckDecisionRequest request) {
        ReviewTask task = requireOwnedTask(taskId, userId);
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
                allCheckResults, request.getFindingId(), request.getCheckCode(), request.getSourceChunk());
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
        task.setProblemCount(ReviewExportUtil.computeProblemCount(aiResult));
        task.setUpdatedAt(LocalDateTime.now());
        reviewTaskMapper.updateById(task);

        ReviewAuditLog audit = new ReviewAuditLog();
        audit.setTaskId(taskId);
        audit.setUserId(userId);
        audit.setAction("manual_check_decision");
        audit.setTargetType("check_result");
        audit.setTargetId(String.valueOf(target.get("check_code")));
        audit.setBeforeValue(before);
        audit.setAfterValue(new LinkedHashMap<>(target));
        audit.setComment(request.getComment());
        audit.setCreatedAt(LocalDateTime.now());
        reviewAuditLogMapper.insert(audit);

        return toDTO(task, true);
    }

    public List<Map<String, Object>> listAuditLogs(String taskId, Long userId) {
        requireOwnedTask(taskId, userId);
        List<ReviewAuditLog> logs = reviewAuditLogMapper.findByTaskId(taskId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReviewAuditLog logEntry : logs) {
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
        return objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(listAuditLogs(taskId, userId));
    }

    /**
     * Cancel a running review task.
     */
    public void cancelTask(String taskId, Long userId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only cancel your own tasks");
        }
        String status = task.getStatus();
        if (!ReviewTask.STATUS_PENDING.equals(status) && !ReviewTask.STATUS_PROCESSING.equals(status)) {
            throw new IllegalArgumentException("Only pending or processing tasks can be cancelled");
        }
        cancelledTasks.add(taskId);
        updateTaskStatus(task, ReviewTask.STATUS_CANCELLED, "User cancelled");
        webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_CANCELLED, "Task cancelled by user");
        log.info("Review task cancelled: {}", taskId);
    }

    /**
     * Re-review: create a new task reusing the same file, scenario, and model.
     */
    public ReviewTaskDTO reReview(String taskId, Long userId) {
        ReviewTask original = reviewTaskMapper.selectById(taskId);
        if (original == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!original.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only re-review your own tasks");
        }

        ReviewTask task = new ReviewTask();
        task.setUserId(userId);
        task.setFileName(original.getFileName());
        task.setFilePath(original.getFilePath());
        task.setScenarioId(original.getScenarioId());
        task.setSelectedModel(original.getSelectedModel());
        task.setStatus(ReviewTask.STATUS_PENDING);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        reviewTaskMapper.insert(task);

        log.info("Re-review task created: {} from original: {}", task.getId(), taskId);
        self.executeReviewAsync(task.getId());
        return toDTO(task);
    }

    public ReviewTaskDTO retryFailedChunks(String taskId, Long userId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only retry your own tasks");
        }
        if (ReviewTask.STATUS_PROCESSING.equals(task.getStatus())) {
            throw new IllegalArgumentException("Task is currently processing");
        }

        Set<Integer> failedChunkNumbers = extractFailedChunkNumbers(task.getAiResult());
        if (failedChunkNumbers.isEmpty()) {
            throw new IllegalArgumentException("No failed chunks to retry");
        }

        updateTaskStatus(task, ReviewTask.STATUS_PROCESSING, null);
        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                "开始重新审查失败切片，共 " + failedChunkNumbers.size() + " 个", 5);
        self.retryFailedChunksAsync(taskId);
        return toDTO(task);
    }

    @Async("reviewTaskExecutor")
    public void retryFailedChunksAsync(String taskId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("Task not found for failed-chunk retry: {}", taskId);
            return;
        }

        String runStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Map<String, Object> existingResult = task.getAiResult();
        Set<Integer> failedChunkNumbers = extractFailedChunkNumbers(existingResult);
        if (failedChunkNumbers.isEmpty()) {
            updateTaskStatus(task, ReviewTask.STATUS_COMPLETED, null);
            webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_COMPLETED, "没有需要重新审查的失败切片");
            return;
        }

        try {
            List<WordParser.Chapter> rawChapters = WordParser.parseChapters(task.getFilePath());
            int firstRealIdx = ChunkUtils.findFirstRealChapterIndex(rawChapters);
            List<WordParser.Chapter> chapters = firstRealIdx > 0
                    ? new ArrayList<>(rawChapters.subList(firstRealIdx, rawChapters.size()))
                    : rawChapters;
            List<Rule> rules = ruleService.getRulesByScenarioId(task.getScenarioId());
            List<RuleDispatcher.PreparedRule> preparedRules = RuleDispatcher.prepare(rules);
            List<String> declaredTestItems = extractDeclaredTestItems(chapters);
            attachChecks(preparedRules);
            AiModelConfig modelConfig = aiModelService.getEnabledModel(task.getSelectedModel());
            List<ChunkUtils.ChunkResult> chunks = ChunkUtils.chunkByChapters(chapters, maxChunkTokens);
            final boolean qualityCheck = !Boolean.FALSE.equals(task.getQualityCheckEnabled());

            List<Integer> validChunkNumbers = failedChunkNumbers.stream()
                    .filter(n -> n != null && n >= 1 && n <= chunks.size())
                    .toList();
            if (validChunkNumbers.size() < failedChunkNumbers.size()) {
                log.warn("Dropped {} invalid failed chunk number(s) for task {}",
                        failedChunkNumbers.size() - validChunkNumbers.size(), taskId);
            }

            int totalToRetry = validChunkNumbers.size();
            int effectiveConcurrency = Math.min(chunkConcurrency, Math.max(1, totalToRetry));
            log.info("Starting parallel failed-chunk retry: task={}, total={}, concurrency={}",
                    taskId, totalToRetry, effectiveConcurrency);
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "开始并行重新审查失败切片，共 " + totalToRetry + " 个（并发度 "
                            + effectiveConcurrency + "）...", 10);

            // Same parent-thread gating as executeReviewAsync — see comment there for why.
            Semaphore taskSlots = new Semaphore(effectiveConcurrency);
            AtomicInteger retriedCount = new AtomicInteger(0);
            Map<Integer, Map<String, Object>> replacements = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>(totalToRetry);

            for (Integer chunkNumber : validChunkNumbers) {
                if (cancelledTasks.contains(taskId)) {
                    break;
                }
                try {
                    taskSlots.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (cancelledTasks.contains(taskId)) {
                    taskSlots.release();
                    break;
                }

                final int chunkIdx = chunkNumber - 1;
                final int displayChunk = chunkNumber;
                final ChunkUtils.ChunkResult chunk = chunks.get(chunkIdx);
                final RuleDispatcher.DispatchResult dispatch = RuleDispatcher.dispatchForChunk(
                        chunk.getLabel(), chunk.getContent(), preparedRules, basicOnlyMaxChapter,
                        isTestItemChapter(chunk.getLabel(), declaredTestItems));

                CompletableFuture<Void> fut = CompletableFuture.runAsync(() -> {
                    long startNs = System.nanoTime();
                    try {
                        Map<String, Object> replacement;
                        try {
                            replacement = reviewSingleChunk(chunkIdx, chunks.size(), chunk, dispatch, chapters, modelConfig, qualityCheck);
                            long elapsedMs = elapsedMs(startNs);
                            replacement.put("elapsedMs", elapsedMs);
                            int done = retriedCount.incrementAndGet();
                            log.info("Failed chunk retry completed: task={}, chunk={}/{}, title='{}', tokens={}, rules={}, elapsedMs={}",
                                    taskId, displayChunk, chunks.size(), chunk.getLabel(), chunk.getEstimatedTokens(),
                                    dispatch.getAppliedRuleNames().size(), elapsedMs);
                            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                                    "失败切片重审完成 " + displayChunk + "/" + chunks.size() + " [" + chunk.getLabel()
                                            + "]，tokens=" + chunk.getEstimatedTokens()
                                            + "，规则=" + dispatch.getAppliedRuleNames().size()
                                            + "，耗时=" + elapsedMs + "ms (" + done + "/" + totalToRetry + ")",
                                    10 + (int) ((double) done / totalToRetry * 80));
                        } catch (Exception e) {
                            long elapsedMs = elapsedMs(startNs);
                            replacement = buildFailedChunkResult(chunkIdx, chunks.size(), chunk, dispatch, e, elapsedMs);
                            int done = retriedCount.incrementAndGet();
                            log.warn("Failed chunk retry still failed: task={}, chunk={}/{}, title='{}', tokens={}, rules={}, elapsedMs={}, reason={}",
                                    taskId, displayChunk, chunks.size(), chunk.getLabel(), chunk.getEstimatedTokens(),
                                    dispatch.getAppliedRuleNames().size(), elapsedMs, e.getMessage(), e);
                            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                                    "失败切片重审仍失败 " + displayChunk + "/" + chunks.size() + " [" + chunk.getLabel()
                                            + "]，tokens=" + chunk.getEstimatedTokens()
                                            + "，规则=" + dispatch.getAppliedRuleNames().size()
                                            + "，耗时=" + elapsedMs + "ms，原因：" + e.getMessage(),
                                    10 + (int) ((double) done / totalToRetry * 80));
                        }
                        replacements.put(displayChunk, replacement);
                    } finally {
                        taskSlots.release();
                    }
                }, chunkReviewExecutor);

                futures.add(fut);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            if (cancelledTasks.remove(taskId)) {
                updateTaskStatus(task, ReviewTask.STATUS_CANCELLED, "User cancelled");
                webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_CANCELLED, "Task cancelled by user");
                return;
            }

            List<Map<String, Object>> chunkResults = copyChunkResults(existingResult);
            for (Map.Entry<Integer, Map<String, Object>> entry : replacements.entrySet()) {
                replaceChunkResult(chunkResults, entry.getValue());
            }
            Map<String, Object> aggregatedResult = aggregateResults(chunkResults);
            saveAiResultToFile(taskId, task.getFileName(), task.getSelectedModel(), runStamp, aggregatedResult);
            task.setAiResult(aggregatedResult);
            updateTaskStatus(task, ReviewTask.STATUS_COMPLETED, null);

            int failedChunkCount = ((Number) aggregatedResult.getOrDefault("failedChunkCount", 0)).intValue();
            webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_COMPLETED,
                    failedChunkCount > 0
                            ? "失败切片重审完成，仍有 " + failedChunkCount + " 个切片失败"
                            : "失败切片已全部重审完成");
        } catch (Exception e) {
            log.error("Failed-chunk retry task failed: {}", taskId, e);
            updateTaskStatus(task, ReviewTask.STATUS_FAILED, e.getMessage());
            webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_FAILED,
                    "失败切片重审失败: " + e.getMessage());
        }
    }

    /**
     * Asynchronously execute the review task:
     * 1. Parse the Word document
     * 2. Build the system prompt from scenario rules
     * 3. Chunk the document if it exceeds the context window
     * 4. Call the AI model for each chunk
     * 5. Aggregate results
     * 6. Retry on failure (up to maxRetryAttempts with retryIntervalMs interval)
     */
    @Async("reviewTaskExecutor")
    public void executeReviewAsync(String taskId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("Task not found for async execution: {}", taskId);
            return;
        }

        // Single timestamp used for both 切片结果 and 审查结果 file names so they share
        // a consistent suffix and are easy to associate.
        String runStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        try {
            // Update status to PROCESSING
            updateTaskStatus(task, ReviewTask.STATUS_PROCESSING, null);
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "开始文件审查...", 5);

            // 1. Parse Word document into chapters (by Heading 1)
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "正在解析文档结构（按一级标题拆分章节）...", 10);
            List<WordParser.Chapter> rawChapters = WordParser.parseChapters(task.getFilePath());
            if (rawChapters.isEmpty() || rawChapters.stream().allMatch(ch -> ch.getContent().isBlank())) {
                throw new RuntimeException("文档内容为空或无法解析");
            }

            // Skip leading front matter (封面/签署页/目录/图表清单) — start review from the
            // first chapter whose title begins with a chapter number, e.g. "1 试验目的".
            int firstRealIdx = ChunkUtils.findFirstRealChapterIndex(rawChapters);
            List<WordParser.Chapter> chapters = firstRealIdx > 0
                    ? new ArrayList<>(rawChapters.subList(firstRealIdx, rawChapters.size()))
                    : rawChapters;
            if (firstRealIdx > 0) {
                log.info("Skipped {} front-matter chapter(s); first real chapter: '{}'",
                        firstRealIdx, chapters.get(0).getTitle());
            }
            log.info("Document parsed into {} chapter(s) (after front-matter trim)", chapters.size());
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "文档解析完成，跳过封面/目录等前置内容后共 " + chapters.size() + " 个章节", 15);

            // 2. Load and prepare scenario rules (parse metadata + strip frontmatter)
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "正在加载审查规则...", 20);
            List<Rule> rules = ruleService.getRulesByScenarioId(task.getScenarioId());
            if (rules.isEmpty()) {
                throw new RuntimeException("审查场景中没有找到有效规则，场景ID: " + task.getScenarioId());
            }
            List<RuleDispatcher.PreparedRule> preparedRules = RuleDispatcher.prepare(rules);
            attachChecks(preparedRules);
            int globalCount = (int) preparedRules.stream().filter(RuleDispatcher.PreparedRule::isGlobal).count();
            int sectionCount = (int) preparedRules.stream().filter(RuleDispatcher.PreparedRule::isSectionSpecific).count();
            int docCount = (int) preparedRules.stream().filter(RuleDispatcher.PreparedRule::isDocumentSpecific).count();
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "已加载 " + rules.size() + " 条规则（通用 " + globalCount
                            + " 条，专项 " + sectionCount + " 条，文档级 " + docCount + " 条）", 25);

            // 3. Get AI model config
            AiModelConfig modelConfig = aiModelService.getEnabledModel(task.getSelectedModel());

            // 4. Chunk chapters (each chapter = 1 chunk if under maxTokens, otherwise sub-split)
            List<ChunkUtils.ChunkResult> chunks = ChunkUtils.chunkByChapters(chapters, maxChunkTokens);
            log.info("Document split into {} chunk(s) for AI review", chunks.size());
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "文档已切分为 " + chunks.size() + " 个片段，开始调用AI审查...", 30);

            // 试验项目章节识别：从试验概述(7.1)动态提取声明的试验项目清单，供 test_item 规则按章路由。
            List<String> declaredTestItems = extractDeclaredTestItems(chapters);
            if (!declaredTestItems.isEmpty()) {
                log.info("Detected {} declared test item(s) from overview: {}",
                        declaredTestItems.size(), declaredTestItems);
            }

            // 5a. Pre-compute dispatch for every chunk so we can write the debug file
            // BEFORE any AI call. This way a 429 / timeout on chunk 1 still leaves a
            // diagnosable 切片结果.json on disk with the applied-rule traces.
            List<RuleDispatcher.DispatchResult> dispatches = new ArrayList<>();
            List<Map<String, Object>> chunkDispatchTraces = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkUtils.ChunkResult chunk = chunks.get(i);
                RuleDispatcher.DispatchResult dispatch = RuleDispatcher.dispatchForChunk(
                        chunk.getLabel(), chunk.getContent(), preparedRules, basicOnlyMaxChapter,
                        isTestItemChapter(chunk.getLabel(), declaredTestItems));
                dispatches.add(dispatch);

                Map<String, Object> dispatchEntry = new LinkedHashMap<>();
                dispatchEntry.put("chunk", i + 1);
                dispatchEntry.put("chapterTitle", chunk.getLabel());
                dispatchEntry.put("appliedRules", dispatch.getAppliedRuleNames());
                dispatchEntry.put("matchTraces", dispatch.getMatchTraces());
                chunkDispatchTraces.add(dispatchEntry);
            }
            // Write debug file early so partial failures stay diagnosable.
            saveChunkDebugInfo(taskId, task.getFileName(), task.getSelectedModel(), runStamp,
                    chapters, chunks, chunkDispatchTraces);

            // 5b. 逐章节并行审查：每个章节切片 = 一次独立 AI 调用，单元内容为
            //     「命中的规则（system prompt）+ 本章节切片 + 本章节引用到的其他章节切片（user message）」。
            //
            // 设计要点：
            //   1. 不再做批处理打包/采样翻倍/批兜底重发 —— 这些在限流(429)场景下会放大调用量、
            //      形成「越限流越猛打」的失败风暴。改为每章节单次调用，调用量与章节数严格 1:1。
            //   2. 跨章节引用上下文由 reviewSingleChunk → ChapterReferenceResolver 自动附加。
            //   3. 并发调度：父线程 Semaphore 控制单任务章节并发（与失败重审路径一致）；
            //      单章节失败只标记为可重试切片，不影响其他章节，事后可「重审失败切片」。
            int totalChunks = chunks.size();
            int effectiveConcurrency = Math.max(1, Math.min(chunkConcurrency, totalChunks));
            log.info("Starting per-chapter parallel review: task={}, chapters/chunks={}, concurrency={}",
                    taskId, totalChunks, effectiveConcurrency);
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "开始逐章节并行审查，共 " + totalChunks + " 个章节切片（并发度 "
                            + effectiveConcurrency + "）...", 35);

            @SuppressWarnings({"unchecked", "rawtypes"})
            final Map<String, Object>[] orderedResults = new Map[totalChunks];
            AtomicInteger completedChunkCount = new AtomicInteger(0);
            // 全文质量检查开关（用户在新建审查时选择；默认开启）。关闭时，纯基础质量、
            // 无任何业务规则命中的章节将被直接跳过，不再调用模型——这也是这类审查变快的关键。
            final boolean qualityCheck = !Boolean.FALSE.equals(task.getQualityCheckEnabled());

            Semaphore taskSlots = new Semaphore(effectiveConcurrency);
            List<CompletableFuture<Void>> chunkFutures = new ArrayList<>(totalChunks);

            for (int i = 0; i < totalChunks; i++) {
                if (cancelledTasks.contains(taskId)) break;

                // 关闭全文质量检查时，跳过没有命中任何业务规则的章节（否则只会跑一遍基础质量）。
                if (!qualityCheck && dispatches.get(i).getAppliedRules().isEmpty()) {
                    orderedResults[i] = buildSkippedChunkResult(i, totalChunks, chunks.get(i));
                    int done = completedChunkCount.incrementAndGet();
                    webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                            "已跳过 " + (i + 1) + "/" + totalChunks + " [" + chunks.get(i).getLabel()
                                    + "]（未命中业务规则，全文质量检查已关闭）(" + done + "/" + totalChunks + ")",
                            35 + (int) ((double) done / totalChunks * 55));
                    continue;
                }

                try {
                    taskSlots.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (cancelledTasks.contains(taskId)) {
                    taskSlots.release();
                    break;
                }

                final int chunkIdx = i;
                final int displayChunk = i + 1;
                final ChunkUtils.ChunkResult chunk = chunks.get(i);
                final RuleDispatcher.DispatchResult dispatch = dispatches.get(i);

                CompletableFuture<Void> fut = CompletableFuture.runAsync(() -> {
                    long startNs = System.nanoTime();
                    Map<String, Object> result;
                    try {
                        result = reviewSingleChunk(chunkIdx, totalChunks, chunk, dispatch,
                                chapters, modelConfig, taskId, qualityCheck);
                        long elapsedMs = elapsedMs(startNs);
                        result.put("elapsedMs", elapsedMs);
                        int done = completedChunkCount.incrementAndGet();
                        log.info("Chapter review completed: task={}, chunk={}/{}, title='{}', tokens={}, rules={}, elapsedMs={}",
                                taskId, displayChunk, totalChunks, chunk.getLabel(), chunk.getEstimatedTokens(),
                                dispatch.getAppliedRuleNames().size(), elapsedMs);
                        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                                "章节审查完成 " + displayChunk + "/" + totalChunks + " [" + chunk.getLabel()
                                        + "]，tokens=" + chunk.getEstimatedTokens()
                                        + "，规则=" + dispatch.getAppliedRuleNames().size()
                                        + "，耗时=" + elapsedMs + "ms (" + done + "/" + totalChunks + ")",
                                35 + (int) ((double) done / totalChunks * 55));
                    } catch (Exception e) {
                        long elapsedMs = elapsedMs(startNs);
                        result = buildFailedChunkResult(chunkIdx, totalChunks, chunk, dispatch, e, elapsedMs);
                        int done = completedChunkCount.incrementAndGet();
                        log.warn("Chapter review failed: task={}, chunk={}/{}, title='{}', tokens={}, rules={}, elapsedMs={}, reason={}",
                                taskId, displayChunk, totalChunks, chunk.getLabel(), chunk.getEstimatedTokens(),
                                dispatch.getAppliedRuleNames().size(), elapsedMs, e.getMessage(), e);
                        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                                "章节审查失败 " + displayChunk + "/" + totalChunks + " [" + chunk.getLabel()
                                        + "]，原因：" + e.getMessage() + "（可在完成后重审失败切片）",
                                35 + (int) ((double) done / totalChunks * 55));
                    } finally {
                        taskSlots.release();
                    }
                    orderedResults[chunkIdx] = result;
                }, chunkReviewExecutor);
                chunkFutures.add(fut);
            }
            CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0])).join();

            if (cancelledTasks.remove(taskId)) {
                log.info("Review task {} cancelled during processing", taskId);
                return;
            }

            // 按原章节顺序收集结果；被取消而未执行的切片位置为 null，跳过
            List<Map<String, Object>> chunkResults = new ArrayList<>(totalChunks);
            for (Map<String, Object> r : orderedResults) {
                if (r != null) chunkResults.add(r);
            }

            // 5.5 Document-level pass (only if there are document_specific rules to apply)
            List<RuleDispatcher.PreparedRule> docRules = RuleDispatcher.documentLevelRules(preparedRules);
            if (!docRules.isEmpty()) {
                webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                        "正在进行文档级综合审查 (" + docRules.size() + " 条)...", 92);
                String docSystemPrompt = buildPromptForRules(docRules);
                String docUserContent = buildDocumentLevelInput(chapters, chunkResults);
                try {
                    AiCallOptions docOptions = buildConvergenceOptions(modelConfig,
                            stableSeed(taskId, /*chunkIdx*/ -1, /*sampleIdx*/ 0));
                    String docResponse = callWithRetry(modelConfig, docSystemPrompt, docUserContent, docOptions);
                    Map<String, Object> docParsed = tryParseAiJson(docResponse);
                    if (docParsed == null) {
                        docParsed = buildFallbackResult("全文综合审查", docResponse);
                    }
                    Map<String, Object> docChunk = new HashMap<>();
                    docChunk.put("chunk", chunkResults.size() + 1);
                    docChunk.put("chapterTitle", "全文综合审查（文档级规则）");
                    docChunk.put("totalChunks", chunks.size() + 1);
                    docChunk.put("estimatedTokens", ChunkUtils.estimateTokens(docUserContent));
                    docChunk.put("appliedRules", docRules.stream()
                            .map(r -> r.getRule().getRuleName()).toList());
                    docChunk.put("result", docParsed);
                    chunkResults.add(docChunk);
                } catch (Exception docEx) {
                    log.warn("Document-level review pass failed: {}", docEx.getMessage());
                }
            }

            // 6. Aggregate results
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "正在汇总审查结果...", 95);
            Map<String, Object> aggregatedResult = aggregateResults(chunkResults);
            // 在结果里冻结模型的可对比性，避免历史任务在跨模型对比 UI 里被误算进来
            boolean crossModelEligible = !ThinkingModeDetector.isThinking(modelConfig);
            aggregatedResult.put("crossModelEligible", crossModelEligible);
            // 逐章节方案每章节单次调用，统一标记为 single 采样
            aggregatedResult.put("samplingStrategy", "single");
            aggregatedResult.put("modelName", modelConfig.getModelName());
            aggregatedResult.put("modelKey", modelConfig.getModelKey());
            aggregatedResult.put("originalSources", buildOriginalSources(chapters, chunks));
            aggregatedResult.put("sourceTextMode", "structured_json_markdown_review_html_display");

            // 6.5 Persist the aggregated AI result to ./output/审查结果_<file>_<model>_<ts>.json
            saveAiResultToFile(taskId, task.getFileName(), task.getSelectedModel(), runStamp,
                    aggregatedResult);

            // 7. Save result and mark as completed
            task.setAiResult(aggregatedResult);
            updateTaskStatus(task, ReviewTask.STATUS_COMPLETED, null);
            int failedChunkCount = ((Number) aggregatedResult.getOrDefault("failedChunkCount", 0)).intValue();
            webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_COMPLETED,
                    failedChunkCount > 0
                            ? "审查完成，共审查 " + chunks.size() + " 个切片，其中 " + failedChunkCount + " 个切片失败，可点击重新审查失败切片"
                            : "审查完成，共审查 " + chunks.size() + " 个章节片段");

            log.info("Review task completed: {}", taskId);

        } catch (Exception e) {
            log.error("Review task failed: {}", taskId, e);
            updateTaskStatus(task, ReviewTask.STATUS_FAILED, e.getMessage());
            webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_FAILED,
                    "审查失败: " + e.getMessage());
        }
    }

    /**
     * Save complete chapter/chunk information to
     * ./output/切片结果_<fileBase>_<model>_<runStamp>.json on the host
     * (via the bind-mounted /app/output directory). A separate file is produced
     * for every review run so historic slicing details are preserved.
     *
     * @param dispatchTraces  per-chunk record of which rules were applied and why; one entry
     *                        per chunk, aligned with {@code chunks} by chunk number.
     */
    private void saveChunkDebugInfo(String taskId, String fileName, String model, String runStamp,
                                     List<WordParser.Chapter> chapters,
                                     List<ChunkUtils.ChunkResult> chunks,
                                     List<Map<String, Object>> dispatchTraces) {
        try {
            Map<String, Object> debug = new LinkedHashMap<>();
            debug.put("taskId", taskId);
            debug.put("fileName", fileName);
            debug.put("model", model);
            debug.put("timestamp", LocalDateTime.now().toString());
            debug.put("chapterCount", chapters.size());
            debug.put("chunkCount", chunks.size());

            // Chapter list with FULL content (no truncation)
            List<Map<String, Object>> chapterList = new ArrayList<>();
            for (int i = 0; i < chapters.size(); i++) {
                WordParser.Chapter ch = chapters.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i + 1);
                m.put("title", ch.getTitle().isBlank() ? "(前言/无标题)" : ch.getTitle());
                m.put("estimatedTokens", ChunkUtils.estimateTokens(ch.getFullText()));
                // 精简调试导出：content/plainText/html/contentLength 均与 reviewMarkdown 或
                // nodes 重复，故只保留送审文本 reviewMarkdown + 结构化 nodes（节点再去掉
                // 与 text 重复的 markdown 及派生的 nodeIndex/headingLevel）。
                m.put("reviewMarkdown", ch.getContent());
                m.put("nodes", slimDebugNodes(DocumentSourceMapper.toStructuredNodes(ch.getNodes())));
                chapterList.add(m);
            }
            debug.put("chapters", chapterList);

            // Chunk list with FULL content (no truncation), plus dispatch trace
            List<Map<String, Object>> chunkList = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkUtils.ChunkResult chunk = chunks.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i + 1);
                m.put("label", chunk.getLabel());
                m.put("estimatedTokens", chunk.getEstimatedTokens());
                m.put("reviewMarkdown", chunk.getContent());
                m.put("source", DocumentSourceMapper.toChunkSource(
                        chunk, i + 1, "debug_chunk"));
                if (dispatchTraces != null && i < dispatchTraces.size()) {
                    Map<String, Object> trace = dispatchTraces.get(i);
                    m.put("appliedRules", trace.get("appliedRules"));
                    m.put("dispatchTrace", trace.get("matchTraces"));
                }
                chunkList.add(m);
            }
            debug.put("chunks", chunkList);

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(debug);

            Path outputDir = Path.of("/app/output");
            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve(buildOutputFileName("切片结果", fileName, model, runStamp));
            Files.writeString(outputFile, json);
            log.info("切片结果 saved to output dir: {}", outputFile.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to save chunk debug info: {}", e.getMessage());
        }
    }

    private Set<Integer> extractFailedChunkNumbers(Map<String, Object> aiResult) {
        Set<Integer> failed = new TreeSet<>();
        if (aiResult == null) return failed;

        Object failedChunksObj = aiResult.get("failedChunks");
        if (failedChunksObj instanceof List<?> failedChunks) {
            for (Object item : failedChunks) {
                if (item instanceof Map<?, ?> map) {
                    Integer chunk = toInteger(map.get("chunk"));
                    if (chunk != null) failed.add(chunk);
                }
            }
        }

        Object chunkResultsObj = aiResult.get("chunkResults");
        if (chunkResultsObj instanceof List<?> chunkResults) {
            for (Object item : chunkResults) {
                if (item instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("failed"))) {
                    Integer chunk = toInteger(map.get("chunk"));
                    if (chunk != null) failed.add(chunk);
                }
            }
        }
        return failed;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> copyChunkResults(Map<String, Object> aiResult) {
        List<Map<String, Object>> copied = new ArrayList<>();
        if (aiResult == null) return copied;
        Object chunkResultsObj = aiResult.get("chunkResults");
        if (chunkResultsObj instanceof List<?> chunkResults) {
            for (Object item : chunkResults) {
                if (item instanceof Map<?, ?> map) {
                    copied.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
        }
        return copied;
    }

    private void replaceChunkResult(List<Map<String, Object>> chunkResults, Map<String, Object> replacement) {
        Integer replacementChunk = toInteger(replacement.get("chunk"));
        if (replacementChunk == null) {
            chunkResults.add(replacement);
            return;
        }
        for (int i = 0; i < chunkResults.size(); i++) {
            Integer existingChunk = toInteger(chunkResults.get(i).get("chunk"));
            if (replacementChunk.equals(existingChunk)) {
                chunkResults.set(i, replacement);
                return;
            }
        }
        chunkResults.add(replacement);
        chunkResults.sort(Comparator.comparingInt(m -> Optional.ofNullable(toInteger(m.get("chunk"))).orElse(Integer.MAX_VALUE)));
    }

    private Integer toInteger(Object raw) {
        if (raw instanceof Number number) return number.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Persist the AI-aggregated review result to
     * ./output/审查结果_<fileBase>_<model>_<runStamp>.json on the host.
     */
    private void saveAiResultToFile(String taskId, String fileName, String model, String runStamp,
                                     Map<String, Object> aggregatedResult) {
        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("taskId", taskId);
            wrapper.put("fileName", fileName);
            wrapper.put("model", model);
            wrapper.put("timestamp", LocalDateTime.now().toString());
            wrapper.put("result", aggregatedResult);

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper);

            Path outputDir = Path.of("/app/output");
            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve(buildOutputFileName("审查结果", fileName, model, runStamp));
            Files.writeString(outputFile, json);
            log.info("审查结果 saved to: {}", outputFile.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to save 审查结果: {}", e.getMessage());
        }
    }

    /**
     * Compose an output file name: {@code <prefix>_<fileBase>_<model>_<runStamp>.json}.
     * The original document's extension is stripped, and any filesystem-unsafe
     * characters in the file base or model name are replaced with underscores.
     */
    private static String buildOutputFileName(String prefix, String fileName, String model, String runStamp) {
        String base = fileName == null ? "document" : fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = sanitizeForFileName(base);
        String safeModel = sanitizeForFileName(model == null || model.isBlank() ? "model" : model);
        return prefix + "_" + base + "_" + safeModel + "_" + runStamp + ".json";
    }

    private static String sanitizeForFileName(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
    }

    /**
     * 构造单切片系统提示词。流程：
     * <ol>
     *   <li>把分发器命中的规则转成 {@link RuleParser.RuleEntry}；缺 rule_code 的自动生成 R-AUTO-NNN，
     *       保证模型可以稳定引用。</li>
     *   <li>估算 prompt 长度，若超过 {@link #RULE_BUDGET_TOKENS} 则按"全局规则优先 + rule_code 升序"
     *       截断，防止超长 prompt 让模型把后段规则当作背景噪声。</li>
     *   <li>调用 {@link RuleParser#buildStructuredSystemPrompt}，按四段式结构（ROLE / Schema /
     *       Few-shot / 规则清单）生成最终 system prompt。</li>
     * </ol>
     */
    private String buildPromptForRules(List<RuleDispatcher.PreparedRule> rulesForChunk) {
        return RuleParser.buildStructuredSystemPrompt(buildRuleEntriesForChunk(rulesForChunk));
    }

    /**
     * Build the ordered rule-entry list injected into a chunk's prompt: the editable
     * built-in quality rule first, then the dispatched rules (after token-budget trim).
     */
    private List<RuleParser.RuleEntry> buildRuleEntriesForChunk(List<RuleDispatcher.PreparedRule> rulesForChunk) {
        return buildRuleEntriesForChunk(rulesForChunk, true);
    }

    /**
     * @param qualityCheck 是否注入内置「全文质量检查（基础文字质量审查）」。关闭时：仅装配命中的业务规则；
     *                     若该章节无任何业务规则，返回空列表（调用方据此跳过该章节，不再调用模型）。
     */
    private List<RuleParser.RuleEntry> buildRuleEntriesForChunk(List<RuleDispatcher.PreparedRule> rulesForChunk,
                                                                boolean qualityCheck) {
        if (rulesForChunk == null || rulesForChunk.isEmpty()) {
            return qualityCheck ? List.of(builtInQualityRule()) : List.of();
        }

        // 转 RuleEntry + 缺失编号自动补齐
        List<RuleParser.RuleEntry> entries = new ArrayList<>();
        int autoSeq = 1;
        for (RuleDispatcher.PreparedRule pr : rulesForChunk) {
            String code = pr.getMetadata() != null ? pr.getMetadata().getRuleCode() : null;
            // Skip the editable built-in rule if it was dispatched through a scenario/library:
            // it is always prepended separately below, so including it here would duplicate it.
            if (RuleDispatcher.BASIC_QUALITY_RULE_CODE.equals(code)) {
                continue;
            }
            if (code == null || code.isBlank()) {
                code = "R-AUTO-" + String.format("%03d", autoSeq++);
            }
            entries.add(new RuleParser.RuleEntry(
                    code,
                    pr.getRule().getRuleName(),
                    pr.getBody(),
                    toCheckEntries(code, pr.getChecks())));
        }

        // 按规则部分 token 上限截断；保留 global，丢失 section_specific 的尾部
        List<RuleParser.RuleEntry> kept = applyRuleBudget(entries, rulesForChunk);
        if (kept.size() != entries.size()) {
            log.info("Rule budget triggered: chunk rules trimmed from {} → {} (cap={} tokens)",
                    entries.size(), kept.size(), RULE_BUDGET_TOKENS);
        }
        if (qualityCheck) kept.add(0, builtInQualityRule());
        return kept;
    }

    private void attachChecks(List<RuleDispatcher.PreparedRule> preparedRules) {
        if (preparedRules == null || preparedRules.isEmpty()) return;
        List<Long> ruleIds = preparedRules.stream()
                .map(pr -> pr.getRule() != null ? pr.getRule().getId() : null)
                .filter(Objects::nonNull)
                .toList();
        if (ruleIds.isEmpty()) return;
        List<RuleCheck> checks = ruleCheckMapper.findActiveByRuleIds(ruleIds);
        Map<Long, List<RuleCheck>> byRuleId = new LinkedHashMap<>();
        for (RuleCheck check : checks) {
            if (check.getRuleId() == null) continue;
            byRuleId.computeIfAbsent(check.getRuleId(), k -> new ArrayList<>()).add(check);
        }
        for (RuleDispatcher.PreparedRule pr : preparedRules) {
            Long ruleId = pr.getRule() != null ? pr.getRule().getId() : null;
            pr.setChecks(ruleId == null ? List.of() : byRuleId.getOrDefault(ruleId, List.of()));
        }
    }

    private List<RuleParser.CheckEntry> toCheckEntries(String ruleCode, List<RuleCheck> checks) {
        if (checks == null || checks.isEmpty()) return List.of();
        List<RuleParser.CheckEntry> entries = new ArrayList<>();
        int autoSeq = 1;
        for (RuleCheck check : checks) {
            String checkCode = check.getCheckCode();
            if (checkCode == null || checkCode.isBlank()) {
                checkCode = ruleCode + "-C" + String.format("%03d", autoSeq++);
            }
            entries.add(new RuleParser.CheckEntry(
                    checkCode,
                    check.getQuestion(),
                    check.getPassCriteria(),
                    check.getCategory(),
                    check.getEvidenceRequired()));
        }
        return entries;
    }

    /**
     * 按 token 预算裁剪规则清单。策略：
     * <ul>
     *   <li>global / output 规则全部保留（它们是跨章节质量底线）；</li>
     *   <li>剩余预算分给 section_specific 规则，按原 rule_code 顺序填，直到累计 token 超过上限。</li>
     * </ul>
     */
    private List<RuleParser.RuleEntry> applyRuleBudget(List<RuleParser.RuleEntry> entries,
                                                       List<RuleDispatcher.PreparedRule> rulesForChunk) {
        // 按 entries 顺序记录每条是否为 global，避免再排序破坏调用方对齐
        List<Boolean> isGlobalFlags = new ArrayList<>(rulesForChunk.size());
        for (RuleDispatcher.PreparedRule pr : rulesForChunk) {
            isGlobalFlags.add(pr.isGlobal());
        }

        // 先把所有 global 规则纳入
        List<RuleParser.RuleEntry> kept = new ArrayList<>();
        int budgetUsed = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (Boolean.TRUE.equals(isGlobalFlags.get(i))) {
                RuleParser.RuleEntry e = entries.get(i);
                kept.add(e);
                budgetUsed += estimateEntryTokens(e);
            }
        }
        // 然后按顺序填 section_specific 规则
        for (int i = 0; i < entries.size(); i++) {
            if (!Boolean.TRUE.equals(isGlobalFlags.get(i))) {
                RuleParser.RuleEntry e = entries.get(i);
                int cost = estimateEntryTokens(e);
                if (budgetUsed + cost > RULE_BUDGET_TOKENS) {
                    continue; // 跳过这一条，继续尝试后续可能更短的
                }
                kept.add(e);
                budgetUsed += cost;
            }
        }
        return kept;
    }

    private int estimateEntryTokens(RuleParser.RuleEntry e) {
        int tokens = 0;
        if (e.name != null) tokens += ChunkUtils.estimateTokens(e.name);
        if (e.body != null) tokens += ChunkUtils.estimateTokens(e.body);
        if (e.checks != null) {
            for (RuleParser.CheckEntry check : e.checks) {
                if (check.checkCode != null) tokens += ChunkUtils.estimateTokens(check.checkCode);
                if (check.question != null) tokens += ChunkUtils.estimateTokens(check.question);
                if (check.passCriteria != null) tokens += ChunkUtils.estimateTokens(check.passCriteria);
            }
        }
        return tokens + 8; // 编号 / 分隔符的固定开销
    }

    /**
     * 全文一级标题目录，附加到每个章节切片的审查上下文末尾。让"按章审查"时模型也能看到
     * 整篇文档有哪些一级标题章节，从而支持"本章声明的清单 vs 全文实际章节"这类跨章节核对
     * （例如：试验概述里列出的试验项目，是否都有对应的试验章节）。
     * 明确标注为"参考、勿对其本身判定问题"，避免被当作本章正文重复审查。
     */
    private static String buildChapterOutline(List<WordParser.Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "\n\n=== 全文章节目录（一级标题，仅供跨章节核对参考，勿对其本身判定问题）===\n");
        for (int i = 0; i < chapters.size(); i++) {
            String t = chapters.get(i).getTitle();
            sb.append(i + 1).append(". ").append(t == null || t.isBlank() ? "(无标题)" : t).append('\n');
        }
        return sb.toString();
    }

    /**
     * 从"试验概述"章节(一般是 7.1)动态提取本受试设备声明应完成的试验项目清单。
     * 优先取标题含"试验概述/试验项目概述"的章节正文；否则回退到任一含"(鉴定)试验项目有"的章节。
     *
     * <p>两路提取，取并集（散文常按类别分多句、且未必列全，需配合一览表）：
     * <ol>
     *   <li>散文声明：可能有多句"……试验项目有：A、B、C…等"（自然环境类 / 电磁兼容类 / …），逐句逐项收集；</li>
     *   <li>试验项目一览表：表格行里以"试验"结尾的纯中文单元格通常即试验项目名，补齐散文遗漏的项。</li>
     * </ol>
     * 解析失败/无任何项时返回空列表（此时 test_item 规则不作用于任何章节，安全降级）。
     */
    static List<String> extractDeclaredTestItems(List<WordParser.Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return List.of();
        String text = null;
        for (WordParser.Chapter ch : chapters) {
            String t = ch.getTitle() == null ? "" : ch.getTitle();
            if (t.contains("试验概述") || t.contains("试验项目概述")) { text = ch.getContent(); break; }
        }
        if (text == null) {
            for (WordParser.Chapter ch : chapters) {
                String c = ch.getContent();
                if (c != null && (c.contains("试验项目有") || c.contains("鉴定试验项目有"))) { text = c; break; }
            }
        }
        if (text == null || text.isBlank()) return List.of();

        List<String> items = new ArrayList<>();

        // ① 散文：逐句"试验项目有：…等/。/见表"，reluctant 匹配，find-all 收集每一句
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("试验项目有[：:]\\s*(.+?)(?:等|。|见表|具体)").matcher(text);
        while (m.find()) {
            for (String part : m.group(1).split("[、，,；;/]")) {
                String p = part.trim();
                if (p.length() < 2) continue;
                if (p.contains("试验项目") || p.contains("本设备") || p.contains("应完成")) continue;
                if (!items.contains(p)) items.add(p);
            }
        }

        // ② 试验项目一览表：抓表格行中以"试验"结尾的纯中文单元格（散文常列不全）
        for (String line : text.split("\\r?\\n")) {
            if (line.indexOf('|') < 0) continue;
            for (String cell : line.split("\\|")) {
                String c = cell.trim();
                if (c.length() < 3 || c.length() > 16 || !c.endsWith("试验")) continue;
                if (c.matches(".*[0-9A-Za-z：:].*")) continue;   // 排除含编号/英文/冒号的单元格
                if (c.contains("条") || c.contains("类") || c.contains("记录") || c.contains("报告")) continue;
                String core = c.replaceAll("(试验项目|试验)$", "").trim();
                if (core.length() >= 2 && !items.contains(core)) items.add(core);
            }
        }
        return items;
    }

    /** 章节标题去掉前导编号与尾部"试验/试验项目"后的核心词，用于与声明项目做宽松匹配。 */
    private static String normalizeTitleCore(String title) {
        if (title == null) return "";
        String s = title.trim()
                .replaceFirst("^第?\\s*[0-9]+(\\.[0-9]+)*\\s*[章节]?[\\s\\.、:：-]*", "")
                .replaceAll("(试验项目|试验)$", "")
                .trim();
        return s;
    }

    /**
     * 判断某章节标题是否对应试验概述声明的某个试验项目（即"试验项目章节"）。
     * 匹配层次：①标题/核心词互相包含；②声明项目核心词与标题核心词互相包含；
     * ③中文字符集重叠度兜底——处理"射频敏感性↔射频敏感度""射频发射↔射频能量发射"等近义用词差异。
     */
    static boolean isTestItemChapter(String chapterTitle, List<String> declaredTestItems) {
        if (chapterTitle == null || declaredTestItems == null || declaredTestItems.isEmpty()) return false;
        String title = chapterTitle.trim();
        String core = normalizeTitleCore(title);
        if (core.isEmpty()) core = title;
        for (String item : declaredTestItems) {
            String it = item == null ? "" : item.trim();
            if (it.length() < 2) continue;
            String itCore = normalizeTitleCore(it);
            if (itCore.isEmpty()) itCore = it;
            if (title.contains(it) || it.contains(core) || core.contains(it)) return true;
            if (core.contains(itCore) || itCore.contains(core)) return true;
            if (cjkOverlapMatch(itCore, core)) return true;
        }
        return false;
    }

    /**
     * 中文字符集重叠度匹配：共享字符≥2 且 ≥较短串去重字数的 80%，视为同一试验项目。
     * 80% 阈值能容忍"射频敏感性↔射频敏感度""射频发射↔射频能量发射""雷电↔闪电"等近义用词，
     * 又能排除"鸟撞冲击↔工作冲击和坠撞安全""防水性↔防爆性"这类仅个别字巧合的误配。
     */
    private static boolean cjkOverlapMatch(String a, String b) {
        java.util.Set<Character> sa = cjkCharSet(a);
        java.util.Set<Character> sb = cjkCharSet(b);
        if (sa.size() < 2 || sb.size() < 2) return false;
        int shared = 0;
        for (Character c : sa) if (sb.contains(c)) shared++;
        if (shared < 2) return false;
        int minLen = Math.min(sa.size(), sb.size());
        return shared >= Math.max(2, (int) Math.ceil(minLen * 0.8));
    }

    private static java.util.Set<Character> cjkCharSet(String s) {
        java.util.Set<Character> set = new java.util.LinkedHashSet<>();
        if (s == null) return set;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fa5) set.add(c);
        }
        return set;
    }

    /**
     * Compose the user message for the document-level review pass: chapter outline plus
     * each per-chunk summary already returned by the model.
     */
    private String buildDocumentLevelInput(List<WordParser.Chapter> chapters,
                                           List<Map<String, Object>> chunkResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("【文档章节目录】\n");
        for (int i = 0; i < chapters.size(); i++) {
            WordParser.Chapter ch = chapters.get(i);
            String title = ch.getTitle() == null || ch.getTitle().isBlank()
                    ? "(前言/无标题)" : ch.getTitle();
            sb.append(i + 1).append(". ").append(title).append("\n");
        }
        sb.append("\n【各章节审查摘要】\n");
        for (Map<String, Object> chunk : chunkResults) {
            String title = String.valueOf(chunk.getOrDefault("chapterTitle", ""));
            sb.append("- ").append(title).append("：");
            Object result = chunk.get("result");
            if (result instanceof Map<?, ?> resMap) {
                Object summary = resMap.get("summary");
                sb.append(summary == null ? "(无摘要)" : summary.toString());
                Object issues = resMap.get("issues");
                if (issues instanceof List<?> issueList && !issueList.isEmpty()) {
                    sb.append(" / 已发现问题 ").append(issueList.size()).append(" 条");
                }
            }
            sb.append("\n");
        }
        sb.append("\n请基于全文目录与各章节摘要，按文档级规则给出综合判定。");
        return sb.toString();
    }

    /**
     * 单个切片的完整审查流程：组装系统提示词、附加跨章节引用上下文、调用 AI、解析响应。
     *
     * <p>本方法被并行调度执行，必须线程安全：
     * <ul>
     *   <li>{@link #buildPromptForRules}、{@link ChapterReferenceResolver} 都是纯函数；</li>
     *   <li>{@link AiModelService#callAiModel} 使用同一个 {@link java.net.http.HttpClient}
     *       —— JDK HttpClient 文档明确说明线程安全；</li>
     *   <li>{@link #tryParseAiJson} 使用 {@code ObjectMapper}，后者文档说明 read 操作是线程安全的；</li>
     *   <li>返回的 Map 是本方法内新建的，调用方拿到独立实例。</li>
     * </ul>
     */
    /** 调试切片导出用：剔除与 text 重复的 markdown，以及派生的 nodeIndex/headingLevel，缩小体积。 */
    private static List<Map<String, Object>> slimDebugNodes(List<Map<String, Object>> nodes) {
        if (nodes == null) return null;
        for (Map<String, Object> n : nodes) {
            n.remove("markdown");
            n.remove("nodeIndex");
            n.remove("headingLevel");
        }
        return nodes;
    }

    private Map<String, Object> reviewSingleChunk(int chunkIdx, int totalChunks,
                                                   ChunkUtils.ChunkResult chunk,
                                                   RuleDispatcher.DispatchResult dispatch,
                                                   List<WordParser.Chapter> chapters,
                                                   AiModelConfig modelConfig,
                                                   boolean qualityCheck) throws Exception {
        return reviewSingleChunk(chunkIdx, totalChunks, chunk, dispatch, chapters, modelConfig, /*taskId*/ null, qualityCheck);
    }

    /**
     * 单切片审查：装配 prompt + 跨章节引用上下文，每章节单次调用大模型，
     * 把结果整理为 chunkResult。
     *
     * <p>逐章节方案每章节只调用一次（{@code sampleCount=1}）：调用量与章节数 1:1，
     * 避免双采样在限流(429)场景下翻倍打满 provider 配额。{@link #mergeSamples} 在单采样
     * 下会把所有 issue 标记 confidence=single，下游聚合再据跨章节复现情况提升置信度。
     */
    private Map<String, Object> reviewSingleChunk(int chunkIdx, int totalChunks,
                                                   ChunkUtils.ChunkResult chunk,
                                                   RuleDispatcher.DispatchResult dispatch,
                                                   List<WordParser.Chapter> chapters,
                                                   AiModelConfig modelConfig,
                                                   String taskId,
                                                   boolean qualityCheck) throws Exception {
        int chunkNum = chunkIdx + 1;
        List<RuleParser.RuleEntry> ruleEntries = buildRuleEntriesForChunk(dispatch.getAppliedRules(), qualityCheck);
        String systemPrompt = RuleParser.buildStructuredSystemPrompt(ruleEntries);
        // 1a: floor check_results at the number of injected rules/checks so weak models
        // can't drop them; paired with the prompt's coverage anchoring (same count).
        int minChecks = RuleParser.expectedCheckCount(ruleEntries);

        Set<Integer> refIdx = ChapterReferenceResolver
                .findReferencedChapters(chunk.getContent(), chunk.getLabel(), chapters);
        String supporting = ChapterReferenceResolver.renderSupportingContext(refIdx, chapters);

        String chunkContent = "章节: " + chunk.getLabel()
                + " (" + chunkNum + "/" + totalChunks + ")\n\n" + chunk.getContent()
                + supporting
                + buildChapterOutline(chapters);

        int sampleCount = 1;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int s = 0; s < sampleCount; s++) {
            Map<String, Object> parsed = null;
            String aiResponse = null;
            // JSON 解析失败重试：弱模型（如 Qwen3.6）对长/多规则章节常返回非法或被截断的 JSON。
            // 解析在 HTTP 重试之后，原先一旦失败直接降级为“原始文本”，导致整章 R-Q 检查项被补成
            // “模型未返回→待复核”。这里在解析失败时换种子重试（temperature=0 须换种子才能改变输出），
            // 多数能拿到合法 JSON，显著减少无定位的待复核噪声。
            for (int attempt = 1; attempt <= JSON_PARSE_MAX_ATTEMPTS && parsed == null; attempt++) {
                long seed = stableSeed(taskId, chunkIdx, s) + (attempt - 1);
                AiCallOptions options = buildConvergenceOptions(modelConfig, seed, minChecks);
                aiResponse = callWithRetry(modelConfig, systemPrompt, chunkContent, options);
                parsed = tryParseAiJson(aiResponse);
                if (parsed == null) {
                    log.warn("Chunk {} sample {} JSON 解析失败（第 {}/{} 次），响应长度={}",
                            chunkNum, s + 1, attempt, JSON_PARSE_MAX_ATTEMPTS,
                            aiResponse != null ? aiResponse.length() : 0);
                }
            }
            if (parsed == null) {
                log.warn("Chunk {} sample {} 多次解析仍失败，降级为原始文本 issue。", chunkNum, s + 1);
                parsed = buildFallbackResult(chunk.getLabel(), aiResponse);
            }
            samples.add(parsed);
        }
        Map<String, Object> merged = mergeSamples(samples, chunk.getLabel());
        if (qualityCheck) enrichBuiltInQualityChecks(merged, chunkHasFiguresOrTables(chunk.getContent()));
        enrichResultSourceRefs(merged, chunkIdx, chunk, refIdx);

        Map<String, Object> chunkResult = new HashMap<>();
        chunkResult.put("chunk", chunkNum);
        chunkResult.put("chapterTitle", chunk.getLabel());
        chunkResult.put("totalChunks", totalChunks);
        chunkResult.put("estimatedTokens", chunk.getEstimatedTokens());
        chunkResult.put("source", buildChunkSource(chunkIdx, chunk));
        chunkResult.put("sourceRefs", buildChunkSourceRefs(chunkIdx, chunk, refIdx));
        List<String> appliedRuleNames = new ArrayList<>();
        if (qualityCheck) appliedRuleNames.add(RuleDispatcher.BASIC_QUALITY_RULE_NAME);
        for (String ruleName : dispatch.getAppliedRuleNames()) {
            if (!RuleDispatcher.BASIC_QUALITY_RULE_NAME.equals(ruleName)) {
                appliedRuleNames.add(ruleName);
            }
        }
        chunkResult.put("appliedRules", appliedRuleNames);
        chunkResult.put("reviewProfile", dispatch.getAppliedRules().isEmpty()
                ? (qualityCheck ? "basic_quality" : "skipped") : "rule_based");
        chunkResult.put("samplingStrategy", sampleCount > 1 ? "double" : "single");
        chunkResult.put("sampleCount", sampleCount);
        chunkResult.put("result", merged);
        return chunkResult;
    }

    /**
     * 关闭全文质量检查、且某章节未命中任何业务规则时的占位结果：不调用模型，仅记录"已跳过"，
     * 保证章节计数/结果列表完整。
     */
    private Map<String, Object> buildSkippedChunkResult(int chunkIdx, int totalChunks,
                                                        ChunkUtils.ChunkResult chunk) {
        Map<String, Object> result = new HashMap<>();
        result.put("summary", "本章节未命中任何业务规则，且全文质量检查已关闭，已跳过审查。");
        result.put("issues", new ArrayList<>());
        result.put("passed_items", new ArrayList<>());
        result.put("check_results", new ArrayList<>());

        Map<String, Object> chunkResult = new HashMap<>();
        chunkResult.put("chunk", chunkIdx + 1);
        chunkResult.put("chapterTitle", chunk.getLabel());
        chunkResult.put("totalChunks", totalChunks);
        chunkResult.put("estimatedTokens", chunk.getEstimatedTokens());
        chunkResult.put("source", buildChunkSource(chunkIdx, chunk));
        chunkResult.put("sourceRefs", buildChunkSourceRefs(chunkIdx, chunk, java.util.Set.of()));
        chunkResult.put("appliedRules", new ArrayList<>());
        chunkResult.put("reviewProfile", "skipped");
        chunkResult.put("samplingStrategy", "single");
        chunkResult.put("sampleCount", 0);
        chunkResult.put("result", result);
        return chunkResult;
    }

    /** Default preface used when the editable rule row is missing or has blank content. */
    private static final String BASIC_QUALITY_DEFAULT_PREFACE = """
            仅审查当前章节的文字表达质量，不审查工程字段完整性、试验项目完整性、
            设备证书、试验条件、试验程序或标准符合性。
            章节内容简短或只有一行不是问题，不得因篇幅短判定不通过。
            错别字/标点、语句通顺、术语一致等文字检查项对当前章节始终适用，不得跳过或判不适用，
            仅就当前章节文字本身审查，不臆造问题。
            """;

    /**
     * Built-in 基础文字质量审查 rule injected into every chapter prompt. Its preface and
     * checks are sourced from the editable DB rule (rule_code = R-BASIC-QUALITY) so users
     * can tune them from the UI; if that row is absent (fresh DB, failed seed) we fall back
     * to {@link #BASIC_QUALITY_DEFAULT_PREFACE} / {@link #defaultBasicQualityChecks()} so the
     * always-on review never disappears. Code-side enforcement (禁止 N/A、漏项补齐、不受预算
     * 裁剪) is unchanged and keyed on {@link RuleDispatcher#BASIC_QUALITY_RULE_CODE}.
     */
    private RuleParser.RuleEntry builtInQualityRule() {
        Rule dbRule = loadEditableRule(RuleDispatcher.BASIC_QUALITY_RULE_CODE);
        String name = RuleDispatcher.BASIC_QUALITY_RULE_NAME;
        String preface = BASIC_QUALITY_DEFAULT_PREFACE;
        if (dbRule != null) {
            if (dbRule.getRuleName() != null && !dbRule.getRuleName().isBlank()) {
                name = dbRule.getRuleName().trim();
            }
            String body = RuleMetadata.stripFrontmatter(
                    Objects.toString(dbRule.getContent(), ""), dbRule.getFileType());
            if (body != null && !body.isBlank()) {
                preface = body.trim();
            }
        }
        return new RuleParser.RuleEntry(
                RuleDispatcher.BASIC_QUALITY_RULE_CODE, name, preface, basicQualityChecks());
    }

    /**
     * The three (or user-edited) basic-quality checks. Loaded from the editable DB rule's
     * {@code rule_checks}; falls back to {@link #defaultBasicQualityChecks()} when the rule
     * row or its active checks are missing. Drives both the prompt and the N/A / missing-item
     * enforcement in {@link #enrichBuiltInQualityChecks(Map)}.
     */
    private List<RuleParser.CheckEntry> basicQualityChecks() {
        Rule dbRule = loadEditableRule(RuleDispatcher.BASIC_QUALITY_RULE_CODE);
        if (dbRule != null && dbRule.getId() != null) {
            try {
                List<RuleCheck> dbChecks = ruleCheckMapper.findActiveByRuleId(dbRule.getId());
                if (dbChecks != null && !dbChecks.isEmpty()) {
                    return toCheckEntries(RuleDispatcher.BASIC_QUALITY_RULE_CODE, dbChecks);
                }
            } catch (Exception e) {
                log.warn("加载可编辑基础文字质量检查项失败，回退内置默认：{}", e.getMessage());
            }
        }
        return defaultBasicQualityChecks();
    }

    /** Load an editable built-in rule row by rule_code; null when absent or on error. */
    private Rule loadEditableRule(String ruleCode) {
        try {
            Long id = ruleMapper.findIdByRuleCode(ruleCode);
            return id == null ? null : ruleMapper.selectById(id);
        } catch (Exception e) {
            log.warn("加载可编辑内置规则 {} 失败，回退内置默认：{}", ruleCode, e.getMessage());
            return null;
        }
    }

    private List<RuleParser.CheckEntry> defaultBasicQualityChecks() {
        return List.of(
                new RuleParser.CheckEntry(
                        RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C001",
                        "是否存在错别字、漏字、多字、重复词或明显标点错误",
                        "未发现错别字、漏字、多字、重复词或明显标点错误",
                        "其他",
                        true),
                new RuleParser.CheckEntry(
                        RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C002",
                        "语句是否通顺，是否存在语序不当、语病或明显歧义",
                        "语句通顺、语义明确，不存在语序不当、语病或明显歧义",
                        "逻辑一致性",
                        true),
                new RuleParser.CheckEntry(
                        RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C003",
                        "本章节内术语、名称和称谓是否一致",
                        "本章节内相同对象的术语、名称和称谓保持一致",
                        "术语一致性",
                        true),
                new RuleParser.CheckEntry(
                        RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C004",
                        "图号、表号是否全文唯一不重复、编号格式统一？",
                        "编号唯一且格式统一即 Pass；存在重复编号或格式不统一则 Fail。",
                        "格式",
                        true),
                new RuleParser.CheckEntry(
                        RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C005",
                        "正文引用的图号/表号是否真实存在，且引用编号与图表实际编号一致、表述统一（无图1/图一混用）？",
                        "引用均真实且编号表述一致即 Pass；引用不存在的编号或表述混乱则 Fail。",
                        "逻辑一致性",
                        true),
                new RuleParser.CheckEntry(
                        RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C006",
                        "图表编号是否按出现顺序递增、不跳号、不倒序？",
                        "顺序合理即 Pass；跳号或倒序则 Fail。",
                        "逻辑一致性",
                        true),
                new RuleParser.CheckEntry(
                        RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C007",
                        "是否所有图表均被正文引用（无未被引用的图表）？",
                        "图表均被引用即 Pass；存在未被引用的图表则 Fail。",
                        "完整性",
                        true));
    }

    @SuppressWarnings("unchecked")
    private void enrichBuiltInQualityChecks(Map<String, Object> result, boolean hasFigures) {
        List<Map<String, Object>> checks = new ArrayList<>();
        if (result.get("check_results") instanceof List<?> rawChecks) {
            for (Object item : rawChecks) {
                if (item instanceof Map<?, ?> raw) {
                    checks.add((Map<String, Object>) raw);
                }
            }
        }

        Map<String, RuleParser.CheckEntry> metadata = new LinkedHashMap<>();
        for (RuleParser.CheckEntry check : basicQualityChecks()) {
            metadata.put(check.checkCode, check);
        }

        Map<String, Map<String, Object>> checksByCode = new LinkedHashMap<>();
        for (Map<String, Object> check : checks) {
            String checkCode = Objects.toString(check.get("check_code"), "");
            if (!checkCode.isBlank()) {
                checksByCode.putIfAbsent(checkCode, check);
            }
            RuleParser.CheckEntry entry = metadata.get(checkCode);
            if (entry == null) continue;
            enrichBuiltInQualityCheck(check, entry, hasFigures);
        }

        for (RuleParser.CheckEntry entry : metadata.values()) {
            if (checksByCode.containsKey(entry.checkCode)) continue;
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("check_code", entry.checkCode);
            missing.put("rule_code", RuleDispatcher.BASIC_QUALITY_RULE_CODE);
            missing.put("check_question", entry.question);
            missing.put("status", "Review");
            missing.put("reason", "模型未返回该基础文字质量检查项，已转为待复核。");
            missing.put("evidence", "");
            missing.put("missing_items", new ArrayList<>());
            missing.put("suggestion", "");
            missing.put("confidence", "needs_review");
            enrichBuiltInQualityCheck(missing, entry, hasFigures);
            checks.add(missing);
        }
        result.put("check_results", checks);
    }

    private void enrichBuiltInQualityCheck(Map<String, Object> check,
                                           RuleParser.CheckEntry entry,
                                           boolean hasFigures) {
        check.put("ruleName", RuleDispatcher.BASIC_QUALITY_RULE_NAME);
        check.put("ruleDescription", "所有章节均执行基础文字质量检查；基础章节不执行具体业务规则。");
        check.put("passCriteria", entry.passCriteria);
        check.putIfAbsent("rule_code", RuleDispatcher.BASIC_QUALITY_RULE_CODE);
        check.putIfAbsent("check_question", entry.question);
        // 图表类检查项（图号/表号唯一性、引用、顺序、完整性）在“本章无图表”时空转：
        // 直接判通过（本章无图表即无图表一致性问题），不再转待复核，避免大量无定位噪声。
        if (isFigureCheck(entry) && !hasFigures) {
            check.put("status", "Pass");
            check.put("confidence", "high");
            check.put("reason", "本章无图号、表号，图表一致性检查不适用，自动判通过。");
            check.put("evidence", "");
            check.put("missing_items", new ArrayList<>());
            check.put("suggestion", "");
            return;
        }
        if ("N/A".equals(normalizeCheckStatus(check.get("status")))) {
            check.put("status", "Review");
            check.put("confidence", "needs_review");
            check.put("reason", "基础文字质量检查始终适用，模型返回“不适用”，已转为待复核。");
        }
    }

    /** 图表类检查项识别：问题文本含“图”或“表号”即视为图号/表号相关（R-Q 的 C004~C007）。 */
    private static boolean isFigureCheck(RuleParser.CheckEntry entry) {
        String q = (entry == null || entry.question == null) ? "" : entry.question;
        return q.contains("图") || q.contains("表号");
    }

    /** 本章是否含图表：图片占位、Markdown 表格分隔行、或“图N/表N”题注或引用。 */
    private static boolean chunkHasFiguresOrTables(String content) {
        if (content == null || content.isBlank()) return false;
        if (content.contains("[图片")) return true;
        if (content.contains("| ---") || content.contains("|---")) return true;
        return java.util.regex.Pattern.compile("[图表]\\s*\\d").matcher(content).find();
    }

    /**
     * 构造收敛性 AI 调用参数：
     * <ul>
     *   <li>非思维模型：temperature=0、top_p=1、seed=stable、结构化输出={@link ReviewResultSchema}；</li>
     *   <li>思维模型：temperature 由 server 强制，仍传 seed（不支持的 provider 会忽略）和结构化 schema。</li>
     * </ul>
     */
    private AiCallOptions buildConvergenceOptions(AiModelConfig modelConfig, long seed) {
        return buildConvergenceOptions(modelConfig, seed, 0);
    }

    /**
     * Build convergence options. When {@code minChecks > 0}, the structured schema sets a
     * {@code minItems} floor on check_results (1a coverage enforcement); otherwise the plain
     * schema is used.
     */
    private AiCallOptions buildConvergenceOptions(AiModelConfig modelConfig, long seed, int minChecks) {
        boolean thinking = ThinkingModeDetector.isThinking(modelConfig);
        com.alibaba.fastjson2.JSONObject schema = minChecks > 0
                ? ReviewResultSchema.schemaWithMinChecks(minChecks)
                : ReviewResultSchema.schema();
        AiCallOptions.AiCallOptionsBuilder b = AiCallOptions.builder()
                .seed(seed)
                .maxTokensOverride(thinking ? null : CONVERGENCE_MAX_TOKENS)
                .structuredSchema(com.alibaba.fastjson2.JSON.parseObject(
                        com.alibaba.fastjson2.JSON.toJSONString(schema)))
                .structuredSchemaName(ReviewResultSchema.SCHEMA_NAME);
        if (!thinking) {
            b.temperature(CONVERGENCE_TEMPERATURE).topP(CONVERGENCE_TOP_P);
        }
        return b.build();
    }

    /** taskId+chunkIdx+sampleIdx → 稳定 long seed（同任务复现，跨任务隔离）。 */
    private long stableSeed(String taskId, int chunkIdx, int sampleIdx) {
        String src = (taskId == null ? "task" : taskId) + ":" + chunkIdx + ":" + sampleIdx;
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest(src.getBytes(StandardCharsets.UTF_8));
            long v = 0L;
            for (int i = 0; i < 8; i++) v = (v << 8) | (sha[i] & 0xFFL);
            // 大模型 API 多用正整数范围；用绝对值避免某些 provider 拒绝负值。
            return Math.abs(v == Long.MIN_VALUE ? 0L : v);
        } catch (Exception e) {
            // 回退：简单哈希
            return Math.abs((long) src.hashCode());
        }
    }

    /**
     * 把多次采样的结果按 issue fingerprint 合并：
     * <ul>
     *   <li>fingerprint = sha1(归一化location + rule_code)；</li>
     *   <li>所有采样都出现的 issue → confidence=high；</li>
     *   <li>仅部分采样出现 → confidence=needs_review；</li>
     *   <li>passed_items 取所有采样的并集；summary 取首次采样。</li>
     * </ul>
     * 单采样路径下，所有 issue 默认 confidence=single。
     */
    private Map<String, Object> mergeSamples(List<Map<String, Object>> samples, String chapterLabel) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (samples == null || samples.isEmpty()) {
            merged.put("summary", "");
            merged.put("issues", new ArrayList<>());
            merged.put("passed_items", new ArrayList<>());
            merged.put("check_results", new ArrayList<>());
            return merged;
        }
        Map<String, Object> first = samples.get(0);
        merged.put("summary", first.getOrDefault("summary", ""));

        // 按 fingerprint 聚合
        Map<String, List<Map<String, Object>>> byFp = new LinkedHashMap<>();
        for (Map<String, Object> sample : samples) {
            Object issues = sample.get("issues");
            if (!(issues instanceof List<?>)) continue;
            for (Object item : (List<?>) issues) {
                if (!(item instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> issue = (Map<String, Object>) item;
                String fp = issueFingerprint(chapterLabel, issue);
                byFp.computeIfAbsent(fp, k -> new ArrayList<>()).add(issue);
            }
        }

        List<Map<String, Object>> mergedIssues = new ArrayList<>();
        int total = samples.size();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byFp.entrySet()) {
            List<Map<String, Object>> occurrences = entry.getValue();
            Map<String, Object> base = new LinkedHashMap<>(occurrences.get(0));
            base.put("fingerprint", entry.getKey());
            if (total == 1) {
                base.put("confidence", "single");
            } else if (occurrences.size() == total) {
                base.put("confidence", "high");
            } else {
                base.put("confidence", "needs_review");
                base.put("agreement", occurrences.size() + "/" + total);
            }
            mergedIssues.add(base);
        }
        merged.put("issues", mergedIssues);

        // passed_items：并集去重，保持首次出现顺序
        LinkedHashSet<String> passed = new LinkedHashSet<>();
        for (Map<String, Object> sample : samples) {
            Object pi = sample.get("passed_items");
            if (pi instanceof List<?>) {
                for (Object o : (List<?>) pi) {
                    if (o != null) passed.add(o.toString());
                }
            }
        }
        merged.put("passed_items", new ArrayList<>(passed));
        merged.put("check_results", mergeCheckResults(samples));
        return merged;
    }

    private List<Map<String, Object>> mergeCheckResults(List<Map<String, Object>> samples) {
        Map<String, List<Map<String, Object>>> byCheck = new LinkedHashMap<>();
        for (Map<String, Object> sample : samples) {
            Object results = sample.get("check_results");
            if (!(results instanceof List<?> resultList)) continue;
            for (Object item : resultList) {
                if (!(item instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> check = (Map<String, Object>) item;
                String checkCode = String.valueOf(check.getOrDefault("check_code", ""));
                if (checkCode.isBlank()) continue;
                byCheck.computeIfAbsent(checkCode, k -> new ArrayList<>()).add(check);
            }
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        int total = samples.size();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byCheck.entrySet()) {
            List<Map<String, Object>> occurrences = entry.getValue();
            Map<String, Object> base = new LinkedHashMap<>(occurrences.get(0));
            String chosenStatus = chooseMostConservativeStatus(occurrences);
            base.put("status", chosenStatus);
            if (total > 1) {
                boolean allSame = occurrences.size() == total
                        && occurrences.stream().allMatch(o -> chosenStatus.equals(String.valueOf(o.get("status"))));
                base.put("confidence", allSame ? base.getOrDefault("confidence", "high") : "needs_review");
                if (!allSame) {
                    base.put("agreement", occurrences.size() + "/" + total);
                }
            } else {
                base.putIfAbsent("confidence", "single");
            }
            merged.add(base);
        }
        return merged;
    }

    private String chooseMostConservativeStatus(List<Map<String, Object>> occurrences) {
        List<String> order = List.of("Fail", "Review", "Pass");
        for (String status : order) {
            for (Map<String, Object> item : occurrences) {
                if (status.equals(normalizeCheckStatus(item.get("status")))) {
                    return status;
                }
            }
        }
        return "Review";
    }

    /**
     * Issue 指纹：sha1(归一化 location + "|" + rule_code)。location 归一化方式：去掉空白和标点，
     * 大小写折叠，使 "4 试验条件 > 4.2 环境条件" / "4.2 环境条件" / "4-试验条件 > 4.2环境条件" 视为同一处。
     */
    private String issueFingerprint(String chapterLabel, Map<String, Object> issue) {
        String location = String.valueOf(issue.getOrDefault("location", chapterLabel == null ? "" : chapterLabel));
        String ruleCode = String.valueOf(issue.getOrDefault("rule_code", ""));
        String normalizedLoc = normalizeLocation(location);
        String src = normalizedLoc + "|" + ruleCode;
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : sha) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(src.hashCode());
        }
    }

    private static String normalizeLocation(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s>>\\-\\.、，,()（）【】\\[\\]]+", "").toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> buildFailedChunkResult(int chunkIdx, int totalChunks,
                                                        ChunkUtils.ChunkResult chunk,
                                                        RuleDispatcher.DispatchResult dispatch,
                                                        Exception error,
                                                        long elapsedMs) {
        Map<String, Object> chunkResult = new LinkedHashMap<>();
        chunkResult.put("chunk", chunkIdx + 1);
        chunkResult.put("chapterTitle", chunk.getLabel());
        chunkResult.put("totalChunks", totalChunks);
        chunkResult.put("estimatedTokens", chunk.getEstimatedTokens());
        chunkResult.put("contentLength", chunk.getContent().length());
        chunkResult.put("source", buildChunkSource(chunkIdx, chunk));
        chunkResult.put("sourceRefs", buildChunkSourceRefs(chunkIdx, chunk));
        chunkResult.put("appliedRules", dispatch.getAppliedRuleNames());
        chunkResult.put("failed", true);
        chunkResult.put("retryable", true);
        chunkResult.put("elapsedMs", elapsedMs);
        chunkResult.put("error", error != null ? error.getMessage() : "unknown error");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", "切片审查失败，已保留为可重试切片");
        result.put("issues", new ArrayList<>());
        result.put("passed_items", new ArrayList<>());
        result.put("check_results", new ArrayList<>());
        chunkResult.put("result", result);
        return chunkResult;
    }

    private Map<String, Object> buildChunkSource(int chunkIdx, ChunkUtils.ChunkResult chunk) {
        return DocumentSourceMapper.toChunkSource(chunk, chunkIdx + 1, "document_chunk");
    }

    private List<Map<String, Object>> buildChunkSourceRefs(int chunkIdx, ChunkUtils.ChunkResult chunk) {
        return buildChunkSourceRefs(chunkIdx, chunk, null);
    }

    private List<Map<String, Object>> buildChunkSourceRefs(int chunkIdx, ChunkUtils.ChunkResult chunk,
                                                           Set<Integer> supportingChapterIndices) {
        List<Map<String, Object>> refs = new ArrayList<>();
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("sourceId", "CHUNK-" + String.format("%03d", chunkIdx + 1));
        ref.put("chunk", chunkIdx + 1);
        ref.put("title", chunk.getLabel());
        ref.put("sectionPath", chunk.getLabel());
        ref.put("reason", "matched_chunk");
        refs.add(ref);

        if (supportingChapterIndices != null) {
            for (Integer chapterIdx : new TreeSet<>(supportingChapterIndices)) {
                if (chapterIdx == null || chapterIdx < 0) continue;
                Map<String, Object> supportingRef = new LinkedHashMap<>();
                supportingRef.put("sourceId", "CHAPTER-" + String.format("%03d", chapterIdx + 1));
                supportingRef.put("reason", "referenced_chapter");
                refs.add(supportingRef);
            }
        }
        return refs;
    }

    @SuppressWarnings("unchecked")
    private void enrichResultSourceRefs(Map<String, Object> result,
                                        int chunkIdx,
                                        ChunkUtils.ChunkResult chunk,
                                        Set<Integer> supportingChapterIndices) {
        if (result == null) return;
        for (String field : List.of("issues", "check_results")) {
            Object rawItems = result.get(field);
            if (!(rawItems instanceof List<?> items)) continue;
            for (Object rawItem : items) {
                if (!(rawItem instanceof Map<?, ?>)) continue;
                Map<String, Object> item = (Map<String, Object>) rawItem;
                String evidence = Objects.toString(item.getOrDefault("evidence", ""), "").trim();
                List<Map<String, Object>> refs = buildChunkSourceRefs(
                        chunkIdx, chunk, supportingChapterIndices);
                locateEvidenceNode(chunk, evidence).ifPresent(range -> {
                    if (refs.isEmpty()) return;
                    Map<String, Object> primary = new LinkedHashMap<>(refs.get(0));
                    primary.put("startNodeId", range.startNodeId());
                    primary.put("endNodeId", range.endNodeId());
                    if (range.sectionPath() != null && !range.sectionPath().isBlank()) {
                        primary.put("title", range.sectionPath());
                        primary.put("sectionPath", range.sectionPath());
                        item.put("location", range.sectionPath());
                        item.put("sourceTitle", range.sectionPath());
                    }
                    refs.set(0, primary);
                });
                item.put("sourceRefs", refs);
            }
        }
    }

    private Optional<DocumentEvidenceLocator.NodeRange> locateEvidenceNode(
            ChunkUtils.ChunkResult chunk, String evidence) {
        WordParser.Chapter chapter = chunk == null ? null : chunk.getSourceChapter();
        return chapter == null
                ? Optional.empty()
                : DocumentEvidenceLocator.locate(chapter.getNodes(), evidence);
    }

    private static long elapsedMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    /**
     * Call the AI model with **smart** retry logic:
     * <ul>
     *   <li>4xx (except 429) → fail fast. These are permanent client-side errors
     *       (bad request, expired key, wrong model id) — retrying just wastes
     *       {@code retryIntervalMs × maxRetryAttempts} of wall-clock time.</li>
     *   <li>5xx / network errors / transient response parsing failures →
     *       retry with exponential backoff: {@code retryIntervalMs × 2^(attempt-1)},
     *       capped at 30s so a misconfigured {@code maxRetryAttempts=10} doesn't
     *       sleep for 8 minutes.</li>
     *   <li>429 (rate limited) → wait the longer of the provider's {@code Retry-After}
     *       header and a steeper exponential backoff ({@code retryIntervalMs × 2^attempt}),
     *       capped at 60s. Provider rate-limit windows are ~minute-scale, so the old
     *       1+2+4=7s budget couldn't outlast them and burned all attempts inside one
     *       window — the dominant cause of the "大量切片审查失败" 429 storm.</li>
     *   <li>InterruptedException → propagate immediately, preserving interrupt
     *       status so task cancellation works.</li>
     * </ul>
     */
    private String callWithRetry(AiModelConfig config, String systemPrompt,
                                  String userContent) throws Exception {
        return callWithRetry(config, systemPrompt, userContent, AiCallOptions.defaults());
    }

    private String callWithRetry(AiModelConfig config, String systemPrompt,
                                  String userContent, AiCallOptions options) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                return aiModelService.callAiModel(config, systemPrompt, userContent, options);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Review interrupted", ie);
            } catch (AiApiException ae) {
                if (!ae.isRetryable()) {
                    log.warn("AI model returned non-retryable HTTP {} — failing fast: {}",
                            ae.getStatusCode(), ae.getMessage());
                    throw new RuntimeException(
                            "AI调用失败(HTTP " + ae.getStatusCode() + "，不可重试): " + ae.getResponseBody(),
                            ae);
                }
                lastException = ae;
                log.warn("AI model call attempt {}/{} failed (retryable HTTP {}): {}",
                        attempt, maxRetryAttempts, ae.getStatusCode(), ae.getMessage());
            } catch (Exception e) {
                // IOException (network), or runtime errors thrown by callAiModel itself
                // (empty choices / unparseable JSON / etc.) — treat as transient.
                lastException = e;
                log.warn("AI model call attempt {}/{} failed: {}",
                        attempt, maxRetryAttempts, e.getMessage());
            }
            if (attempt < maxRetryAttempts) {
                long sleepMs;
                if (lastException instanceof AiApiException ae && ae.getStatusCode() == 429) {
                    // 429：尊重 Retry-After，并用更陡的退避抗住分钟级限流窗口（封顶 60s）
                    long retryAfterMs = ae.getRetryAfterSeconds() > 0
                            ? ae.getRetryAfterSeconds() * 1000L : 0L;
                    long expoMs = retryIntervalMs * (1L << attempt);
                    sleepMs = Math.min(Math.max(retryAfterMs, expoMs), 60_000L);
                } else {
                    sleepMs = Math.min(retryIntervalMs * (1L << (attempt - 1)), 30_000L);
                }
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Review interrupted", ie);
                }
            }
        }
        String rootCause = lastException != null ? lastException.getMessage() : "unknown error";
        throw new RuntimeException("AI调用失败(重试" + maxRetryAttempts + "次): " + rootCause, lastException);
    }

    /**
     * Best-effort parse of an AI response into a JSON object Map.
     * Handles common deviations from strict-JSON output that custom-provider models
     * tend to produce: leading prose, trailing reasoning text, ```json ... ``` fences,
     * stray escapes, etc. Returns null only if no parseable {...} object can be located.
     */
    private Map<String, Object> tryParseAiJson(String aiResponse) {
        if (aiResponse == null) return null;
        String text = aiResponse.trim();
        if (text.isEmpty()) return null;

        // 1) Strip a single fenced code block (```json ... ``` or ``` ... ```) if the
        //    whole response is wrapped in one.
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int closingFence = text.lastIndexOf("```");
            if (closingFence >= 0) {
                text = text.substring(0, closingFence);
            }
            text = text.trim();
        }

        // 2) Direct parse attempt.
        Map<String, Object> direct = parseJsonSilently(text);
        if (direct != null) return direct;

        // 3) Extract the first balanced {...} object from the (possibly noisy) text.
        //    Custom providers often surround JSON with explanatory prose or
        //    "<think>...</think>" reasoning blocks.
        String extracted = extractFirstJsonObject(text);
        if (extracted != null) {
            Map<String, Object> parsed = parseJsonSilently(extracted);
            if (parsed != null) return parsed;
        }

        // 4) As a last resort, look inside any embedded ```json fence.
        int fenceStart = text.indexOf("```json");
        if (fenceStart >= 0) {
            int contentStart = text.indexOf('\n', fenceStart);
            int fenceEnd = text.indexOf("```", contentStart > 0 ? contentStart : fenceStart + 7);
            if (contentStart > 0 && fenceEnd > contentStart) {
                String inner = text.substring(contentStart + 1, fenceEnd).trim();
                Map<String, Object> parsed = parseJsonSilently(inner);
                if (parsed != null) return parsed;
                String innerExtract = extractFirstJsonObject(inner);
                if (innerExtract != null) {
                    Map<String, Object> p2 = parseJsonSilently(innerExtract);
                    if (p2 != null) return p2;
                }
            }
        }

        // 5) Salvage a truncated object (输出被 max_tokens 截断、花括号未闭合)：裁到最后一个
        //    完整元素，再补齐未闭合的容器。仅当补全后能成功解析才返回，否则维持 null（只增不减）。
        Map<String, Object> salvaged = salvageTruncatedJson(text);
        if (salvaged != null) return salvaged;

        return null;
    }

    /** 抢救被截断的 JSON：裁剪到最后一个已闭合的 } / ]，再按未闭合栈补齐结尾括号后尝试解析。 */
    private Map<String, Object> salvageTruncatedJson(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        String s = text.substring(start);
        int lastCloser = -1;
        boolean inStr = false, esc = false;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') { depth--; lastCloser = i; }
        }
        if (depth <= 0 || lastCloser < 0) return null; // 未截断或无可裁剪点
        String head = s.substring(0, lastCloser + 1);
        // 重新扫描 head，按顺序记录仍未闭合的容器，逆序补齐。
        java.util.Deque<Character> open = new java.util.ArrayDeque<>();
        inStr = false; esc = false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (esc) { esc = false; continue; }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{' || c == '[') open.push(c);
            else if (c == '}' || c == ']') { if (!open.isEmpty()) open.pop(); }
        }
        StringBuilder sb = new StringBuilder(head);
        while (!open.isEmpty()) sb.append(open.pop() == '{' ? '}' : ']');
        Map<String, Object> parsed = parseJsonSilently(sb.toString());
        if (parsed != null) {
            log.warn("已从截断的 AI 响应中抢救出部分 JSON（裁剪+补齐括号），长度 {}→{}", s.length(), sb.length());
        }
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonSilently(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Object obj = objectMapper.readValue(json, Object.class);
            if (obj instanceof Map) return (Map<String, Object>) obj;
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Find the first balanced JSON object substring in the given text. Skips characters
     * inside string literals (so braces inside quoted strings are not mis-counted) and
     * tolerates escaped characters.
     */
    private String extractFirstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * Build a minimal issues structure from an unparseable AI response so the chunk
     * still surfaces in the UI and the Excel export instead of silently disappearing.
     */
    private Map<String, Object> buildFallbackResult(String chapterLabel, String rawText) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", "AI返回内容无法解析为JSON，已保留原始文本");
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("location", chapterLabel != null ? chapterLabel : "");
        issue.put("description", "模型未按要求返回JSON格式，原始响应：\n"
                + (rawText == null ? "" : rawText.trim()));
        issue.put("suggestion", "请在【模型管理】中确认该模型是否支持指令跟随，或调低 temperature 后重试");
        issue.put("rule", "解析失败");
        List<Map<String, Object>> issues = new ArrayList<>();
        issues.add(issue);
        result.put("issues", issues);
        result.put("passed_items", new ArrayList<>());
        result.put("check_results", new ArrayList<>());
        return result;
    }

    /**
     * Aggregate chunk review results into a unified result.
     * Adds category and confidence breakdowns so the dashboard and Excel export can
     * surface the extended issue fields produced by the new system prompt schema.
     */
    private Map<String, Object> aggregateResults(List<Map<String, Object>> chunkResults) {
        Map<String, Object> aggregated = new LinkedHashMap<>();
        aggregated.put("totalChunks", chunkResults.size());
        aggregated.put("chunkResults", chunkResults);

        List<Integer> scores = new ArrayList<>();
        // 跨切片去重容器：fingerprint → 已归一化的 issue。
        // 同一 fingerprint 多次命中合并 occurrences；占多就计多权重。
        Map<String, Map<String, Object>> dedupedByFp = new LinkedHashMap<>();
        List<Map<String, Object>> failedChunks = new ArrayList<>();
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (String c : ReviewResultSchema.CATEGORY_ENUM) categoryCounts.put(c, 0);
        Map<String, Integer> confidenceCounts = new LinkedHashMap<>();
        Map<String, Integer> checkStatusCounts = new LinkedHashMap<>();
        for (String s : ReviewResultSchema.CHECK_STATUS_ENUM) checkStatusCounts.put(s, 0);
        List<Map<String, Object>> allCheckResults = new ArrayList<>();
        // passed_items 入聚合：按规则编号统计被各切片"通过/不适用"的次数，用于覆盖率
        Map<String, Integer> passedRuleCounts = new LinkedHashMap<>();
        Set<String> seenChapterPassed = new LinkedHashSet<>(); // chapter+passed 文本，避免重复

        for (Map<String, Object> chunk : chunkResults) {
            String chapterTitle = String.valueOf(chunk.getOrDefault("chapterTitle", ""));
            if (Boolean.TRUE.equals(chunk.get("failed"))) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("chunk", chunk.get("chunk"));
                failed.put("chapterTitle", chapterTitle);
                failed.put("estimatedTokens", chunk.get("estimatedTokens"));
                failed.put("contentLength", chunk.get("contentLength"));
                failed.put("appliedRulesCount", chunk.get("appliedRules") instanceof List<?> rules ? rules.size() : 0);
                failed.put("elapsedMs", chunk.get("elapsedMs"));
                failed.put("error", chunk.get("error"));
                failedChunks.add(failed);
            }
            Object result = chunk.get("result");
            if (!(result instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) result;
            Object score = resultMap.get("overall_score");
            if (score instanceof Number) {
                scores.add(((Number) score).intValue());
            }
            Object issues = resultMap.get("issues");
            if (issues instanceof List<?> issueList) {
                for (Object item : issueList) {
                    if (!(item instanceof Map<?, ?>)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> issueMap = (Map<String, Object>) item;
                    Map<String, Object> normalized = normalizeIssue(issueMap, chapterTitle);
                    normalized.putIfAbsent("sourceChunk", chunk.get("chunk"));
                    normalized.putIfAbsent("sourceTitle", chapterTitle);
                    normalized.putIfAbsent("sourceRefs", chunk.get("sourceRefs"));
                    String fp = String.valueOf(normalized.getOrDefault("fingerprint",
                            issueFingerprint(chapterTitle, issueMap)));
                    Map<String, Object> existing = dedupedByFp.get(fp);
                    if (existing == null) {
                        normalized.put("occurrences", 1);
                        dedupedByFp.put(fp, normalized);
                        categoryCounts.merge(String.valueOf(normalized.get("category")), 1, Integer::sum);
                        confidenceCounts.merge(String.valueOf(normalized.getOrDefault("confidence", "single")),
                                1, Integer::sum);
                    } else {
                        int occ = ((Number) existing.getOrDefault("occurrences", 1)).intValue() + 1;
                        existing.put("occurrences", occ);
                        // 跨切片重复出现的问题，置信度提升为 high（单切片 needs_review 也算）
                        if ("needs_review".equals(existing.get("confidence"))) {
                            existing.put("confidence", "high");
                        }
                    }
                }
            }
            Object passed = resultMap.get("passed_items");
            if (passed instanceof List<?> passedList) {
                for (Object p : passedList) {
                    if (p == null) continue;
                    String text = p.toString();
                    String key = chapterTitle + "||" + text;
                    if (!seenChapterPassed.add(key)) continue;
                    String ruleCode = extractRuleCode(text);
                    passedRuleCounts.merge(ruleCode == null ? "(未编号)" : ruleCode, 1, Integer::sum);
                }
            }
            Object checkResults = resultMap.get("check_results");
            if (checkResults instanceof List<?> checkList) {
                for (Object item : checkList) {
                    if (!(item instanceof Map<?, ?>)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> checkMap = (Map<String, Object>) item;
                    Map<String, Object> normalized = normalizeCheckResult(checkMap, chapterTitle);
                    normalized.putIfAbsent("sourceChunk", chunk.get("chunk"));
                    normalized.putIfAbsent("sourceTitle", chapterTitle);
                    normalized.putIfAbsent("sourceRefs", chunk.get("sourceRefs"));
                    allCheckResults.add(normalized);
                    checkStatusCounts.merge(String.valueOf(normalized.get("status")), 1, Integer::sum);
                }
            }
        }

        if (!scores.isEmpty()) {
            int avgScore = (int) scores.stream().mapToInt(Integer::intValue).average().orElse(0);
            aggregated.put("overallScore", avgScore);
        }
        List<Map<String, Object>> allIssues = new ArrayList<>(dedupedByFp.values());
        aggregated.put("totalIssues", allIssues.size());
        aggregated.put("allIssues", allIssues);
        aggregated.put("failedChunkCount", failedChunks.size());
        aggregated.put("failedChunks", failedChunks);
        aggregated.put("categoryCounts", categoryCounts);
        aggregated.put("confidenceCounts", confidenceCounts);
        aggregated.put("totalCheckResults", allCheckResults.size());
        aggregated.put("allCheckResults", allCheckResults);
        aggregated.put("checkStatusCounts", checkStatusCounts);
        aggregated.put("passedRuleCoverage", passedRuleCounts);
        return aggregated;
    }

    /**
     * 把单条 issue 归一化到 schema 范围内：
     * <ul>
     *   <li>category 强制映射到枚举；未命中归其他。</li>
     *   <li>缺失 fingerprint 时按 chapterLabel + rule_code + location 现场补算。</li>
     *   <li>缺失 confidence 默认 single（旧任务兼容）。</li>
     * </ul>
     */
    private Map<String, Object> normalizeIssue(Map<String, Object> raw, String chapterLabel) {
        Map<String, Object> out = new LinkedHashMap<>(raw);
        out.put("category", forceEnum(raw.get("category"), ReviewResultSchema.CATEGORY_ENUM, "其他",
                this::categoryAlias));
        if (!out.containsKey("confidence") || out.get("confidence") == null) {
            out.put("confidence", "single");
        }
        if (!out.containsKey("fingerprint") || out.get("fingerprint") == null) {
            out.put("fingerprint", issueFingerprint(chapterLabel, raw));
        }
        return out;
    }

    private Map<String, Object> normalizeCheckResult(Map<String, Object> raw, String chapterLabel) {
        Map<String, Object> out = new LinkedHashMap<>(raw);
        out.put("status", normalizeCheckStatus(raw.get("status")));
        if (!out.containsKey("confidence") || out.get("confidence") == null) {
            out.put("confidence", "single");
        }
        out.putIfAbsent("check_code", "");
        out.putIfAbsent("rule_code", "");
        out.putIfAbsent("check_question", "");
        out.putIfAbsent("reason", "");
        out.putIfAbsent("evidence", "");
        out.putIfAbsent("suggestion", "");
        out.putIfAbsent("missing_items", new ArrayList<>());
        out.putIfAbsent("location", chapterLabel == null ? "" : chapterLabel);
        return out;
    }

    private String normalizeCheckStatus(Object raw) {
        if (raw == null) return "Review";
        String s = raw.toString().trim();
        if (s.isEmpty()) return "Review";
        String lower = s.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "pass", "passed", "通过", "符合" -> "Pass";
            case "fail", "failed", "不通过", "不符合", "未通过" -> "Fail";
            // 三级判定：部分通过(Partial) 与 不适用(N/A) 一律并入待复核(Review)，交人工判定。
            case "partial", "partially_passed", "部分通过", "部分符合", "部分满足",
                 "n/a", "na", "not_applicable", "not applicable", "不适用",
                 "review", "needs_review", "待复核", "人工复核", "需复核" -> "Review";
            default -> ReviewResultSchema.CHECK_STATUS_ENUM.contains(s) ? s : "Review";
        };
    }

    /** 把任意输入强制映射到 enum；先走别名表，再做大小写不敏感匹配，否则归默认。 */
    private String forceEnum(Object raw, List<String> allowed, String fallback,
                              java.util.function.Function<String, String> aliasFn) {
        if (raw == null) return fallback;
        String s = raw.toString().trim();
        if (s.isEmpty()) return fallback;
        String alias = aliasFn.apply(s.toLowerCase(Locale.ROOT));
        if (alias != null && allowed.contains(alias)) return alias;
        for (String a : allowed) if (a.equalsIgnoreCase(s)) return a;
        return fallback;
    }

    private String categoryAlias(String s) {
        if (s.contains("格式")) return "格式";
        if (s.contains("完整")) return "完整性";
        if (s.contains("标准") || s.contains("符合")) return "标准符合性";
        if (s.contains("逻辑")) return "逻辑一致性";
        if (s.contains("术语") || s.contains("措辞")) return "术语一致性";
        return null;
    }

    /** 从 passed_items 文本里抽取 [R-XXX] 编号；无编号返回 null。 */
    private String extractRuleCode(String text) {
        if (text == null) return null;
        int l = text.indexOf('[');
        int r = text.indexOf(']');
        if (l < 0 || r <= l) return null;
        String inner = text.substring(l + 1, r).trim();
        return inner.isEmpty() ? null : inner;
    }

    /**
     * Export review results to Excel format.
     * Columns: 序号, 章节, 审查意见, 判定依据, 是否接受
     */
    public byte[] exportResultToExcel(String taskId, Long userId) throws IOException {
        ReviewTask task = requireOwnedTask(taskId, userId);
        return ReviewExportUtil.toExcel(task.getAiResult());
    }


    public byte[] exportReviewReportDocx(String taskId, Long userId) throws IOException {
        ReviewTask task = requireOwnedTask(taskId, userId);
        return ReviewExportUtil.toReportDocx(task.getFileName(), task.getId(),
                task.getSelectedModel(), task.getStatus(), task.getAiResult(),
                listAuditLogs(taskId, userId));
    }

    private static String strField(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? (b == null ? "" : b) : a;
    }

    private ReviewTask requireOwnedTask(String taskId, Long userId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only view your own tasks");
        }
        return task;
    }

    private void updateTaskStatus(ReviewTask task, String status, String failReason) {
        task.setStatus(status);
        task.setFailReason(failReason);
        task.setUpdatedAt(LocalDateTime.now());
        if (task.getAiResult() != null) {
            task.setProblemCount(ReviewExportUtil.computeProblemCount(task.getAiResult()));
        }
        reviewTaskMapper.updateById(task);
    }

    private Map<String, Object> enrichAiResultWithOriginalSources(ReviewTask task) {
        Map<String, Object> aiResult = task.getAiResult();
        if (aiResult == null) return null;
        Map<String, Object> enriched = new LinkedHashMap<>(aiResult);
        enrichCheckMetadata(task.getScenarioId(), enriched);
        if (!enriched.containsKey("originalSources")) {
            enriched.put("originalSources", buildOriginalSources(task));
            enriched.put("sourceTextMode", "structured_json_markdown_review_html_display");
        }
        return enriched;
    }

    /**
     * Like {@link #enrichAiResultWithOriginalSources} but strips the heavy
     * {@code originalSources} / {@code chunkResults} keys. The check matrix still
     * gets its rule metadata so the first paint is fully usable.
     */
    private Map<String, Object> enrichAiResultLight(ReviewTask task) {
        Map<String, Object> aiResult = task.getAiResult();
        if (aiResult == null) return null;
        Map<String, Object> enriched = new LinkedHashMap<>(aiResult);
        enrichCheckMetadata(task.getScenarioId(), enriched);
        enriched.remove("originalSources");
        enriched.remove("chunkResults");
        return enriched;
    }

    @SuppressWarnings("unchecked")
    private void enrichCheckMetadata(Long scenarioId, Map<String, Object> aiResult) {
        Object rawChecks = aiResult.get("allCheckResults");
        if (scenarioId == null || !(rawChecks instanceof List<?> checkList) || checkList.isEmpty()) {
            return;
        }

        List<Rule> rules = ruleService.getRulesByScenarioId(scenarioId);
        List<Long> ruleIds = rules.stream()
                .map(Rule::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ruleIds.isEmpty()) return;

        Map<Long, Rule> rulesById = new LinkedHashMap<>();
        Map<String, CheckMetadata> metadataByRuleCode = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (rule.getId() != null) rulesById.put(rule.getId(), rule);
            String ruleCode = Objects.toString(rule.getRuleCode(), "");
            if (!ruleCode.isBlank()) {
                metadataByRuleCode.put(ruleCode, new CheckMetadata(
                        Objects.toString(rule.getRuleName(), ""),
                        ruleCode,
                        Objects.toString(rule.getDescription(), ""),
                        "",
                        ""));
            }
        }

        Map<String, CheckMetadata> metadataByCompositeKey = new LinkedHashMap<>();
        Map<String, CheckMetadata> metadataByCheckCode = new LinkedHashMap<>();
        for (RuleCheck ruleCheck : ruleCheckMapper.findActiveByRuleIds(ruleIds)) {
            Rule rule = rulesById.get(ruleCheck.getRuleId());
            String checkCode = Objects.toString(ruleCheck.getCheckCode(), "");
            if (checkCode.isBlank()) continue;
            String ruleCode = rule == null ? "" : Objects.toString(rule.getRuleCode(), "");
            CheckMetadata metadata = new CheckMetadata(
                    rule == null ? "" : Objects.toString(rule.getRuleName(), ""),
                    ruleCode,
                    rule == null ? "" : Objects.toString(rule.getDescription(), ""),
                    Objects.toString(ruleCheck.getQuestion(), ""),
                    Objects.toString(ruleCheck.getPassCriteria(), ""));
            metadataByCompositeKey.put(ruleCode + "\u0000" + checkCode, metadata);
            metadataByCheckCode.putIfAbsent(checkCode, metadata);
        }

        List<Map<String, Object>> enrichedChecks = new ArrayList<>();
        for (Object item : checkList) {
            if (!(item instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> check = new LinkedHashMap<>((Map<String, Object>) rawMap);
            String checkCode = firstNonBlank(strField(check, "check_code"), strField(check, "checkCode"));
            String ruleCode = firstNonBlank(strField(check, "rule_code"), strField(check, "ruleCode"));
            CheckMetadata metadata = metadataByCompositeKey.get(ruleCode + "\u0000" + checkCode);
            if (metadata == null) metadata = metadataByCheckCode.get(checkCode);
            if (metadata == null) metadata = metadataByRuleCode.get(ruleCode);
            applyCheckMetadata(check, metadata);
            enrichedChecks.add(check);
        }
        aiResult.put("allCheckResults", enrichedChecks);
    }

    private void applyCheckMetadata(Map<String, Object> check, CheckMetadata metadata) {
        if (metadata == null) return;
        if (strField(check, "ruleName").isBlank() && strField(check, "rule_name").isBlank()) {
            check.put("ruleName", metadata.ruleName());
        }
        if (strField(check, "rule_code").isBlank() && strField(check, "ruleCode").isBlank()) {
            check.put("rule_code", metadata.ruleCode());
        }
        if (strField(check, "ruleDescription").isBlank()
                && strField(check, "rule_description").isBlank()) {
            check.put("ruleDescription", metadata.ruleDescription());
        }
        if (strField(check, "check_question").isBlank() && strField(check, "question").isBlank()) {
            check.put("check_question", metadata.checkQuestion());
        }
        if (strField(check, "passCriteria").isBlank() && strField(check, "pass_criteria").isBlank()) {
            check.put("passCriteria", metadata.passCriteria());
        }
    }

    private record CheckMetadata(String ruleName, String ruleCode, String ruleDescription,
                                 String checkQuestion, String passCriteria) {
    }

    private List<Map<String, Object>> buildOriginalSources(ReviewTask task) {
        List<Map<String, Object>> sources = new ArrayList<>();
        if (task.getFilePath() == null || task.getFilePath().isBlank()) {
            return sources;
        }
        try {
            List<WordParser.Chapter> rawChapters = WordParser.parseChapters(task.getFilePath());
            int firstRealIdx = ChunkUtils.findFirstRealChapterIndex(rawChapters);
            List<WordParser.Chapter> chapters = firstRealIdx > 0
                    ? new ArrayList<>(rawChapters.subList(firstRealIdx, rawChapters.size()))
                    : rawChapters;
            return buildOriginalSources(
                    chapters,
                    ChunkUtils.chunkByChapters(chapters, maxChunkTokens));
        } catch (Exception e) {
            log.warn("Failed to build original source view for task {}: {}", task.getId(), e.getMessage());
        }
        return sources;
    }

    private List<Map<String, Object>> buildOriginalSources(
            List<WordParser.Chapter> chapters,
            List<ChunkUtils.ChunkResult> chunks) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            WordParser.Chapter chapter = chapters.get(i);
            sources.add(DocumentSourceMapper.toChapterSource(
                    chapter,
                    i + 1,
                    "CHAPTER-" + String.format("%03d", i + 1)));
        }

        for (int i = 0; i < chunks.size(); i++) {
            sources.add(DocumentSourceMapper.toChunkSource(
                    chunks.get(i), i + 1, "original_chunk"));
        }
        return sources;
    }

    private ReviewTaskDTO toDTO(ReviewTask task) {
        return toDTO(task, false);
    }

    private ReviewTaskDTO toDTO(ReviewTask task, boolean includeOriginalSources) {
        ReviewTaskDTO dto = new ReviewTaskDTO();
        dto.setId(task.getId());
        dto.setUserId(task.getUserId());
        dto.setFileName(task.getFileName());
        dto.setScenarioId(task.getScenarioId());
        dto.setSelectedModel(task.getSelectedModel());
        dto.setStatus(task.getStatus());
        dto.setAiResult(includeOriginalSources ? enrichAiResultWithOriginalSources(task) : task.getAiResult());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setFailReason(task.getFailReason());
        dto.setProblemCount(task.getProblemCount());
        dto.setProgress(webSocketService.getProgress(task.getId()));
        dto.setReviewMode("CHUNK");
        return dto;
    }

    /** Detail DTO without the heavy source payload (see {@link #enrichAiResultLight}). */
    private ReviewTaskDTO toLightDetailDTO(ReviewTask task) {
        ReviewTaskDTO dto = new ReviewTaskDTO();
        dto.setId(task.getId());
        dto.setUserId(task.getUserId());
        dto.setFileName(task.getFileName());
        dto.setScenarioId(task.getScenarioId());
        dto.setSelectedModel(task.getSelectedModel());
        dto.setStatus(task.getStatus());
        dto.setAiResult(enrichAiResultLight(task));
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setFailReason(task.getFailReason());
        dto.setProblemCount(task.getProblemCount());
        dto.setProgress(webSocketService.getProgress(task.getId()));
        dto.setReviewMode("CHUNK");
        return dto;
    }

    /**
     * Lightweight listing for the unified workbench. Returns recent tasks for the
     * user, with the {@code reviewMode} field populated, sorted desc.
     */
    public List<ReviewTaskDTO> recentTasksForUser(Long userId, int limit) {
        LambdaQueryWrapper<ReviewTask> query = new LambdaQueryWrapper<>();
        // Exclude the heavy ai_result JSON from the list query — the dashboard only needs
        // metadata + the cached problem_count, and reading hundreds of big blobs was the
        // cause of the multi-second list load.
        query.select(ReviewTask.class, info -> !"ai_result".equals(info.getColumn()));
        query.eq(ReviewTask::getUserId, userId);
        query.orderByDesc(ReviewTask::getCreatedAt);
        Page<ReviewTask> pageParam = new Page<>(1, Math.max(1, limit));
        return reviewTaskMapper.selectPage(pageParam, query).getRecords()
                .stream().map(this::toListDTO).toList();
    }

    /** Lightweight DTO for the task list: metadata + cached problem count, never ai_result. */
    private ReviewTaskDTO toListDTO(ReviewTask task) {
        ReviewTaskDTO dto = new ReviewTaskDTO();
        dto.setId(task.getId());
        dto.setUserId(task.getUserId());
        dto.setFileName(task.getFileName());
        dto.setScenarioId(task.getScenarioId());
        dto.setSelectedModel(task.getSelectedModel());
        dto.setStatus(task.getStatus());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setFailReason(task.getFailReason());
        dto.setProblemCount(task.getProblemCount());
        dto.setProgress(webSocketService.getProgress(task.getId()));
        dto.setReviewMode("CHUNK");
        return dto;
    }
}
