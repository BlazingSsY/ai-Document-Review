package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.ReviewTaskDTO;
import com.aireview.entity.AiModelConfig;
import com.aireview.entity.ReviewTask;
import com.aireview.entity.Rule;
import com.aireview.repository.ReviewTaskMapper;
import com.aireview.util.ChunkUtils;
import com.aireview.util.RuleParser;
import com.aireview.util.WordParser;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final RuleService ruleService;
    private final AiModelService aiModelService;
    private final WebSocketService webSocketService;

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

        // Start async processing
        executeReviewAsync(task.getId());

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

        try {
            // Update status to PROCESSING
            updateTaskStatus(task, ReviewTask.STATUS_PROCESSING, null);
            webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_PROCESSING, "Starting document review...");

            // 1. Parse Word document
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "Parsing document...", 10);
            String documentText = WordParser.parse(task.getFilePath());
            if (documentText == null || documentText.isBlank()) {
                throw new RuntimeException("Document is empty or could not be parsed");
            }
            log.info("Document parsed: {} characters", documentText.length());

            // 2. Build system prompt from scenario rules
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "Loading review rules...", 20);
            List<Rule> rules = ruleService.getRulesByScenarioId(task.getScenarioId());
            if (rules.isEmpty()) {
                throw new RuntimeException("No valid rules found for scenario: " + task.getScenarioId());
            }
            List<String> ruleContents = rules.stream().map(Rule::getContent).toList();
            String systemPrompt = RuleParser.buildSystemPrompt(ruleContents);

            // 3. Get AI model config
            AiModelConfig modelConfig = aiModelService.getEnabledModel(task.getSelectedModel());

            // 4. Chunk the document text
            List<String> chunks = ChunkUtils.chunkText(documentText, maxChunkTokens, overlapTokens);
            log.info("Document split into {} chunks", chunks.size());

            // 5. Process each chunk with retry
            List<Map<String, Object>> chunkResults = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                int chunkNum = i + 1;
                int progress = 30 + (int) ((double) chunkNum / chunks.size() * 60);
                webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                        "Reviewing chunk " + chunkNum + "/" + chunks.size() + "...", progress);

                String chunkContent = "Document Part " + chunkNum + "/" + chunks.size() + ":\n\n" + chunks.get(i);
                String aiResponse = callWithRetry(modelConfig, systemPrompt, chunkContent);

                Map<String, Object> chunkResult = new HashMap<>();
                chunkResult.put("chunk", chunkNum);
                chunkResult.put("totalChunks", chunks.size());
                try {
                    chunkResult.put("result", JSON.parseObject(aiResponse));
                } catch (Exception e) {
                    // AI response might not be valid JSON; store as raw text
                    chunkResult.put("result", aiResponse);
                }
                chunkResults.add(chunkResult);
            }

            // 6. Aggregate results
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "Aggregating results...", 95);
            Map<String, Object> aggregatedResult = aggregateResults(chunkResults);

            // 7. Save result and mark as completed
            task.setAiResult(aggregatedResult);
            updateTaskStatus(task, ReviewTask.STATUS_COMPLETED, null);
            webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_COMPLETED, "Review completed successfully");

            log.info("Review task completed: {}", taskId);

        } catch (Exception e) {
            log.error("Review task failed: {}", taskId, e);
            updateTaskStatus(task, ReviewTask.STATUS_FAILED, e.getMessage());
            webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_FAILED,
                    "Review failed: " + e.getMessage());
        }
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
        throw new RuntimeException("AI model call failed after " + maxRetryAttempts + " attempts", lastException);
    }

    /**
     * Aggregate chunk review results into a unified result.
     */
    private Map<String, Object> aggregateResults(List<Map<String, Object>> chunkResults) {
        Map<String, Object> aggregated = new HashMap<>();
        aggregated.put("totalChunks", chunkResults.size());
        aggregated.put("chunkResults", chunkResults);

        // Try to compute an average score if available
        List<Integer> scores = new ArrayList<>();
        List<Object> allIssues = new ArrayList<>();

        for (Map<String, Object> chunk : chunkResults) {
            Object result = chunk.get("result");
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                Object score = resultMap.get("overall_score");
                if (score instanceof Number) {
                    scores.add(((Number) score).intValue());
                }
                Object issues = resultMap.get("issues");
                if (issues instanceof List) {
                    allIssues.addAll((List<?>) issues);
                }
            }
        }

        if (!scores.isEmpty()) {
            int avgScore = (int) scores.stream().mapToInt(Integer::intValue).average().orElse(0);
            aggregated.put("overallScore", avgScore);
        }
        aggregated.put("totalIssues", allIssues.size());
        aggregated.put("allIssues", allIssues);

        return aggregated;
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
