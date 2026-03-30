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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final RuleService ruleService;
    private final AiModelService aiModelService;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        executeReviewAsync(task.getId());
        return toDTO(task);
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
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "开始文件审查...", 5);

            // 1. Parse Word document into chapters (by Heading 1)
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "正在解析文档结构（按一级标题拆分章节）...", 10);
            List<WordParser.Chapter> chapters = WordParser.parseChapters(task.getFilePath());
            if (chapters.isEmpty() || chapters.stream().allMatch(ch -> ch.getContent().isBlank())) {
                throw new RuntimeException("文档内容为空或无法解析");
            }
            log.info("Document parsed into {} chapter(s)", chapters.size());
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "文档解析完成，共识别 " + chapters.size() + " 个章节", 15);

            // 2. Build system prompt from scenario rules
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "正在加载审查规则...", 20);
            List<Rule> rules = ruleService.getRulesByScenarioId(task.getScenarioId());
            if (rules.isEmpty()) {
                throw new RuntimeException("审查场景中没有找到有效规则，场景ID: " + task.getScenarioId());
            }
            List<String> ruleContents = rules.stream()
                    .map(r -> RuleParser.parseContent(r.getContent(), r.getFileType()))
                    .toList();
            String systemPrompt = RuleParser.buildSystemPrompt(ruleContents);
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "已加载 " + rules.size() + " 条审查规则", 25);

            // 3. Get AI model config
            AiModelConfig modelConfig = aiModelService.getEnabledModel(task.getSelectedModel());

            // 4. Chunk chapters (each chapter = 1 chunk if under maxTokens, otherwise sub-split)
            List<ChunkUtils.ChunkResult> chunks = ChunkUtils.chunkByChapters(chapters, maxChunkTokens);
            log.info("Document split into {} chunk(s) for AI review", chunks.size());
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                    "文档已切分为 " + chunks.size() + " 个片段，开始调用AI审查...", 30);

            // Save chunk debug info to 切片结果.json for debugging
            saveChunkDebugInfo(taskId, task.getFileName(), chapters, chunks);

            // 5. Process each chunk with retry
            List<Map<String, Object>> chunkResults = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                // Check cancellation before each chunk
                if (cancelledTasks.remove(taskId)) {
                    log.info("Review task {} cancelled during processing", taskId);
                    return;
                }

                ChunkUtils.ChunkResult chunk = chunks.get(i);
                int chunkNum = i + 1;
                int progress = 30 + (int) ((double) chunkNum / chunks.size() * 60);
                String progressMsg = "正在审查 [" + chunk.getLabel() + "] (" + chunkNum + "/" + chunks.size()
                        + "，约" + chunk.getEstimatedTokens() + " tokens)...";
                webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, progressMsg, progress);

                String chunkContent = "章节: " + chunk.getLabel()
                        + " (" + chunkNum + "/" + chunks.size() + ")\n\n" + chunk.getContent();
                String aiResponse = callWithRetry(modelConfig, systemPrompt, chunkContent);

                Map<String, Object> chunkResult = new HashMap<>();
                chunkResult.put("chunk", chunkNum);
                chunkResult.put("chapterTitle", chunk.getLabel());
                chunkResult.put("totalChunks", chunks.size());
                chunkResult.put("estimatedTokens", chunk.getEstimatedTokens());
                try {
                    // Strip markdown code block markers if present (```json ... ```)
                    String cleaned = aiResponse.trim();
                    if (cleaned.startsWith("```")) {
                        int firstNewline = cleaned.indexOf('\n');
                        if (firstNewline > 0) {
                            cleaned = cleaned.substring(firstNewline + 1);
                        }
                        if (cleaned.endsWith("```")) {
                            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
                        }
                    }
                    Map<String, Object> parsedResult = objectMapper.readValue(cleaned, Map.class);
                    chunkResult.put("result", parsedResult);
                } catch (JsonProcessingException e) {
                    log.warn("AI响应不是有效JSON，以原始文本存储: {}", e.getMessage());
                    chunkResult.put("result", aiResponse);
                }
                chunkResults.add(chunkResult);

                webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING,
                        "[" + chunk.getLabel() + "] 审查完成", progress);
            }

            // 6. Aggregate results
            webSocketService.sendTaskProgress(taskId, ReviewTask.STATUS_PROCESSING, "正在汇总审查结果...", 95);
            Map<String, Object> aggregatedResult = aggregateResults(chunkResults);

            // 7. Save result and mark as completed
            task.setAiResult(aggregatedResult);
            updateTaskStatus(task, ReviewTask.STATUS_COMPLETED, null);
            webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_COMPLETED,
                    "审查完成，共审查 " + chunks.size() + " 个章节片段");

            log.info("Review task completed: {}", taskId);

        } catch (Exception e) {
            log.error("Review task failed: {}", taskId, e);
            updateTaskStatus(task, ReviewTask.STATUS_FAILED, e.getMessage());
            webSocketService.sendTaskUpdate(taskId, ReviewTask.STATUS_FAILED,
                    "审查失败: " + e.getMessage());
        }
    }

    /**
     * Save chapter/chunk debug information to 切片结果.json in the working directory.
     */
    private void saveChunkDebugInfo(String taskId, String fileName,
                                     List<WordParser.Chapter> chapters,
                                     List<ChunkUtils.ChunkResult> chunks) {
        try {
            Map<String, Object> debug = new LinkedHashMap<>();
            debug.put("taskId", taskId);
            debug.put("fileName", fileName);
            debug.put("timestamp", LocalDateTime.now().toString());
            debug.put("chapterCount", chapters.size());
            debug.put("chunkCount", chunks.size());

            List<Map<String, Object>> chapterList = new ArrayList<>();
            for (int i = 0; i < chapters.size(); i++) {
                WordParser.Chapter ch = chapters.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i + 1);
                m.put("title", ch.getTitle().isBlank() ? "(前言/无标题)" : ch.getTitle());
                m.put("contentLength", ch.getContent().length());
                m.put("estimatedTokens", ChunkUtils.estimateTokens(ch.getFullText()));
                String preview = ch.getContent();
                m.put("contentPreview", preview.substring(0, Math.min(500, preview.length())));
                chapterList.add(m);
            }
            debug.put("chapters", chapterList);

            List<Map<String, Object>> chunkList = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkUtils.ChunkResult chunk = chunks.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i + 1);
                m.put("label", chunk.getLabel());
                m.put("estimatedTokens", chunk.getEstimatedTokens());
                m.put("contentLength", chunk.getContent().length());
                String preview = chunk.getContent();
                m.put("contentPreview", preview.substring(0, Math.min(500, preview.length())));
                chunkList.add(m);
            }
            debug.put("chunks", chunkList);

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(debug);

            // Primary: save to uploads directory (volume-mounted, definitely writable)
            Path uploadsDebugFile = Path.of(documentsDir).getParent().resolve("切片结果.json");
            Files.writeString(uploadsDebugFile, json);
            log.info("Chunk debug info saved to uploads: {}", uploadsDebugFile.toAbsolutePath());

            // Also try bind-mounted project root path
            try {
                Path rootDebugFile = Path.of(System.getProperty("user.dir"), "切片结果.json");
                Files.writeString(rootDebugFile, json);
                log.info("Chunk debug info also saved to app root: {}", rootDebugFile.toAbsolutePath());
            } catch (Exception ex) {
                log.debug("Could not write to app root (bind mount may be unavailable): {}", ex.getMessage());
            }
        } catch (Exception e) {
            log.warn("Failed to save chunk debug info: {}", e.getMessage());
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
        String rootCause = lastException != null ? lastException.getMessage() : "unknown error";
        throw new RuntimeException("AI调用失败(重试" + maxRetryAttempts + "次): " + rootCause, lastException);
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

            // Create header row
            Row headerRow = sheet.createRow(0);
            String[] headers = {"序号", "章节", "审查意见", "判定依据", "是否接受"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Collect all issues with chapter info
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
                                Row row = sheet.createRow(rowNum);

                                // 序号
                                Cell numCell = row.createCell(0);
                                numCell.setCellValue(rowNum);
                                numCell.setCellStyle(dataStyle);

                                // 章节
                                Cell chapterCell = row.createCell(1);
                                chapterCell.setCellValue(chapterTitle);
                                chapterCell.setCellStyle(dataStyle);

                                // 审查意见 (description + suggestion)
                                String description = issue.get("description") != null
                                        ? issue.get("description").toString() : "";
                                String suggestion = issue.get("suggestion") != null
                                        ? issue.get("suggestion").toString() : "";
                                String opinion = description;
                                if (!suggestion.isEmpty()) {
                                    opinion += "\n建议：" + suggestion;
                                }
                                Cell opinionCell = row.createCell(2);
                                opinionCell.setCellValue(opinion);
                                opinionCell.setCellStyle(dataStyle);

                                // 判定依据 (location + rule + severity)
                                String location = issue.get("location") != null
                                        ? issue.get("location").toString() : "";
                                String rule = issue.get("rule") != null
                                        ? issue.get("rule").toString() : "";
                                String severity = issue.get("severity") != null
                                        ? issue.get("severity").toString() : "";
                                StringBuilder basis = new StringBuilder();
                                if (!location.isEmpty()) basis.append("位置：").append(location);
                                if (!rule.isEmpty()) {
                                    if (basis.length() > 0) basis.append("\n");
                                    basis.append("规则：").append(rule);
                                }
                                if (!severity.isEmpty()) {
                                    if (basis.length() > 0) basis.append("\n");
                                    basis.append("严重程度：").append(
                                            "high".equals(severity) ? "高" :
                                            "medium".equals(severity) ? "中" : "低");
                                }
                                Cell basisCell = row.createCell(3);
                                basisCell.setCellValue(basis.toString());
                                basisCell.setCellStyle(dataStyle);

                                // 是否接受 (空白)
                                Cell acceptCell = row.createCell(4);
                                acceptCell.setCellValue("");
                                acceptCell.setCellStyle(dataStyle);

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
                        Row row = sheet.createRow(rowNum);

                        Cell numCell = row.createCell(0);
                        numCell.setCellValue(rowNum);
                        numCell.setCellStyle(dataStyle);

                        Cell chapterCell = row.createCell(1);
                        chapterCell.setCellValue("");
                        chapterCell.setCellStyle(dataStyle);

                        String description = issue.get("description") != null
                                ? issue.get("description").toString() : "";
                        Cell opinionCell = row.createCell(2);
                        opinionCell.setCellValue(description);
                        opinionCell.setCellStyle(dataStyle);

                        String location = issue.get("location") != null
                                ? issue.get("location").toString() : "";
                        Cell basisCell = row.createCell(3);
                        basisCell.setCellValue(location);
                        basisCell.setCellStyle(dataStyle);

                        Cell acceptCell = row.createCell(4);
                        acceptCell.setCellValue("");
                        acceptCell.setCellStyle(dataStyle);

                        rowNum++;
                    }
                }
            }

            // Set column widths
            sheet.setColumnWidth(0, 2000);   // 序号
            sheet.setColumnWidth(1, 6000);   // 章节
            sheet.setColumnWidth(2, 15000);  // 审查意见
            sheet.setColumnWidth(3, 12000);  // 判定依据
            sheet.setColumnWidth(4, 4000);   // 是否接受

            workbook.write(out);
            return out.toByteArray();
        }
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
