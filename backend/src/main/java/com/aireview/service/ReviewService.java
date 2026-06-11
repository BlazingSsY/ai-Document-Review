package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.ManualCheckDecisionRequest;
import com.aireview.dto.ReviewTaskDTO;
import com.aireview.entity.AiModelConfig;
import com.aireview.entity.ReviewTask;
import com.aireview.entity.ReviewAuditLog;
import com.aireview.entity.Rule;
import com.aireview.entity.RuleCheck;
import com.aireview.repository.ReviewAuditLogMapper;
import com.aireview.repository.ReviewTaskMapper;
import com.aireview.repository.RuleCheckMapper;
import com.aireview.review.ChunkBatchPlanner;
import com.aireview.review.ModelTier;
import com.aireview.review.ReviewResultSchema;
import com.aireview.review.llm.ThinkingModeDetector;
import com.aireview.util.ChapterReferenceResolver;
import com.aireview.util.ChunkUtils;
import com.aireview.util.RuleDispatcher;
import com.aireview.util.RuleParser;
import com.aireview.util.WordParser;
import com.alibaba.fastjson2.JSONObject;
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

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayOutputStream;
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
    private final ReviewAuditLogMapper reviewAuditLogMapper;
    private final RagReviewService ragReviewService;
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

    @Value("${review.rag.enabled:true}")
    private boolean ragReviewEnabled;

    @Value("${review.rag.fallback-to-legacy:false}")
    private boolean ragFallbackToLegacy;

    /**
     * 收敛性审查的统一参数。这些值不放配置是因为它们是「跨模型收敛」契约本身：
     * 改一动就会让历史 ai_result 与新结果失去可比性，所以集中放在代码里。
     */
    private static final double CONVERGENCE_TEMPERATURE = 0.0;
    private static final double CONVERGENCE_TOP_P = 1.0;
    private static final int CONVERGENCE_MAX_TOKENS = 8192;
    /** 单切片 prompt 中规则部分的硬上限。超过则按 rule_code asc 截断。 */
    private static final int RULE_BUDGET_TOKENS = 6000;
    /** 非思维模型的采样次数。两次种子不同，按 fingerprint 交集做高置信、对称差做"待复核"。 */
    private static final int NON_THINKING_SAMPLES = 2;

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
        Map<String, Object> target = findCheckResult(allCheckResults, request.getCheckCode(), request.getSourceChunk());
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

        syncChunkCheckResult(aiResult, target);
        aiResult.put("manualReviewSummary", buildManualReviewSummary(allCheckResults));
        task.setAiResult(aiResult);
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
            attachChecks(preparedRules);
            AiModelConfig modelConfig = aiModelService.getEnabledModel(task.getSelectedModel());
            List<ChunkUtils.ChunkResult> chunks = ChunkUtils.chunkByChapters(chapters, maxChunkTokens);

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
                        chunk.getLabel(), chunk.getContent(), preparedRules);

                CompletableFuture<Void> fut = CompletableFuture.runAsync(() -> {
                    long startNs = System.nanoTime();
                    try {
                        Map<String, Object> replacement;
                        try {
                            replacement = reviewSingleChunk(chunkIdx, chunks.size(), chunk, dispatch, chapters, modelConfig);
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

            if (ragReviewEnabled) {
                try {
                    webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                            "文档已上传，开始向量化预处理...", 6);
                    RagReviewService.PreparedDocument preparedDocument =
                            ragReviewService.prepareDocumentVectors(task);
                    ragReviewService.executeReview(task, runStamp, preparedDocument);
                    return;
                } catch (Exception ragEx) {
                    if (!ragFallbackToLegacy) {
                        throw ragEx;
                    }
                    log.warn("RAG review failed, falling back to legacy chunk review: {}", ragEx.getMessage());
                    webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                            "RAG review failed, falling back to legacy chunk review: " + ragEx.getMessage(), 6);
                }
            }

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

            // 5a. Pre-compute dispatch for every chunk so we can write the debug file
            // BEFORE any AI call. This way a 429 / timeout on chunk 1 still leaves a
            // diagnosable 切片结果.json on disk with the applied-rule traces.
            List<RuleDispatcher.DispatchResult> dispatches = new ArrayList<>();
            List<Map<String, Object>> chunkDispatchTraces = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkUtils.ChunkResult chunk = chunks.get(i);
                RuleDispatcher.DispatchResult dispatch = RuleDispatcher.dispatchForChunk(
                        chunk.getLabel(), chunk.getContent(), preparedRules);
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

            // 5b. 批处理：按规则签名分组 + token 预算装箱，一次 AI 调用审查多个切片
            //
            // 设计要点（详见 README "审查管线 - 批处理与采样" 章节）：
            //   1. ModelTier 决定单批 user 预算和 chunk 数上限；
            //   2. ChunkBatchPlanner 把切片按签名分组、按预算装箱，输出 BatchPlan 列表；
            //   3. 并发调度：父线程 Semaphore 控制单任务批并发，与之前 per-chunk 模式一致；
            //   4. 校准波：先跑前 N 批，观察实际输出 token 占用率；
            //      ratio < 50% → 后续批 chunk 数上限 +2 / ratio > 80% → -1，重新打包剩余切片；
            //   5. 兜底：批输出缺 chunk_id 或解析失败 → 该批拆回单切片重发，并 WebSocket 提示。
            int totalChunks = chunks.size();
            ModelTier tier = ModelTier.detect(modelConfig);
            int batchConcurrency = Math.max(1, Math.min(chunkConcurrency, totalChunks));
            int sampleCount = ThinkingModeDetector.isThinking(modelConfig) ? 1 : NON_THINKING_SAMPLES;

            List<ChunkBatchPlanner.BatchPlan> initialPlan = ChunkBatchPlanner.plan(
                    chunks, dispatches, tier, /*adaptiveCap*/ -1);
            log.info("Batch planning: task={}, tier={}, totalChunks={}, batches={}, sampleCount={}",
                    taskId, tier, totalChunks, initialPlan.size(), sampleCount);
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "已规划 " + initialPlan.size() + " 个批次（模型档位 " + tier
                            + "，单批最多 " + tier.maxChunksPerBatch() + " 片，采样 "
                            + sampleCount + " 次）", 30);

            @SuppressWarnings({"unchecked", "rawtypes"})
            final Map<String, Object>[] orderedResults = new Map[totalChunks];
            AtomicInteger completedChunkCount = new AtomicInteger(0);
            Semaphore taskSlots = new Semaphore(batchConcurrency);
            List<Map<String, Object>> batchFallbacks = Collections.synchronizedList(new ArrayList<>());
            // 滑动窗口收集每批的输出占用率，用于动态调整 chunk 数上限
            List<Double> observedRatios = Collections.synchronizedList(new ArrayList<>());

            // 校准波：当总批次 > 3 时，先跑前 ⌈total/3⌉ 批观察输出占用率，再决定是否调高/调低 chunk 数。
            // 总批次少时直接一次跑完（动态调整无意义）。
            int calibrationCount = initialPlan.size() > 3 ? Math.max(2, initialPlan.size() / 3) : initialPlan.size();
            List<ChunkBatchPlanner.BatchPlan> wave1 = initialPlan.subList(0, calibrationCount);
            List<ChunkBatchPlanner.BatchPlan> remaining = new ArrayList<>(
                    initialPlan.subList(calibrationCount, initialPlan.size()));

            runBatchWave(wave1, orderedResults, chunks, dispatches, chapters, modelConfig,
                    taskId, taskSlots, completedChunkCount, totalChunks,
                    observedRatios, batchFallbacks, sampleCount, /*wave*/ 1);

            // 动态调整：根据已观察到的输出占用率决定剩余批的 chunk 数上限
            if (!remaining.isEmpty()) {
                int newCap = adaptChunkCap(tier, observedRatios);
                webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                        "校准波完成（平均输出占用率 " + percentStr(avg(observedRatios))
                                + "），剩余 " + remainingChunkCount(remaining) + " 片以单批最多 "
                                + newCap + " 片继续审查", 55);
                // 收集剩余 chunk 索引并按新 cap 重新打包
                List<Integer> remainingIdx = new ArrayList<>();
                for (ChunkBatchPlanner.BatchPlan b : remaining) remainingIdx.addAll(b.getChunkIndices());
                List<ChunkBatchPlanner.BatchPlan> wave2 = ChunkBatchPlanner.replanRemaining(
                        chunks, dispatches, remainingIdx, tier, newCap, calibrationCount + 1);
                runBatchWave(wave2, orderedResults, chunks, dispatches, chapters, modelConfig,
                        taskId, taskSlots, completedChunkCount, totalChunks,
                        observedRatios, batchFallbacks, sampleCount, /*wave*/ 2);
            }

            if (cancelledTasks.remove(taskId)) {
                log.info("Review task {} cancelled during processing", taskId);
                return;
            }

            // 按原顺序收集结果；被取消跳过/批失败但兜底也未补回的切片位置为 null
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
            aggregatedResult.put("samplingStrategy", crossModelEligible ? "double" : "single");
            aggregatedResult.put("modelName", modelConfig.getModelName());
            aggregatedResult.put("modelKey", modelConfig.getModelKey());
            aggregatedResult.put("modelTier", tier.name());
            aggregatedResult.put("batchFallbacks", batchFallbacks);
            aggregatedResult.put("batchTotalCount", initialPlan.size());
            aggregatedResult.put("batchFallbackCount", batchFallbacks.size());

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
                m.put("contentLength", ch.getContent().length());
                m.put("content", ch.getContent());          // full content
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
                m.put("contentLength", chunk.getContent().length());
                m.put("content", chunk.getContent());       // full content
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
        if (rulesForChunk == null || rulesForChunk.isEmpty()) {
            // 无命中规则：仍然走结构化路径，让模型回传规范 JSON 而不是自由文本。
            RuleParser.RuleEntry placeholder = new RuleParser.RuleEntry(
                    "R-DEFAULT-001",
                    "通用文档质量轻量审查",
                    "本片段未命中任何已配置规则。仅基于通用文档质量要求（错别字、引用一致性、字段完整性等）做轻量审查；"
                            + "若没有问题，请保持 issues 为空数组，并在 summary 中说明本切片未命中规则。");
            return RuleParser.buildStructuredSystemPrompt(List.of(placeholder));
        }

        // 转 RuleEntry + 缺失编号自动补齐
        List<RuleParser.RuleEntry> entries = new ArrayList<>();
        int autoSeq = 1;
        for (RuleDispatcher.PreparedRule pr : rulesForChunk) {
            String code = pr.getMetadata() != null ? pr.getMetadata().getRuleCode() : null;
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
        return RuleParser.buildStructuredSystemPrompt(kept);
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

    // ------------------------------------------------------------------
    // 批处理：单波执行 / 单批处理 / 动态调整辅助
    // ------------------------------------------------------------------

    /** 单波批处理：父线程 Semaphore 限并发，runnable 内做 chunk 兜底重发，不阻断其他批。 */
    private void runBatchWave(List<ChunkBatchPlanner.BatchPlan> wave,
                               Map<String, Object>[] orderedResults,
                               List<ChunkUtils.ChunkResult> chunks,
                               List<RuleDispatcher.DispatchResult> dispatches,
                               List<WordParser.Chapter> chapters,
                               AiModelConfig modelConfig,
                               String taskId,
                               Semaphore taskSlots,
                               AtomicInteger completedChunkCount,
                               int totalChunks,
                               List<Double> observedRatios,
                               List<Map<String, Object>> batchFallbacks,
                               int sampleCount,
                               int waveNum) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(wave.size());
        for (ChunkBatchPlanner.BatchPlan batch : wave) {
            if (cancelledTasks.contains(taskId)) break;
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
            final ChunkBatchPlanner.BatchPlan fb = batch;
            CompletableFuture<Void> fut = CompletableFuture.runAsync(() -> {
                long startNs = System.nanoTime();
                try {
                    BatchOutcome outcome = reviewSingleBatch(
                            fb, chunks, dispatches, chapters, modelConfig, taskId, sampleCount);
                    long elapsedMs = elapsedMs(startNs);
                    // 1) 成功命中的切片直接回填
                    outcome.chunkResults.forEach((idx, res) -> {
                        res.put("elapsedMs", elapsedMs);
                        res.put("batchId", fb.getBatchId());
                        orderedResults[idx] = res;
                    });
                    // 2) 输出占用率入观察队列（用于校准波后动态调整）
                    observedRatios.add(outcome.outputRatio);
                    // 3) 缺失 chunk_id 走单切片兜底
                    if (!outcome.missingChunkIndices.isEmpty()) {
                        Map<String, Object> fb1 = new LinkedHashMap<>();
                        fb1.put("batchId", fb.getBatchId());
                        fb1.put("chunks", outcome.missingChunkIndices.stream()
                                .map(i -> i + 1).toList());
                        fb1.put("reason", outcome.failureReason == null
                                ? "输出 chunk_id 缺失" : outcome.failureReason);
                        fb1.put("extraCalls", outcome.missingChunkIndices.size() * sampleCount);
                        batchFallbacks.add(fb1);
                        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                                "批 " + fb.getBatchId() + " 输出不完整，自动拆分为 "
                                        + outcome.missingChunkIndices.size() + " 个单切片重审",
                                Math.min(90, 30 + (int) ((double) completedChunkCount.get() / totalChunks * 60)));
                        for (int idx : outcome.missingChunkIndices) {
                            try {
                                Map<String, Object> single = reviewSingleChunk(
                                        idx, totalChunks, chunks.get(idx), dispatches.get(idx),
                                        chapters, modelConfig, taskId);
                                single.put("fallbackFromBatch", fb.getBatchId());
                                orderedResults[idx] = single;
                            } catch (Exception e) {
                                orderedResults[idx] = buildFailedChunkResult(
                                        idx, totalChunks, chunks.get(idx), dispatches.get(idx),
                                        e, elapsedMs(startNs));
                            }
                        }
                    }
                    int done = completedChunkCount.addAndGet(fb.getChunkIndices().size());
                    int progress = 30 + (int) ((double) done / totalChunks * 60);
                    log.info("Batch wave={} completed: task={}, batch={}, chunks={}, outputRatio={}, elapsedMs={}",
                            waveNum, taskId, fb.getBatchId(), fb.getChunkIndices().size(),
                            percentStr(outcome.outputRatio), elapsedMs);
                    webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                            "批 " + fb.getBatchId() + " 完成（" + fb.getChunkIndices().size()
                                    + " 片，输出占用 " + percentStr(outcome.outputRatio)
                                    + "，累计 " + done + "/" + totalChunks + "）",
                            progress);
                } catch (Exception e) {
                    // 整批失败：对该批所有 chunk 走单切片兜底（不让一个批失败拖死所有切片）
                    long elapsedMs = elapsedMs(startNs);
                    log.warn("Batch wave={} failed; falling back to single-chunk: task={}, batch={}, reason={}",
                            waveNum, taskId, fb.getBatchId(), e.getMessage(), e);
                    Map<String, Object> recRec = new LinkedHashMap<>();
                    recRec.put("batchId", fb.getBatchId());
                    recRec.put("chunks", fb.getChunkIndices().stream().map(i -> i + 1).toList());
                    recRec.put("reason", "批审查失败：" + e.getMessage());
                    recRec.put("extraCalls", fb.getChunkIndices().size() * sampleCount);
                    batchFallbacks.add(recRec);
                    webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                            "批 " + fb.getBatchId() + " 审查失败，自动拆分为 "
                                    + fb.getChunkIndices().size() + " 个单切片重审：" + e.getMessage(),
                            Math.min(90, 30 + (int) ((double) completedChunkCount.get() / totalChunks * 60)));
                    for (int idx : fb.getChunkIndices()) {
                        try {
                            Map<String, Object> single = reviewSingleChunk(
                                    idx, totalChunks, chunks.get(idx), dispatches.get(idx),
                                    chapters, modelConfig, taskId);
                            single.put("fallbackFromBatch", fb.getBatchId());
                            orderedResults[idx] = single;
                        } catch (Exception e2) {
                            orderedResults[idx] = buildFailedChunkResult(
                                    idx, totalChunks, chunks.get(idx), dispatches.get(idx), e2, elapsedMs);
                        }
                    }
                    completedChunkCount.addAndGet(fb.getChunkIndices().size());
                } finally {
                    taskSlots.release();
                }
            }, chunkReviewExecutor);
            futures.add(fut);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /** 批处理执行结果。 */
    private static final class BatchOutcome {
        final Map<Integer, Map<String, Object>> chunkResults; // chunkIdx → 已合并采样的 chunkResult
        final List<Integer> missingChunkIndices;              // 模型未输出的 chunk_id 对应的原 chunkIdx
        final double outputRatio;                             // 实际输出 token / 输出预算
        final String failureReason;                           // 非空表示部分/整体失败的原因
        BatchOutcome(Map<Integer, Map<String, Object>> chunkResults,
                     List<Integer> missingChunkIndices, double outputRatio, String failureReason) {
            this.chunkResults = chunkResults;
            this.missingChunkIndices = missingChunkIndices;
            this.outputRatio = outputRatio;
            this.failureReason = failureReason;
        }
    }

    /**
     * 单批审查：装配批 prompt + chunk 分隔 user message → 采样 N 次 → 按 chunk_id 拆解 →
     * 每个 chunk 按 fingerprint 合并采样。返回 BatchOutcome，调用方处理缺失 chunk_id 的兜底。
     */
    private BatchOutcome reviewSingleBatch(ChunkBatchPlanner.BatchPlan batch,
                                            List<ChunkUtils.ChunkResult> chunks,
                                            List<RuleDispatcher.DispatchResult> dispatches,
                                            List<WordParser.Chapter> chapters,
                                            AiModelConfig modelConfig,
                                            String taskId,
                                            int sampleCount) throws Exception {
        List<Integer> chunkIdxs = batch.getChunkIndices();
        // 1) 组装 chunk_id 列表（C-<idx+1>）和 user message
        List<String> chunkIds = new ArrayList<>(chunkIdxs.size());
        Map<Integer, Set<Integer>> supportingRefsByChunk = new LinkedHashMap<>();
        StringBuilder userContent = new StringBuilder();
        for (int idx : chunkIdxs) {
            String cid = "C-" + (idx + 1);
            chunkIds.add(cid);
            ChunkUtils.ChunkResult chunk = chunks.get(idx);
            userContent.append("===CHUNK ").append(cid).append("===\n");
            userContent.append("章节: ").append(chunk.getLabel()).append("\n\n");
            userContent.append(chunk.getContent());
            Set<Integer> refIdx = ChapterReferenceResolver
                    .findReferencedChapters(chunk.getContent(), chunk.getLabel(), chapters);
            supportingRefsByChunk.put(idx, refIdx);
            String supporting = ChapterReferenceResolver.renderSupportingContext(refIdx, chapters);
            if (!supporting.isEmpty()) userContent.append(supporting);
            userContent.append("\n\n");
        }

        // 2) 组装 batch system prompt（使用同签名的代表性 dispatch 即可，因为本批所有 chunk 同签名）
        RuleDispatcher.DispatchResult sigDispatch = dispatches.get(chunkIdxs.get(0));
        List<RuleParser.RuleEntry> entries = buildRuleEntriesForBatch(sigDispatch);
        List<RuleParser.RuleEntry> kept = applyRuleBudget(entries, sigDispatch.getAppliedRules());
        String systemPrompt = RuleParser.buildBatchStructuredSystemPrompt(kept, chunkIds);

        // 3) 采样
        List<JSONObject> sampleParsed = new ArrayList<>();
        int totalActualOutputTokens = 0;
        for (int s = 0; s < sampleCount; s++) {
            long seed = stableSeed(taskId,
                    Integer.parseInt(batch.getBatchId().substring(batch.getBatchId().lastIndexOf('-') + 1)),
                    s);
            AiCallOptions options = buildBatchConvergenceOptions(modelConfig, seed);
            String aiResponse = callWithRetry(modelConfig, systemPrompt, userContent.toString(), options);
            totalActualOutputTokens += ChunkUtils.estimateTokens(aiResponse);
            Map<String, Object> parsed = tryParseAiJson(aiResponse);
            if (parsed == null) {
                // 该 sample 无法解析；记空，让后续合并按缺失处理
                sampleParsed.add(null);
                continue;
            }
            sampleParsed.add(new JSONObject(parsed));
        }

        // 4) 按 chunk_id 拆解每个采样
        // sampleByChunk: chunkId → List<sample 子结果>
        Map<String, List<Map<String, Object>>> sampleByChunk = new LinkedHashMap<>();
        for (String cid : chunkIds) sampleByChunk.put(cid, new ArrayList<>());
        for (JSONObject sample : sampleParsed) {
            if (sample == null) continue;
            Object chunksObj = sample.get("chunks");
            if (!(chunksObj instanceof List<?>)) continue;
            for (Object item : (List<?>) chunksObj) {
                if (!(item instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) item;
                String cid = String.valueOf(entry.get("chunk_id"));
                if (sampleByChunk.containsKey(cid)) {
                    sampleByChunk.get(cid).add(entry);
                }
            }
        }

        // 5) 每个 chunk 按 fingerprint 合并采样；采样为 0 的 chunk 进 missing 列表
        Map<Integer, Map<String, Object>> chunkResults = new LinkedHashMap<>();
        List<Integer> missing = new ArrayList<>();
        for (int idx : chunkIdxs) {
            String cid = "C-" + (idx + 1);
            List<Map<String, Object>> chunkSamples = sampleByChunk.get(cid);
            if (chunkSamples.isEmpty()) {
                missing.add(idx);
                continue;
            }
            Map<String, Object> merged = mergeSamples(chunkSamples, chunks.get(idx).getLabel());
            Map<String, Object> chunkResult = new LinkedHashMap<>();
            chunkResult.put("chunk", idx + 1);
            chunkResult.put("chapterTitle", chunks.get(idx).getLabel());
            chunkResult.put("totalChunks", chunks.size());
            chunkResult.put("estimatedTokens", chunks.get(idx).getEstimatedTokens());
            chunkResult.put("source", buildChunkSource(idx, chunks.get(idx)));
            chunkResult.put("sourceRefs", buildChunkSourceRefs(idx, chunks.get(idx), supportingRefsByChunk.get(idx)));
            chunkResult.put("appliedRules", dispatches.get(idx).getAppliedRuleNames());
            chunkResult.put("samplingStrategy", sampleCount > 1 ? "double" : "single");
            chunkResult.put("sampleCount", chunkSamples.size());
            chunkResult.put("result", merged);
            chunkResults.put(idx, chunkResult);
        }

        // 6) 输出占用率：实际输出 / (单批最大输出预算 × 采样次数)
        //    单批最大输出预算按思维 16K / 非思维 8K 估算（与各 provider 默认相近）
        int outputBudgetPerCall = ThinkingModeDetector.isThinking(modelConfig) ? 16_000 : 8_000;
        double outputRatio = sampleCount == 0 ? 0.0
                : (double) totalActualOutputTokens / ((long) outputBudgetPerCall * sampleCount);
        String reason = missing.isEmpty() ? null : "输出 chunk_id 缺失：" + missing.size() + " 个";
        return new BatchOutcome(chunkResults, missing, outputRatio, reason);
    }

    /** 从分发结果构造批 RuleEntry，缺 rule_code 时自动补 R-AUTO-NNN。 */
    private List<RuleParser.RuleEntry> buildRuleEntriesForBatch(RuleDispatcher.DispatchResult dispatch) {
        List<RuleParser.RuleEntry> entries = new ArrayList<>();
        int autoSeq = 1;
        for (RuleDispatcher.PreparedRule pr : dispatch.getAppliedRules()) {
            String code = pr.getMetadata() != null ? pr.getMetadata().getRuleCode() : null;
            if (code == null || code.isBlank()) {
                code = "R-AUTO-" + String.format("%03d", autoSeq++);
            }
            entries.add(new RuleParser.RuleEntry(
                    code,
                    pr.getRule().getRuleName(),
                    pr.getBody(),
                    toCheckEntries(code, pr.getChecks())));
        }
        return entries;
    }

    /**
     * 批量调用专用 options：在 {@link #buildConvergenceOptions} 基础上，
     * 把 structuredSchema 换成 {@link ReviewResultSchema#batchSchema()}，并启用 prompt 缓存
     * （Anthropic 加 cache_control，OpenAI 兼容自动命中）。
     */
    private AiCallOptions buildBatchConvergenceOptions(AiModelConfig modelConfig, long seed) {
        boolean thinking = ThinkingModeDetector.isThinking(modelConfig);
        AiCallOptions.AiCallOptionsBuilder b = AiCallOptions.builder()
                .seed(seed)
                .maxTokensOverride(thinking ? null : CONVERGENCE_MAX_TOKENS)
                .structuredSchema(com.alibaba.fastjson2.JSON.parseObject(
                        com.alibaba.fastjson2.JSON.toJSONString(ReviewResultSchema.batchSchema())))
                .structuredSchemaName(ReviewResultSchema.BATCH_SCHEMA_NAME)
                .enablePromptCache(true);
        if (!thinking) {
            b.temperature(CONVERGENCE_TEMPERATURE).topP(CONVERGENCE_TOP_P);
        }
        return b.build();
    }

    /** 动态调整 chunk 数上限：ratio < 50% → +2（不超过 1.5× 档位上限）；> 80% → -1（不低于 2）。 */
    private int adaptChunkCap(ModelTier tier, List<Double> observedRatios) {
        double avg = avg(observedRatios);
        int base = tier.maxChunksPerBatch();
        int ceiling = Math.max(base + 1, (int) Math.round(base * 1.5));
        int floor = Math.max(2, base - 2);
        if (avg < 0.5) return Math.min(base + 2, ceiling);
        if (avg > 0.8) return Math.max(base - 1, floor);
        return base;
    }

    private static double avg(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return 0.0;
        double sum = 0;
        for (Double x : xs) if (x != null) sum += x;
        return sum / xs.size();
    }

    private static int remainingChunkCount(List<ChunkBatchPlanner.BatchPlan> batches) {
        int n = 0;
        for (ChunkBatchPlanner.BatchPlan b : batches) n += b.getChunkIndices().size();
        return n;
    }

    private static String percentStr(double ratio) {
        return String.format("%.0f%%", Math.max(0.0, Math.min(1.0, ratio)) * 100);
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
    private Map<String, Object> reviewSingleChunk(int chunkIdx, int totalChunks,
                                                   ChunkUtils.ChunkResult chunk,
                                                   RuleDispatcher.DispatchResult dispatch,
                                                   List<WordParser.Chapter> chapters,
                                                   AiModelConfig modelConfig) throws Exception {
        return reviewSingleChunk(chunkIdx, totalChunks, chunk, dispatch, chapters, modelConfig, /*taskId*/ null);
    }

    /**
     * 单切片审查：装配 prompt + 跨章节引用上下文，按模型类型选择采样策略，
     * 再把多次采样结果按 fingerprint 合并为最终 chunkResult。
     *
     * <p>采样策略由 {@link #shouldDoubleSample(AiModelConfig)} 决定：
     * <ul>
     *   <li>思维模型：单次采样（temperature 服务器锁定，多次采样无收敛意义且翻倍成本）；</li>
     *   <li>非思维模型：双次采样，seed 不同；fingerprint 交集 → confidence=high 直接采纳；
     *       对称差 → confidence=needs_review 标灰待人工复核。</li>
     * </ul>
     */
    private Map<String, Object> reviewSingleChunk(int chunkIdx, int totalChunks,
                                                   ChunkUtils.ChunkResult chunk,
                                                   RuleDispatcher.DispatchResult dispatch,
                                                   List<WordParser.Chapter> chapters,
                                                   AiModelConfig modelConfig,
                                                   String taskId) throws Exception {
        int chunkNum = chunkIdx + 1;
        String systemPrompt = buildPromptForRules(dispatch.getAppliedRules());

        Set<Integer> refIdx = ChapterReferenceResolver
                .findReferencedChapters(chunk.getContent(), chunk.getLabel(), chapters);
        String supporting = ChapterReferenceResolver.renderSupportingContext(refIdx, chapters);

        String chunkContent = "章节: " + chunk.getLabel()
                + " (" + chunkNum + "/" + totalChunks + ")\n\n" + chunk.getContent()
                + supporting;

        int sampleCount = shouldDoubleSample(modelConfig) ? NON_THINKING_SAMPLES : 1;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int s = 0; s < sampleCount; s++) {
            long seed = stableSeed(taskId, chunkIdx, s);
            AiCallOptions options = buildConvergenceOptions(modelConfig, seed);
            String aiResponse = callWithRetry(modelConfig, systemPrompt, chunkContent, options);
            Map<String, Object> parsed = tryParseAiJson(aiResponse);
            if (parsed == null) {
                log.warn("Chunk {} sample {} AI 响应无法解析为 JSON，包装为原始文本 issue；长度={}",
                        chunkNum, s + 1, aiResponse != null ? aiResponse.length() : 0);
                parsed = buildFallbackResult(chunk.getLabel(), aiResponse);
            }
            samples.add(parsed);
        }
        Map<String, Object> merged = mergeSamples(samples, chunk.getLabel());

        Map<String, Object> chunkResult = new HashMap<>();
        chunkResult.put("chunk", chunkNum);
        chunkResult.put("chapterTitle", chunk.getLabel());
        chunkResult.put("totalChunks", totalChunks);
        chunkResult.put("estimatedTokens", chunk.getEstimatedTokens());
        chunkResult.put("source", buildChunkSource(chunkIdx, chunk));
        chunkResult.put("sourceRefs", buildChunkSourceRefs(chunkIdx, chunk, refIdx));
        chunkResult.put("appliedRules", dispatch.getAppliedRuleNames());
        chunkResult.put("samplingStrategy", sampleCount > 1 ? "double" : "single");
        chunkResult.put("sampleCount", sampleCount);
        chunkResult.put("result", merged);
        return chunkResult;
    }

    /** 思维模型走单采样（温度服务器锁定，多次采样不收敛）；其余走双采样。 */
    private boolean shouldDoubleSample(AiModelConfig modelConfig) {
        return !ThinkingModeDetector.isThinking(modelConfig);
    }

    /**
     * 构造收敛性 AI 调用参数：
     * <ul>
     *   <li>非思维模型：temperature=0、top_p=1、seed=stable、结构化输出={@link ReviewResultSchema}；</li>
     *   <li>思维模型：temperature 由 server 强制，仍传 seed（不支持的 provider 会忽略）和结构化 schema。</li>
     * </ul>
     */
    private AiCallOptions buildConvergenceOptions(AiModelConfig modelConfig, long seed) {
        boolean thinking = ThinkingModeDetector.isThinking(modelConfig);
        AiCallOptions.AiCallOptionsBuilder b = AiCallOptions.builder()
                .seed(seed)
                .maxTokensOverride(thinking ? null : CONVERGENCE_MAX_TOKENS)
                .structuredSchema(com.alibaba.fastjson2.JSON.parseObject(
                        com.alibaba.fastjson2.JSON.toJSONString(ReviewResultSchema.schema())))
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
        List<String> order = List.of("Fail", "Partial", "Review", "N/A", "Pass");
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
        Map<String, Object> source = new LinkedHashMap<>();
        String text = chunk.getContent() == null ? "" : chunk.getContent();
        source.put("blockId", "CHUNK-" + String.format("%03d", chunkIdx + 1));
        source.put("chunk", chunkIdx + 1);
        source.put("type", "document_chunk");
        source.put("sectionPath", chunk.getLabel());
        source.put("text", text);
        source.put("textLength", text.length());
        source.put("estimatedTokens", chunk.getEstimatedTokens());
        return source;
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

    private static long elapsedMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    /**
     * Call the AI model with **smart** retry logic:
     * <ul>
     *   <li>4xx (except 429) → fail fast. These are permanent client-side errors
     *       (bad request, expired key, wrong model id) — retrying just wastes
     *       {@code retryIntervalMs × maxRetryAttempts} of wall-clock time.</li>
     *   <li>429 / 5xx / network errors / transient response parsing failures →
     *       retry with exponential backoff: {@code retryIntervalMs × 2^(attempt-1)},
     *       capped at 30s so a misconfigured {@code maxRetryAttempts=10} doesn't
     *       sleep for 8 minutes.</li>
     *   <li>InterruptedException → propagate immediately, preserving interrupt
     *       status so task cancellation works.</li>
     * </ul>
     *
     * <p>With the defaults ({@code maxAttempts=4}, {@code intervalMs=1000}) the
     * total backoff budget is 1+2+4=7s instead of the previous 4×6=24s.
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
                long sleepMs = Math.min(retryIntervalMs * (1L << (attempt - 1)), 30_000L);
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

        return null;
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
            case "partial", "partially_passed", "部分通过", "部分符合", "部分满足" -> "Partial";
            case "fail", "failed", "不通过", "不符合", "未通过" -> "Fail";
            case "n/a", "na", "not_applicable", "not applicable", "不适用" -> "N/A";
            case "review", "needs_review", "待复核", "人工复核", "需复核" -> "Review";
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
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only export your own tasks");
        }
        if (task.getAiResult() == null) {
            throw new IllegalArgumentException("No review result available for export");
        }

        Map<String, Object> aiResult = task.getAiResult();

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("审查意见");

            // Header style
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 12);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            // Data style
            CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setVerticalAlignment(VerticalAlignment.TOP);
            dataStyle.setWrapText(true);
            dataStyle.setBorderBottom(BorderStyle.THIN);
            dataStyle.setBorderTop(BorderStyle.THIN);
            dataStyle.setBorderLeft(BorderStyle.THIN);
            dataStyle.setBorderRight(BorderStyle.THIN);

            Object allCheckResultsObj = aiResult.get("allCheckResults");
            if (allCheckResultsObj instanceof List<?> checkList && !checkList.isEmpty()) {
                Row headerRow = sheet.createRow(0);
                String[] headers = {"序号", "章节", "检查项编号", "规则编码", "判定", "检查项", "判定理由", "证据", "缺失项", "建议", "置信度", "人工复核"};
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }
                int rowNum = 1;
                for (Object item : checkList) {
                    if (!(item instanceof Map<?, ?>)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> check = (Map<String, Object>) item;
                    writeCheckResultRow(sheet.createRow(rowNum), rowNum, check, dataStyle);
                    rowNum++;
                }
                int[] widths = {2000, 6000, 5000, 4000, 3000, 12000, 12000, 12000, 9000, 10000, 3000, 3000};
                for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i]);
                workbook.write(out);
                return out.toByteArray();
            }

            // Create header row with category / rule_code to match the extended issue schema.
            // 判定依据 now combines the rule
            // name with the evidence excerpt so reviewers can verify each finding inline.
            Row headerRow = sheet.createRow(0);
            String[] headers = {"序号", "章节", "问题分类", "规则编码", "审查意见", "判定依据", "是否接受"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            Object chunkResultsObj = aiResult.get("chunkResults");
            if (chunkResultsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> chunkResults = (List<Map<String, Object>>) chunkResultsObj;
                for (Map<String, Object> chunk : chunkResults) {
                    String chapterTitle = chunk.get("chapterTitle") != null
                            ? chunk.get("chapterTitle").toString() : "";
                    Object result = chunk.get("result");
                    if (result instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) result;
                        Object issuesObj = resultMap.get("issues");
                        if (issuesObj instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> issues = (List<Map<String, Object>>) issuesObj;
                            for (Map<String, Object> issue : issues) {
                                writeIssueRow(sheet.createRow(rowNum), rowNum, issue, chapterTitle, dataStyle);
                                rowNum++;
                            }
                        }
                    }
                }
            }

            // Also handle flat allIssues if chunkResults didn't yield issues
            if (rowNum == 1) {
                Object allIssuesObj = aiResult.get("allIssues");
                if (allIssuesObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> allIssues = (List<Map<String, Object>>) allIssuesObj;
                    for (Map<String, Object> issue : allIssues) {
                        writeIssueRow(sheet.createRow(rowNum), rowNum, issue, "", dataStyle);
                        rowNum++;
                    }
                }
            }

            // Column widths (8 columns now)
            sheet.setColumnWidth(0, 2000);   // 序号
            sheet.setColumnWidth(1, 6000);   // 章节
            sheet.setColumnWidth(2, 3600);   // 问题分类
            sheet.setColumnWidth(3, 4000);   // 规则编码
            sheet.setColumnWidth(4, 14000);  // 审查意见
            sheet.setColumnWidth(5, 12000);  // 判定依据
            sheet.setColumnWidth(6, 3000);   // 是否接受

            workbook.write(out);
            return out.toByteArray();
        }
    }

    public byte[] exportReviewReportDocx(String taskId, Long userId) throws IOException {
        ReviewTask task = requireOwnedTask(taskId, userId);
        if (task.getAiResult() == null) {
            throw new IllegalArgumentException("No review result available for export");
        }

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addDocParagraph(document, "机载文档审查报告", true, 18);
            addDocParagraph(document, "文件名称：" + task.getFileName(), false, 11);
            addDocParagraph(document, "任务编号：" + task.getId(), false, 11);
            addDocParagraph(document, "审查模型：" + task.getSelectedModel(), false, 11);
            addDocParagraph(document, "任务状态：" + task.getStatus(), false, 11);
            addDocParagraph(document, "生成时间：" + LocalDateTime.now(), false, 11);

            Map<String, Object> result = task.getAiResult();
            addDocParagraph(document, "审查概要", true, 14);
            addDocParagraph(document, "问题数：" + result.getOrDefault("totalIssues", 0)
                    + "；检查项数：" + result.getOrDefault("totalCheckResults", 0)
                    + "；失败切片：" + result.getOrDefault("failedChunkCount", 0), false, 11);
            Object manualSummary = result.get("manualReviewSummary");
            if (manualSummary != null) {
                addDocParagraph(document, "人工复核：" + manualSummary, false, 11);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> checks = result.get("allCheckResults") instanceof List<?> list
                    ? (List<Map<String, Object>>) (List<?>) list
                    : List.of();
            addDocParagraph(document, "检查项判定矩阵", true, 14);
            if (checks.isEmpty()) {
                addDocParagraph(document, "当前任务没有检查项判定矩阵。", false, 11);
            } else {
                XWPFTable table = document.createTable(checks.size() + 1, 7);
                String[] headers = {"序号", "章节", "检查项", "系统判定", "人工判定", "理由", "建议"};
                XWPFTableRow header = table.getRow(0);
                for (int i = 0; i < headers.length; i++) {
                    header.getCell(i).setText(headers[i]);
                }
                for (int i = 0; i < checks.size(); i++) {
                    Map<String, Object> check = checks.get(i);
                    XWPFTableRow row = table.getRow(i + 1);
                    row.getCell(0).setText(String.valueOf(i + 1));
                    row.getCell(1).setText(firstNonBlank(strField(check, "sourceTitle"), strField(check, "location")));
                    row.getCell(2).setText(firstNonBlank(strField(check, "check_question"), strField(check, "question")));
                    row.getCell(3).setText(renderCheckStatus(strField(check, "status")));
                    row.getCell(4).setText(renderCheckStatus(strField(check, "manualStatus")));
                    row.getCell(5).setText(firstNonBlank(strField(check, "manualComment"), strField(check, "reason")));
                    row.getCell(6).setText(strField(check, "suggestion"));
                }
            }

            addDocParagraph(document, "审计日志", true, 14);
            List<Map<String, Object>> auditLogs = listAuditLogs(taskId, userId);
            if (auditLogs.isEmpty()) {
                addDocParagraph(document, "暂无人工复核审计记录。", false, 11);
            } else {
                for (Map<String, Object> logEntry : auditLogs) {
                    addDocParagraph(document,
                            logEntry.get("createdAt") + " | " + logEntry.get("action")
                                    + " | " + logEntry.get("targetId")
                                    + " | " + Objects.toString(logEntry.get("comment"), ""),
                            false,
                            10);
                }
            }

            document.write(out);
            return out.toByteArray();
        }
    }

    private static void addDocParagraph(XWPFDocument document, String text, boolean bold, int fontSize) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(bold);
        run.setFontSize(fontSize);
        run.setText(text == null ? "" : text);
    }

    /**
     * Write one issue into a sheet row. Columns:
     *   0 序号 | 1 章节 | 2 问题分类 | 3 规则编码 | 4 审查意见 | 5 判定依据 | 6 是否接受
     *
     * Falls back gracefully when older AI responses don't include category/rule fields.
     */
    private static void writeIssueRow(Row row, int rowNum, Map<String, Object> issue,
                                       String fallbackChapterTitle, CellStyle dataStyle) {
        String location = strField(issue, "location");
        String description = firstNonBlank(strField(issue, "description"), strField(issue, "explanation"));
        String suggestion = strField(issue, "suggestion");
        String rule = strField(issue, "rule");
        String ruleCode = firstNonBlank(strField(issue, "rule_code"), strField(issue, "ruleCode"));
        String category = strField(issue, "category");
        String evidence = strField(issue, "evidence");

        String opinion = description;
        if (!suggestion.isEmpty()) opinion += (opinion.isEmpty() ? "" : "\n") + "建议：" + suggestion;

        String basis = rule;
        if (!evidence.isEmpty()) basis += (basis.isEmpty() ? "" : "\n") + "判定依据：" + evidence;

        cell(row, 0, String.valueOf(rowNum), dataStyle);
        cell(row, 1, location.isEmpty() ? fallbackChapterTitle : location, dataStyle);
        cell(row, 2, category, dataStyle);
        cell(row, 3, ruleCode, dataStyle);
        cell(row, 4, opinion, dataStyle);
        cell(row, 5, basis, dataStyle);
        cell(row, 6, "", dataStyle);
    }

    private static void writeCheckResultRow(Row row, int rowNum, Map<String, Object> check, CellStyle dataStyle) {
        String sourceTitle = firstNonBlank(strField(check, "sourceTitle"), strField(check, "location"));
        String checkCode = firstNonBlank(strField(check, "check_code"), strField(check, "checkCode"));
        String ruleCode = firstNonBlank(strField(check, "rule_code"), strField(check, "ruleCode"));
        String status = renderCheckStatus(strField(check, "status"));
        String question = firstNonBlank(strField(check, "check_question"), strField(check, "question"));
        String reason = strField(check, "reason");
        String evidence = strField(check, "evidence");
        String missing = renderListField(check.get("missing_items"));
        String suggestion = strField(check, "suggestion");
        String confidence = strField(check, "confidence");
        String manual = renderManualReview(check);

        cell(row, 0, String.valueOf(rowNum), dataStyle);
        cell(row, 1, sourceTitle, dataStyle);
        cell(row, 2, checkCode, dataStyle);
        cell(row, 3, ruleCode, dataStyle);
        cell(row, 4, status, dataStyle);
        cell(row, 5, question, dataStyle);
        cell(row, 6, reason, dataStyle);
        cell(row, 7, evidence, dataStyle);
        cell(row, 8, missing, dataStyle);
        cell(row, 9, suggestion, dataStyle);
        cell(row, 10, confidence, dataStyle);
        cell(row, 11, manual, dataStyle);
    }

    private static void cell(Row row, int idx, String value, CellStyle style) {
        Cell c = row.createCell(idx);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static String strField(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? (b == null ? "" : b) : a;
    }

    private static String renderListField(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString)
                    .reduce((a, b) -> a + "；" + b).orElse("");
        }
        return value == null ? "" : value.toString();
    }

    private static String renderManualReview(Map<String, Object> check) {
        String manualStatus = renderCheckStatus(strField(check, "manualStatus"));
        String accepted = "";
        if (check.containsKey("manualAccepted")) {
            accepted = Boolean.TRUE.equals(check.get("manualAccepted")) ? "接受系统意见" : "不接受系统意见";
        }
        String comment = strField(check, "manualComment");
        List<String> parts = new ArrayList<>();
        if (!manualStatus.isBlank()) parts.add("最终判定：" + manualStatus);
        if (!accepted.isBlank()) parts.add(accepted);
        if (!comment.isBlank()) parts.add("备注：" + comment);
        return String.join("\n", parts);
    }

    private static String renderCheckStatus(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return switch (raw.trim()) {
            case "Pass" -> "通过";
            case "Partial" -> "部分通过";
            case "Fail" -> "不通过";
            case "N/A" -> "不适用";
            case "Review" -> "待复核";
            default -> raw.trim();
        };
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

    private Map<String, Object> findCheckResult(List<Map<String, Object>> checks,
                                                String checkCode,
                                                Integer sourceChunk) {
        for (Map<String, Object> check : checks) {
            String code = firstNonBlank(strField(check, "check_code"), strField(check, "checkCode"));
            if (!checkCode.equals(code)) continue;
            if (sourceChunk == null) return check;
            Object chunk = check.get("sourceChunk");
            if (chunk instanceof Number n && n.intValue() == sourceChunk) return check;
            if (chunk != null && String.valueOf(sourceChunk).equals(chunk.toString())) return check;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void syncChunkCheckResult(Map<String, Object> aiResult, Map<String, Object> updated) {
        Object chunkResultsObj = aiResult.get("chunkResults");
        if (!(chunkResultsObj instanceof List<?> chunks)) return;
        String checkCode = firstNonBlank(strField(updated, "check_code"), strField(updated, "checkCode"));
        Object sourceChunk = updated.get("sourceChunk");
        for (Object chunkObj : chunks) {
            if (!(chunkObj instanceof Map<?, ?>)) continue;
            Map<String, Object> chunk = (Map<String, Object>) chunkObj;
            if (sourceChunk != null && !Objects.equals(String.valueOf(sourceChunk), String.valueOf(chunk.get("chunk")))) {
                continue;
            }
            Object resultObj = chunk.get("result");
            if (!(resultObj instanceof Map<?, ?>)) continue;
            Map<String, Object> result = (Map<String, Object>) resultObj;
            Object checksObj = result.get("check_results");
            if (!(checksObj instanceof List<?> checkList)) continue;
            for (Object item : checkList) {
                if (!(item instanceof Map<?, ?>)) continue;
                Map<String, Object> check = (Map<String, Object>) item;
                String candidate = firstNonBlank(strField(check, "check_code"), strField(check, "checkCode"));
                if (checkCode.equals(candidate)) {
                    check.putAll(updated);
                    return;
                }
            }
        }
    }

    private Map<String, Object> buildManualReviewSummary(List<Map<String, Object>> checks) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int reviewed = 0;
        int accepted = 0;
        Map<String, Integer> finalStatusCounts = new LinkedHashMap<>();
        for (String s : ReviewResultSchema.CHECK_STATUS_ENUM) finalStatusCounts.put(s, 0);
        for (Map<String, Object> check : checks) {
            String manualStatus = strField(check, "manualStatus");
            if (!manualStatus.isBlank()) {
                reviewed++;
                finalStatusCounts.merge(normalizeCheckStatus(manualStatus), 1, Integer::sum);
            }
            if (Boolean.TRUE.equals(check.get("manualAccepted"))) {
                accepted++;
            }
        }
        summary.put("reviewed", reviewed);
        summary.put("accepted", accepted);
        summary.put("pending", Math.max(0, checks.size() - reviewed));
        summary.put("finalStatusCounts", finalStatusCounts);
        summary.put("updatedAt", LocalDateTime.now().toString());
        return summary;
    }

    private void updateTaskStatus(ReviewTask task, String status, String failReason) {
        task.setStatus(status);
        task.setFailReason(failReason);
        task.setUpdatedAt(LocalDateTime.now());
        reviewTaskMapper.updateById(task);
    }

    private Map<String, Object> enrichAiResultWithOriginalSources(ReviewTask task) {
        Map<String, Object> aiResult = task.getAiResult();
        if (aiResult == null) return null;
        Map<String, Object> enriched = new LinkedHashMap<>(aiResult);
        if (!enriched.containsKey("originalSources")) {
            enriched.put("originalSources", buildOriginalSources(task));
            enriched.put("sourceTextMode", "original_word_document");
        }
        return enriched;
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

            for (int i = 0; i < chapters.size(); i++) {
                WordParser.Chapter chapter = chapters.get(i);
                String content = chapter.getContent() == null ? "" : chapter.getContent();
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("sourceId", "CHAPTER-" + String.format("%03d", i + 1));
                source.put("type", "original_chapter");
                source.put("title", chapter.getTitle());
                source.put("sectionPath", chapter.getTitle());
                source.put("text", content);
                source.put("textLength", content.length());
                source.put("estimatedTokens", ChunkUtils.estimateTokens(chapter.getFullText()));
                sources.add(source);
            }

            List<ChunkUtils.ChunkResult> chunks = ChunkUtils.chunkByChapters(chapters, maxChunkTokens);
            for (int i = 0; i < chunks.size(); i++) {
                ChunkUtils.ChunkResult chunk = chunks.get(i);
                String content = chunk.getContent() == null ? "" : chunk.getContent();
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("sourceId", "CHUNK-" + String.format("%03d", i + 1));
                source.put("type", "original_chunk");
                source.put("chunk", i + 1);
                source.put("title", chunk.getLabel());
                source.put("sectionPath", chunk.getLabel());
                source.put("text", content);
                source.put("textLength", content.length());
                source.put("estimatedTokens", chunk.getEstimatedTokens());
                sources.add(source);
            }
        } catch (Exception e) {
            log.warn("Failed to build original source view for task {}: {}", task.getId(), e.getMessage());
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
        return dto;
    }
}
