package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.ReviewTaskDTO;
import com.aireview.entity.AiModelConfig;
import com.aireview.entity.ReviewTask;
import com.aireview.entity.Rule;
import com.aireview.repository.ReviewTaskMapper;
import com.aireview.util.ChapterReferenceResolver;
import com.aireview.util.ChunkUtils;
import com.aireview.util.RuleDispatcher;
import com.aireview.util.RuleParser;
import com.aireview.util.WordParser;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final RuleService ruleService;
    private final AiModelService aiModelService;
    private final WebSocketService webSocketService;
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
        return toDTO(task);
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
            AiModelConfig modelConfig = aiModelService.getEnabledModel(task.getSelectedModel());
            List<ChunkUtils.ChunkResult> chunks = ChunkUtils.chunkByChapters(chapters, maxChunkTokens);

            List<Map<String, Object>> chunkResults = copyChunkResults(existingResult);
            int totalToRetry = failedChunkNumbers.size();
            int retried = 0;

            for (Integer chunkNumber : failedChunkNumbers) {
                if (chunkNumber == null || chunkNumber < 1 || chunkNumber > chunks.size()) {
                    log.warn("Skip invalid failed chunk number: task={}, chunk={}", taskId, chunkNumber);
                    continue;
                }
                if (cancelledTasks.contains(taskId)) {
                    updateTaskStatus(task, ReviewTask.STATUS_CANCELLED, "User cancelled");
                    webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_CANCELLED, "Task cancelled by user");
                    return;
                }

                int chunkIdx = chunkNumber - 1;
                ChunkUtils.ChunkResult chunk = chunks.get(chunkIdx);
                RuleDispatcher.DispatchResult dispatch = RuleDispatcher.dispatchForChunk(
                        chunk.getLabel(), chunk.getContent(), preparedRules);

                long startNs = System.nanoTime();
                Map<String, Object> replacement;
                try {
                    replacement = reviewSingleChunk(chunkIdx, chunks.size(), chunk, dispatch, chapters, modelConfig);
                    long elapsedMs = elapsedMs(startNs);
                    replacement.put("elapsedMs", elapsedMs);
                    log.info("Failed chunk retry completed: task={}, chunk={}/{}, title='{}', tokens={}, rules={}, elapsedMs={}",
                            taskId, chunkNumber, chunks.size(), chunk.getLabel(), chunk.getEstimatedTokens(),
                            dispatch.getAppliedRuleNames().size(), elapsedMs);
                    webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                            "失败切片重审完成 " + chunkNumber + "/" + chunks.size() + " [" + chunk.getLabel()
                                    + "]，tokens=" + chunk.getEstimatedTokens()
                                    + "，规则=" + dispatch.getAppliedRuleNames().size()
                                    + "，耗时=" + elapsedMs + "ms",
                            10 + (int) ((double) (++retried) / totalToRetry * 80));
                } catch (Exception e) {
                    long elapsedMs = elapsedMs(startNs);
                    replacement = buildFailedChunkResult(chunkIdx, chunks.size(), chunk, dispatch, e, elapsedMs);
                    log.warn("Failed chunk retry still failed: task={}, chunk={}/{}, title='{}', tokens={}, rules={}, elapsedMs={}, reason={}",
                            taskId, chunkNumber, chunks.size(), chunk.getLabel(), chunk.getEstimatedTokens(),
                            dispatch.getAppliedRuleNames().size(), elapsedMs, e.getMessage(), e);
                    webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                            "失败切片重审仍失败 " + chunkNumber + "/" + chunks.size() + " [" + chunk.getLabel()
                                    + "]，tokens=" + chunk.getEstimatedTokens()
                                    + "，规则=" + dispatch.getAppliedRuleNames().size()
                                    + "，耗时=" + elapsedMs + "ms，原因：" + e.getMessage(),
                            10 + (int) ((double) (++retried) / totalToRetry * 80));
                }
                replaceChunkResult(chunkResults, replacement);
            }

            cancelledTasks.remove(taskId);
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

            // 5b. 并行处理所有切片
            //
            // 性能：原来这里是 for 循环逐切片串行调用，每片 AI 耗时 20~60s 线性叠加，
            // 10 片文档要 5~10 分钟。改为并发调度后，N 片可压缩到 ⌈N/并发⌉ 个 AI 调用时长。
            //
            // 设计要点：
            //   1. 结果按原 chunkIdx 回填到固定大小数组，避免并发追加竞态。
            //   2. 进度按完成数原子递增，UI 看到的百分比始终单调上升。
            //   3. 不为每个切片各发一条 "开始" 消息（会刷屏），只发一条 "已启动 N 个并行任务"。
            //   4. 取消信号被消费一次：在所有 future 完成后统一 remove。运行中的 HTTP 调用
            //      会跑完（CompletableFuture.cancel 在 Java 不能中断 HttpClient.send），但
            //      未启动的 chunk task 会在入口处快速返回。
            int totalChunks = chunks.size();
            int effectiveConcurrency = Math.min(chunkConcurrency, totalChunks);
            log.info("Starting parallel chunk review: total={}, concurrency={}", totalChunks, effectiveConcurrency);
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "文档已切分为 " + totalChunks + " 个片段，开始并行审查（并发度 "
                            + effectiveConcurrency + "）...", 30);

            @SuppressWarnings({"unchecked", "rawtypes"})
            final Map<String, Object>[] orderedResults = new Map[totalChunks];
            AtomicInteger completedCount = new AtomicInteger(0);
            List<CompletableFuture<Void>> futures = new ArrayList<>(totalChunks);

            for (int i = 0; i < totalChunks; i++) {
                final int chunkIdx = i;
                final ChunkUtils.ChunkResult chunk = chunks.get(i);
                final RuleDispatcher.DispatchResult dispatch = dispatches.get(i);

                CompletableFuture<Void> fut = CompletableFuture.runAsync(() -> {
                    // 任务已被取消时，未启动的切片立即返回。
                    if (cancelledTasks.contains(taskId)) {
                        return;
                    }
                    long startNs = System.nanoTime();
                    try {
                        Map<String, Object> chunkResult = reviewSingleChunk(
                                chunkIdx, totalChunks, chunk, dispatch, chapters, modelConfig);
                        long elapsedMs = elapsedMs(startNs);
                        chunkResult.put("elapsedMs", elapsedMs);
                        orderedResults[chunkIdx] = chunkResult;

                        int done = completedCount.incrementAndGet();
                        int progress = 30 + (int) ((double) done / totalChunks * 60);
                        log.info("Chunk review completed: task={}, chunk={}/{}, title='{}', tokens={}, rules={}, elapsedMs={}",
                                taskId, chunkIdx + 1, totalChunks, chunk.getLabel(), chunk.getEstimatedTokens(),
                                dispatch.getAppliedRuleNames().size(), elapsedMs);
                        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                                "切片 " + (chunkIdx + 1) + "/" + totalChunks + " [" + chunk.getLabel()
                                        + "] 审查完成，tokens=" + chunk.getEstimatedTokens()
                                        + "，规则=" + dispatch.getAppliedRuleNames().size()
                                        + "，耗时=" + elapsedMs + "ms (" + done + "/" + totalChunks + ")",
                                progress);
                    } catch (Exception e) {
                        long elapsedMs = elapsedMs(startNs);
                        orderedResults[chunkIdx] = buildFailedChunkResult(
                                chunkIdx, totalChunks, chunk, dispatch, e, elapsedMs);

                        int done = completedCount.incrementAndGet();
                        int progress = 30 + (int) ((double) done / totalChunks * 60);
                        log.warn("Chunk review failed but task continues: task={}, chunk={}/{}, title='{}', tokens={}, rules={}, elapsedMs={}, reason={}",
                                taskId, chunkIdx + 1, totalChunks, chunk.getLabel(), chunk.getEstimatedTokens(),
                                dispatch.getAppliedRuleNames().size(), elapsedMs, e.getMessage(), e);
                        webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                                "切片 " + (chunkIdx + 1) + "/" + totalChunks + " [" + chunk.getLabel()
                                        + "] 审查失败，已记录并继续；tokens=" + chunk.getEstimatedTokens()
                                        + "，规则=" + dispatch.getAppliedRuleNames().size()
                                        + "，耗时=" + elapsedMs + "ms，原因：" + e.getMessage(),
                                progress);
                    }
                }, chunkReviewExecutor);

                futures.add(fut);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 所有 future 完成后统一消费取消信号 —— 与原循环 cancelledTasks.remove(taskId) 等价
            if (cancelledTasks.remove(taskId)) {
                log.info("Review task {} cancelled during processing", taskId);
                return;
            }

            // 按原顺序收集结果；被取消跳过的切片位置为 null，过滤掉
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
                    String docResponse = callWithRetry(modelConfig, docSystemPrompt, docUserContent);
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
     * Build a system prompt that contains only the rules picked by the dispatcher for the
     * current chunk. Each rule is preceded by a small metadata header so the model can cite
     * rule_code / severity / standard in its issues.
     */
    private String buildPromptForRules(List<RuleDispatcher.PreparedRule> rulesForChunk) {
        if (rulesForChunk == null || rulesForChunk.isEmpty()) {
            // Nothing matched. Send a minimal global-style prompt so we still get a JSON
            // skeleton back; otherwise the AI may return free text and the chunk shows up
            // as an "unparseable" fallback issue in the UI.
            return RuleParser.buildSystemPrompt(List.of(
                    "本片段未命中任何专项规则。请仅基于通用文档质量要求（错别字、引用一致性、字段完整性等）做轻量审查；"
                            + "若没有问题，请在 passed_items 中说明，并保持 issues 为空数组。"));
        }
        List<String> bodies = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        for (RuleDispatcher.PreparedRule pr : rulesForChunk) {
            bodies.add(pr.getBody());
            headers.add(RuleParser.buildRuleHeader(pr.getRule().getRuleName(), pr.getMetadata()));
        }
        return RuleParser.buildSystemPrompt(bodies, headers);
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
    private Map<String, Object> reviewSingleChunk(int chunkIdx, int totalChunks,
                                                   ChunkUtils.ChunkResult chunk,
                                                   RuleDispatcher.DispatchResult dispatch,
                                                   List<WordParser.Chapter> chapters,
                                                   AiModelConfig modelConfig) throws Exception {
        int chunkNum = chunkIdx + 1;
        String systemPrompt = buildPromptForRules(dispatch.getAppliedRules());

        // Detect cross-references like "见第X章" / "参见 4.5 节" and append
        // the referenced chapters' content as supporting context.
        Set<Integer> refIdx = ChapterReferenceResolver
                .findReferencedChapters(chunk.getContent(), chunk.getLabel(), chapters);
        String supporting = ChapterReferenceResolver.renderSupportingContext(refIdx, chapters);

        String chunkContent = "章节: " + chunk.getLabel()
                + " (" + chunkNum + "/" + totalChunks + ")\n\n" + chunk.getContent()
                + supporting;
        String aiResponse = callWithRetry(modelConfig, systemPrompt, chunkContent);

        Map<String, Object> chunkResult = new HashMap<>();
        chunkResult.put("chunk", chunkNum);
        chunkResult.put("chapterTitle", chunk.getLabel());
        chunkResult.put("totalChunks", totalChunks);
        chunkResult.put("estimatedTokens", chunk.getEstimatedTokens());
        chunkResult.put("appliedRules", dispatch.getAppliedRuleNames());
        Map<String, Object> parsedResult = tryParseAiJson(aiResponse);
        if (parsedResult != null) {
            chunkResult.put("result", parsedResult);
        } else {
            log.warn("AI响应无法解析为JSON，已包装为原始文本issue: 长度={}",
                    aiResponse != null ? aiResponse.length() : 0);
            chunkResult.put("result", buildFallbackResult(chunk.getLabel(), aiResponse));
        }
        return chunkResult;
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
        chunkResult.put("appliedRules", dispatch.getAppliedRuleNames());
        chunkResult.put("failed", true);
        chunkResult.put("retryable", true);
        chunkResult.put("elapsedMs", elapsedMs);
        chunkResult.put("error", error != null ? error.getMessage() : "unknown error");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", "切片审查失败，已保留为可重试切片");
        result.put("issues", new ArrayList<>());
        result.put("passed_items", new ArrayList<>());
        chunkResult.put("result", result);
        return chunkResult;
    }

    private static long elapsedMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    /**
     * Call the AI model with retry logic.
     */
    private String callWithRetry(AiModelConfig config, String systemPrompt,
                                  String userContent) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                return aiModelService.callAiModel(config, systemPrompt, userContent);
            } catch (Exception e) {
                lastException = e;
                log.warn("AI model call attempt {}/{} failed: {}", attempt, maxRetryAttempts, e.getMessage());
                if (attempt < maxRetryAttempts) {
                    try {
                        Thread.sleep(retryIntervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Review interrupted", ie);
                    }
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
        return result;
    }

    /**
     * Aggregate chunk review results into a unified result.
     * Adds severity / category breakdowns so the dashboard and Excel export can
     * surface the extended issue fields produced by the new system prompt schema.
     */
    private Map<String, Object> aggregateResults(List<Map<String, Object>> chunkResults) {
        Map<String, Object> aggregated = new LinkedHashMap<>();
        aggregated.put("totalChunks", chunkResults.size());
        aggregated.put("chunkResults", chunkResults);

        List<Integer> scores = new ArrayList<>();
        List<Object> allIssues = new ArrayList<>();
        List<Map<String, Object>> failedChunks = new ArrayList<>();
        Map<String, Integer> severityCounts = new LinkedHashMap<>();
        severityCounts.put("high", 0);
        severityCounts.put("medium", 0);
        severityCounts.put("low", 0);
        severityCounts.put("unknown", 0);
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();

        for (Map<String, Object> chunk : chunkResults) {
            if (Boolean.TRUE.equals(chunk.get("failed"))) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("chunk", chunk.get("chunk"));
                failed.put("chapterTitle", chunk.get("chapterTitle"));
                failed.put("estimatedTokens", chunk.get("estimatedTokens"));
                failed.put("contentLength", chunk.get("contentLength"));
                failed.put("appliedRulesCount", chunk.get("appliedRules") instanceof List<?> rules ? rules.size() : 0);
                failed.put("elapsedMs", chunk.get("elapsedMs"));
                failed.put("error", chunk.get("error"));
                failedChunks.add(failed);
            }
            Object result = chunk.get("result");
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                Object score = resultMap.get("overall_score");
                if (score instanceof Number) {
                    scores.add(((Number) score).intValue());
                }
                Object issues = resultMap.get("issues");
                if (issues instanceof List<?> issueList) {
                    for (Object item : issueList) {
                        allIssues.add(item);
                        if (item instanceof Map<?, ?> issueMap) {
                            String severity = normalizeSeverity(issueMap.get("severity"));
                            severityCounts.merge(severity, 1, Integer::sum);
                            Object cat = issueMap.get("category");
                            if (cat != null && !String.valueOf(cat).isBlank()) {
                                categoryCounts.merge(String.valueOf(cat), 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }

        if (!scores.isEmpty()) {
            int avgScore = (int) scores.stream().mapToInt(Integer::intValue).average().orElse(0);
            aggregated.put("overallScore", avgScore);
        }
        aggregated.put("totalIssues", allIssues.size());
        aggregated.put("allIssues", allIssues);
        aggregated.put("failedChunkCount", failedChunks.size());
        aggregated.put("failedChunks", failedChunks);
        aggregated.put("severityCounts", severityCounts);
        aggregated.put("categoryCounts", categoryCounts);

        return aggregated;
    }

    private static String normalizeSeverity(Object raw) {
        if (raw == null) return "unknown";
        String s = raw.toString().trim().toLowerCase();
        return switch (s) {
            case "high", "高", "严重", "critical" -> "high";
            case "medium", "中", "mid", "moderate" -> "medium";
            case "low", "低", "minor" -> "low";
            case "" -> "unknown";
            default -> "unknown";
        };
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

            // Create header row — added 严重程度 / 问题分类 / 规则编码 to match the extended
            // issue schema (severity / category / rule_code). 判定依据 now combines the rule
            // name with the evidence excerpt so reviewers can verify each finding inline.
            Row headerRow = sheet.createRow(0);
            String[] headers = {"序号", "章节", "严重程度", "问题分类", "规则编码", "审查意见", "判定依据", "是否接受"};
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
            sheet.setColumnWidth(2, 2800);   // 严重程度
            sheet.setColumnWidth(3, 3600);   // 问题分类
            sheet.setColumnWidth(4, 4000);   // 规则编码
            sheet.setColumnWidth(5, 14000);  // 审查意见
            sheet.setColumnWidth(6, 12000);  // 判定依据
            sheet.setColumnWidth(7, 3000);   // 是否接受

            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Write one issue into a sheet row. Columns:
     *   0 序号 | 1 章节 | 2 严重程度 | 3 问题分类 | 4 规则编码 | 5 审查意见 | 6 判定依据 | 7 是否接受
     *
     * Falls back gracefully when older AI responses don't include severity/category/etc.
     */
    private static void writeIssueRow(Row row, int rowNum, Map<String, Object> issue,
                                       String fallbackChapterTitle, CellStyle dataStyle) {
        String location = strField(issue, "location");
        String description = firstNonBlank(strField(issue, "description"), strField(issue, "explanation"));
        String suggestion = strField(issue, "suggestion");
        String rule = strField(issue, "rule");
        String ruleCode = firstNonBlank(strField(issue, "rule_code"), strField(issue, "ruleCode"));
        String severity = renderSeverity(strField(issue, "severity"));
        String category = strField(issue, "category");
        String evidence = strField(issue, "evidence");

        String opinion = description;
        if (!suggestion.isEmpty()) opinion += (opinion.isEmpty() ? "" : "\n") + "建议：" + suggestion;

        String basis = rule;
        if (!evidence.isEmpty()) basis += (basis.isEmpty() ? "" : "\n") + "判定依据：" + evidence;

        cell(row, 0, String.valueOf(rowNum), dataStyle);
        cell(row, 1, location.isEmpty() ? fallbackChapterTitle : location, dataStyle);
        cell(row, 2, severity, dataStyle);
        cell(row, 3, category, dataStyle);
        cell(row, 4, ruleCode, dataStyle);
        cell(row, 5, opinion, dataStyle);
        cell(row, 6, basis, dataStyle);
        cell(row, 7, "", dataStyle);
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

    private static String renderSeverity(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return switch (raw.trim().toLowerCase()) {
            case "high", "高", "critical", "严重" -> "高";
            case "medium", "中", "moderate" -> "中";
            case "low", "低", "minor" -> "低";
            default -> raw.trim();
        };
    }

    private void updateTaskStatus(ReviewTask task, String status, String failReason) {
        task.setStatus(status);
        task.setFailReason(failReason);
        task.setUpdatedAt(LocalDateTime.now());
        reviewTaskMapper.updateById(task);
    }

    private ReviewTaskDTO toDTO(ReviewTask task) {
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
        return dto;
    }
}
