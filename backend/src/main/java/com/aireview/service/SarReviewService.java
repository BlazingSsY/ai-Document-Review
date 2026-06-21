package com.aireview.service;

import com.aireview.dto.ManualCheckDecisionRequest;
import com.aireview.dto.PageResponse;
import com.aireview.dto.ReviewTaskDTO;
import com.aireview.entity.AiModelConfig;
import com.aireview.entity.SarDocumentBlock;
import com.aireview.entity.SarReviewAuditLog;
import com.aireview.entity.SarReviewTask;
import com.aireview.entity.SarRule;
import com.aireview.entity.SarRuleCheck;
import com.aireview.repository.SarDocumentVectorRepository;
import com.aireview.repository.SarReviewAuditLogMapper;
import com.aireview.repository.SarReviewTaskMapper;
import com.aireview.repository.SarRuleCheckMapper;
import com.aireview.review.ReviewResultSchema;
import com.aireview.review.llm.JsonExtractor;
import com.aireview.util.ChunkUtils;
import com.aireview.util.DocumentSourceMapper;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SarReviewService {

    private final SarReviewTaskMapper sarReviewTaskMapper;
    private final SarReviewAuditLogMapper sarReviewAuditLogMapper;
    private final SarRuleService sarRuleService;
    private final SarRuleCheckMapper sarRuleCheckMapper;
    private final SarDocumentVectorRepository documentVectorRepository;
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
    private SarReviewService self;

    @Autowired
    @Qualifier("sarCheckExecutor")
    private Executor sarCheckExecutor;

    @Value("${review.sar.block-max-chars:1800}")
    private int blockMaxChars;

    @Value("${review.sar.embedding-batch-size:24}")
    private int embeddingBatchSize;

    @Value("${review.sar.recall-top-k:30}")
    private int recallTopK;

    /** 送入 chat 模型审查的证据块上限（rerank 排序后取前 N）。直接决定单次调用的输入 token。 */
    @Value("${review.sar.evidence-max-blocks:10}")
    private int evidenceMaxBlocks;

    /**
     * 分组评估：一次模型调用最多评估多少个检查项（同章节聚合后装箱的箱容量）。
     * 越大则调用越少、越省 token，但单次输出也越大——受 max_tokens 约束，8 较稳妥。
     */
    @Value("${review.sar.max-checks-per-call:8}")
    private int maxChecksPerCall;

    /** 分组调用时，本组共享证据块的上限（按召回分取前 N，去重后）。控制单次输入 token。 */
    @Value("${review.sar.max-evidence-per-call:16}")
    private int maxEvidencePerCall;

    /**
     * 二阶段复核开关。开启后对每条非 Pass 违规各发一次 AI 复判（N 条问题 = N 次额外调用），
     * 仅产出"已确认/待定"标签、对召回无贡献，是 token 消耗大户。默认关闭以控成本。
     */
    @Value("${review.sar.verify.enabled:false}")
    private boolean verifyEnabled;

    @Value("${review.sar.vector-index.enabled:true}")
    private boolean vectorIndexEnabled;

    @Value("${review.sar.vector-index.hnsw-ef-search:100}")
    private int hnswEfSearch;

    @Value("${review.sar.vector-index.binary-candidate-multiplier:4}")
    private int binaryCandidateMultiplier;

    @Value("${review.sar.failed-check-retry-attempts:1}")
    private int failedCheckRetryAttempts;

    @Value("${review.sar.check-concurrency:4}")
    private int checkConcurrency;

    /** 区域级取证：单个检查项命中区域送入模型的块数上限（按阅读顺序取整段，封顶以控 token）。 */
    @Value("${review.sar.region-max-blocks:14}")
    private int regionMaxBlocks;

    /** 路由置信度阈值（best/total 占比）：低于此值视为"定位不确定"，降级 confidence 并优先进入自适应复核。 */
    @Value("${review.sar.route-confidence-threshold:0.45}")
    private double routeConfidenceThreshold;

    /** 自适应复核：仅对低置信/定位不确定的非 Pass 违规复核（而非全量），省 token 又治误报。 */
    @Value("${review.sar.verify.adaptive:true}")
    private boolean verifyAdaptive;

    /** 跨章一致性层：抽取关键实体并核对跨章节矛盾（图表号/术语/参数/类别等）。 */
    @Value("${review.sar.consistency.enabled:true}")
    private boolean consistencyEnabled;

    // ==============================================================================
    //  Controller-facing API (task CRUD + manual decisions + exports).
    //  Mirrors the surface of chunk's {@link ReviewService} so the SAR controller is
    //  a thin parallel of the chunk controller. The differences are purely table-level
    //  (sar_review_tasks / sar_review_audit_logs) and the pipeline that runs underneath
    //  (SAR: vector recall + per-check eval, defined further down in this file).
    // ==============================================================================

    /**
     * Submit a SAR review task: upload the document and start async processing.
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

        SarReviewTask task = new SarReviewTask();
        task.setUserId(userId);
        task.setFileName(originalFilename);
        task.setFilePath(savedPath.toString());
        task.setScenarioId(scenarioId);
        task.setSelectedModel(selectedModel);
        task.setStatus(SarReviewTask.STATUS_PENDING);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        sarReviewTaskMapper.insert(task);

        log.info("SAR review task created: {} for file {} using model {}",
                task.getId(), originalFilename, selectedModel);

        // Use the proxy so @Async actually fires.
        self.executeReviewAsync(task.getId());
        return toDTO(task);
    }

    public ReviewTaskDTO getTask(String taskId, Long userId) {
        SarReviewTask task = sarReviewTaskMapper.selectById(taskId);
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
     * and summary scalars, WITHOUT the heavy {@code originalSources} /
     * {@code chunkResults}. The frontend pulls those lazily via {@link #getSources}.
     */
    public ReviewTaskDTO getTaskLight(String taskId, Long userId) {
        // SQL 层投影掉 originalSources/chunkResults，不读取/反序列化整条大 ai_result。
        SarReviewTask task = sarReviewTaskMapper.selectLightById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only access your own tasks");
        }
        return toLightDetailDTO(task);
    }

    /**
     * The heavy source-tracing payload split out of the detail response:
     * {@code originalSources} (rebuilt from the document) plus the raw
     * {@code chunkResults}. Fetched on demand by the workspace page.
     */
    public Map<String, Object> getSources(String taskId, Long userId) {
        // SQL 层只取 chunkResults（originalSources 由文件重建，仅需 file_path），不反序列化其它内容。
        SarReviewTask task = sarReviewTaskMapper.selectSourcesById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only access your own tasks");
        }
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("originalSources", buildOriginalDocumentSources(task));
        Map<String, Object> aiResult = task.getAiResult();
        // jsonb_build_object 总会带上 key（值可能为 null），故用显式判空而非 getOrDefault。
        Object chunkResults = aiResult == null ? null : aiResult.get("chunkResults");
        sources.put("chunkResults", chunkResults != null ? chunkResults : new ArrayList<>());
        return sources;
    }

    public PageResponse<ReviewTaskDTO> listTasks(Long userId, int page, int size, String status) {
        Page<SarReviewTask> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<SarReviewTask> query = new LambdaQueryWrapper<>();
        query.eq(SarReviewTask::getUserId, userId);
        if (status != null && !status.isBlank()) {
            query.eq(SarReviewTask::getStatus, status.toUpperCase());
        }
        query.orderByDesc(SarReviewTask::getCreatedAt);
        Page<SarReviewTask> result = sarReviewTaskMapper.selectPage(pageParam, query);
        List<ReviewTaskDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    /**
     * Lightweight listing for cross-pipeline merges. Returns recent tasks (sorted
     * desc) up to a limit, with the {@code reviewMode} field populated. Used by the
     * unified workbench list endpoint.
     */
    public List<ReviewTaskDTO> recentTasksForUser(Long userId, int limit) {
        LambdaQueryWrapper<SarReviewTask> query = new LambdaQueryWrapper<>();
        // Exclude the heavy ai_result JSON from the list query (see ReviewService.recentTasksForUser).
        query.select(SarReviewTask.class, info -> !"ai_result".equals(info.getColumn()));
        query.eq(SarReviewTask::getUserId, userId);
        query.orderByDesc(SarReviewTask::getCreatedAt);
        Page<SarReviewTask> pageParam = new Page<>(1, Math.max(1, limit));
        return sarReviewTaskMapper.selectPage(pageParam, query).getRecords()
                .stream().map(this::toListDTO).toList();
    }

    /** Lightweight DTO for the task list: metadata + cached problem count, never ai_result. */
    private ReviewTaskDTO toListDTO(SarReviewTask task) {
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
        dto.setReviewMode("SAR");
        return dto;
    }

    public Map<String, Object> getStats(Long userId) {
        LambdaQueryWrapper<SarReviewTask> baseQuery = new LambdaQueryWrapper<>();
        baseQuery.eq(SarReviewTask::getUserId, userId);
        long total = sarReviewTaskMapper.selectCount(baseQuery);
        long completed = sarReviewTaskMapper.selectCount(
                new LambdaQueryWrapper<SarReviewTask>()
                        .eq(SarReviewTask::getUserId, userId)
                        .eq(SarReviewTask::getStatus, SarReviewTask.STATUS_COMPLETED));
        long processing = sarReviewTaskMapper.selectCount(
                new LambdaQueryWrapper<SarReviewTask>()
                        .eq(SarReviewTask::getUserId, userId)
                        .eq(SarReviewTask::getStatus, SarReviewTask.STATUS_PROCESSING));
        long failed = sarReviewTaskMapper.selectCount(
                new LambdaQueryWrapper<SarReviewTask>()
                        .eq(SarReviewTask::getUserId, userId)
                        .eq(SarReviewTask::getStatus, SarReviewTask.STATUS_FAILED));
        long todayCount = sarReviewTaskMapper.selectCount(
                new LambdaQueryWrapper<SarReviewTask>()
                        .eq(SarReviewTask::getUserId, userId)
                        .ge(SarReviewTask::getCreatedAt, LocalDateTime.now().toLocalDate().atStartOfDay()));
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("completed", completed);
        stats.put("processing", processing);
        stats.put("failed", failed);
        stats.put("todayCount", todayCount);
        return stats;
    }

    public void deleteTask(String taskId, Long userId) {
        SarReviewTask task = requireOwnedTask(taskId, userId);
        if (SarReviewTask.STATUS_PROCESSING.equals(task.getStatus())) {
            throw new IllegalArgumentException("Cannot delete a task that is currently processing. Cancel it first.");
        }
        sarReviewTaskMapper.deleteById(taskId);
        log.info("SAR review task deleted: {}", taskId);
    }

    public void cancelTask(String taskId, Long userId) {
        SarReviewTask task = requireOwnedTask(taskId, userId);
        String status = task.getStatus();
        if (!SarReviewTask.STATUS_PENDING.equals(status) && !SarReviewTask.STATUS_PROCESSING.equals(status)) {
            throw new IllegalArgumentException("Only pending or processing tasks can be cancelled");
        }
        cancelledTasks.add(taskId);
        updateTaskStatus(task, SarReviewTask.STATUS_CANCELLED, "User cancelled");
        webSocketService.sendTaskUpdate(taskId, SarReviewTask.STATUS_CANCELLED, "Task cancelled by user");
        log.info("SAR review task cancelled: {}", taskId);
    }

    public ReviewTaskDTO reReview(String taskId, Long userId) {
        SarReviewTask original = requireOwnedTask(taskId, userId);
        SarReviewTask task = new SarReviewTask();
        task.setUserId(userId);
        task.setFileName(original.getFileName());
        task.setFilePath(original.getFilePath());
        task.setScenarioId(original.getScenarioId());
        task.setSelectedModel(original.getSelectedModel());
        task.setStatus(SarReviewTask.STATUS_PENDING);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        sarReviewTaskMapper.insert(task);
        log.info("SAR re-review task created: {} from original: {}", task.getId(), taskId);
        self.executeReviewAsync(task.getId());
        return toDTO(task, true);
    }

    /**
     * SAR retry-failed-checks: re-runs the full task with the same scenario/model.
     *
     * <p>Why a full re-run (not selective retry like chunk): SAR operates per atomic
     * check, and a single check costs only one AI call. Selective retry would require
     * pickle-loading individual check states. Re-running the whole document is simpler
     * and only marginally more expensive — the vector index is reused; recall is
     * deterministic; only the LLM eval pass repeats.
     */
    public ReviewTaskDTO retryFailedChunks(String taskId, Long userId) {
        SarReviewTask task = requireOwnedTask(taskId, userId);
        if (SarReviewTask.STATUS_PROCESSING.equals(task.getStatus())) {
            throw new IllegalArgumentException("Task is currently processing");
        }
        updateTaskStatus(task, SarReviewTask.STATUS_PROCESSING, null);
        webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                "SAR 重新审查所有检查项...", 5);
        self.executeReviewAsync(taskId);
        return toDTO(task);
    }

    public ReviewTaskDTO updateManualCheckDecision(String taskId, Long userId,
                                                    ManualCheckDecisionRequest request) {
        SarReviewTask task = requireOwnedTask(taskId, userId);
        if (task.getAiResult() == null) {
            throw new IllegalArgumentException("No review result available for manual decision");
        }
        boolean hasFindingId = request.getFindingId() != null && !request.getFindingId().isBlank();
        if (!hasFindingId && (request.getCheckCode() == null || request.getCheckCode().isBlank())) {
            throw new IllegalArgumentException("findingId or checkCode is required");
        }

        Map<String, Object> aiResult = new LinkedHashMap<>(task.getAiResult());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allCheckResults = aiResult.get("allCheckResults") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : new ArrayList<>();
        Map<String, Object> target = ReviewExportUtil.findCheckResult(
                allCheckResults, request.getFindingId(), request.getCheckCode(), request.getSourceChunk());
        if (target == null) {
            throw new IllegalArgumentException("Check result not found: "
                    + firstNonBlank(request.getFindingId(), request.getCheckCode()));
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
        applyProblemSummary(aiResult, allCheckResults);
        task.setAiResult(aiResult);
        task.setProblemCount(ReviewExportUtil.computeProblemCount(aiResult));
        task.setUpdatedAt(LocalDateTime.now());
        sarReviewTaskMapper.updateById(task);

        SarReviewAuditLog audit = new SarReviewAuditLog();
        audit.setTaskId(taskId);
        audit.setUserId(userId);
        audit.setAction("manual_check_decision");
        audit.setTargetType("check_result");
        audit.setTargetId(String.valueOf(target.get("check_code")));
        audit.setBeforeValue(before);
        audit.setAfterValue(new LinkedHashMap<>(target));
        audit.setComment(request.getComment());
        audit.setCreatedAt(LocalDateTime.now());
        sarReviewAuditLogMapper.insert(audit);

        return toDTO(task, true);
    }

    public List<Map<String, Object>> listAuditLogs(String taskId, Long userId) {
        requireOwnedTask(taskId, userId);
        List<SarReviewAuditLog> logs = sarReviewAuditLogMapper.findByTaskId(taskId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SarReviewAuditLog logEntry : logs) {
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
        SarReviewTask task = requireOwnedTask(taskId, userId);
        return ReviewExportUtil.toExcel(enrichAiResult(task, false));
    }

    public byte[] exportReviewReportDocx(String taskId, Long userId) throws IOException {
        SarReviewTask task = requireOwnedTask(taskId, userId);
        return ReviewExportUtil.toReportDocx(task.getFileName(), task.getId(),
                task.getSelectedModel(), task.getStatus(), enrichAiResult(task, false),
                listAuditLogs(taskId, userId));
    }

    private SarReviewTask requireOwnedTask(String taskId, Long userId) {
        SarReviewTask task = sarReviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only access your own tasks");
        }
        return task;
    }

    private ReviewTaskDTO toDTO(SarReviewTask task) {
        return toDTO(task, false);
    }

    private ReviewTaskDTO toDTO(SarReviewTask task, boolean includeOriginalDocument) {
        ReviewTaskDTO dto = new ReviewTaskDTO();
        dto.setId(task.getId());
        dto.setUserId(task.getUserId());
        dto.setFileName(task.getFileName());
        dto.setScenarioId(task.getScenarioId());
        dto.setSelectedModel(task.getSelectedModel());
        dto.setStatus(task.getStatus());
        dto.setAiResult(enrichAiResult(task, includeOriginalDocument));
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setFailReason(task.getFailReason());
        dto.setProblemCount(task.getProblemCount());
        dto.setProgress(webSocketService.getProgress(task.getId()));
        dto.setReviewMode("SAR");
        return dto;
    }

    private Map<String, Object> enrichAiResult(SarReviewTask task, boolean includeOriginalDocument) {
        if (task.getAiResult() == null) return null;

        Map<String, Object> enriched = new LinkedHashMap<>(task.getAiResult());
        List<Map<String, Object>> checks = copyCheckResults(enriched.get("allCheckResults"));
        if (!checks.isEmpty()) {
            if (includeOriginalDocument) {
                enrichCheckMetadata(task.getScenarioId(), checks);
            }
            enriched.put("allCheckResults", checks);
            applyProblemSummary(enriched, checks);
        }

        if (includeOriginalDocument) {
            List<Map<String, Object>> originalDocument = buildOriginalDocumentSources(task);
            if (!originalDocument.isEmpty()) {
                enriched.put("originalSources", originalDocument);
                enriched.put("sourceTextMode", "structured_json_markdown_review_html_display");
            }
        }
        return enriched;
    }

    /** Detail DTO without the heavy source payload (see {@link #enrichAiResultLight}). */
    private ReviewTaskDTO toLightDetailDTO(SarReviewTask task) {
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
        dto.setReviewMode("SAR");
        return dto;
    }

    /**
     * Like {@link #enrichAiResult} (detail mode) but strips the heavy
     * {@code originalSources} / {@code chunkResults}. The check matrix still gets
     * its rule metadata and problem summary so the first paint is fully usable.
     */
    private Map<String, Object> enrichAiResultLight(SarReviewTask task) {
        if (task.getAiResult() == null) return null;
        Map<String, Object> enriched = new LinkedHashMap<>(task.getAiResult());
        List<Map<String, Object>> checks = copyCheckResults(enriched.get("allCheckResults"));
        if (!checks.isEmpty()) {
            enrichCheckMetadata(task.getScenarioId(), checks);
            enriched.put("allCheckResults", checks);
            applyProblemSummary(enriched, checks);
        }
        enriched.remove("originalSources");
        enriched.remove("chunkResults");
        return enriched;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> copyCheckResults(Object rawChecks) {
        if (!(rawChecks instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                checks.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return checks;
    }

    private void enrichCheckMetadata(Long scenarioId, List<Map<String, Object>> checks) {
        if (scenarioId == null || checks.isEmpty()) return;
        List<SarRule> rules = sarRuleService.getRulesByScenarioId(scenarioId);
        Map<String, String> ruleNamesByCode = new HashMap<>();
        Map<String, String> ruleDescriptionsByCode = new HashMap<>();
        Map<Long, SarRule> rulesById = new HashMap<>();
        for (SarRule rule : rules) {
            if (rule.getId() != null) {
                rulesById.put(rule.getId(), rule);
            }
            if (rule.getRuleCode() != null && !rule.getRuleCode().isBlank()) {
                ruleNamesByCode.put(rule.getRuleCode(), rule.getRuleName());
                ruleDescriptionsByCode.put(rule.getRuleCode(), rule.getDescription());
            }
        }
        List<Long> ruleIds = rules.stream()
                .map(SarRule::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<String, SarRuleCheck> metadataByCompositeKey = new HashMap<>();
        Map<String, SarRuleCheck> metadataByCheckCode = new HashMap<>();
        if (!ruleIds.isEmpty()) {
            for (SarRuleCheck ruleCheck : sarRuleCheckMapper.findActiveByRuleIds(ruleIds)) {
                SarRule rule = rulesById.get(ruleCheck.getRuleId());
                String ruleCode = rule == null ? "" : Objects.toString(rule.getRuleCode(), "");
                String checkCode = Objects.toString(ruleCheck.getCheckCode(), "");
                if (checkCode.isBlank()) continue;
                metadataByCompositeKey.put(ruleCode + "\u0000" + checkCode, ruleCheck);
                metadataByCheckCode.putIfAbsent(checkCode, ruleCheck);
            }
        }
        for (Map<String, Object> check : checks) {
            String currentName = firstNonBlank(
                    Objects.toString(check.get("ruleName"), ""),
                    Objects.toString(check.get("rule_name"), ""));
            String ruleCode = Objects.toString(check.get("rule_code"), "");
            String checkCode = firstNonBlank(
                    Objects.toString(check.get("check_code"), ""),
                    Objects.toString(check.get("checkCode"), ""));
            SarRuleCheck ruleCheck = metadataByCompositeKey.get(ruleCode + "\u0000" + checkCode);
            if (ruleCheck == null) ruleCheck = metadataByCheckCode.get(checkCode);
            if (currentName.isBlank()) {
                String ruleName = ruleNamesByCode.get(ruleCode);
                if (ruleName != null && !ruleName.isBlank()) {
                    check.put("ruleName", ruleName);
                }
            }
            String ruleDescription = ruleDescriptionsByCode.get(ruleCode);
            if (ruleDescription != null && !ruleDescription.isBlank()) {
                check.putIfAbsent("ruleDescription", ruleDescription);
            }
            if (ruleCheck != null) {
                check.putIfAbsent("passCriteria", Objects.toString(ruleCheck.getPassCriteria(), ""));
                check.putIfAbsent("check_question", Objects.toString(ruleCheck.getQuestion(), ""));
            }
        }
    }

    private List<Map<String, Object>> buildOriginalDocumentSources(SarReviewTask task) {
        List<Map<String, Object>> sources = new ArrayList<>();
        if (task.getFilePath() == null || task.getFilePath().isBlank()) return sources;
        try {
            List<WordParser.Chapter> rawChapters = WordParser.parseChapters(task.getFilePath());
            int firstRealIdx = ChunkUtils.findFirstRealChapterIndex(rawChapters);
            List<WordParser.Chapter> chapters = firstRealIdx > 0
                    ? new ArrayList<>(rawChapters.subList(firstRealIdx, rawChapters.size()))
                    : rawChapters;
            for (int i = 0; i < chapters.size(); i++) {
                sources.add(toOriginalSource(chapters.get(i), i + 1));
            }
        } catch (Exception e) {
            log.warn("Failed to rebuild original document view for SAR task {}: {}",
                    task.getId(), e.getMessage());
        }
        return sources;
    }

    private void applyProblemSummary(Map<String, Object> aiResult,
                                     List<Map<String, Object>> checks) {
        List<Map<String, Object>> problems = checks.stream()
                .filter(this::isProblemCheck)
                .toList();
        aiResult.put("totalIssues", problems.size());
        aiResult.put("allIssues", new ArrayList<>(problems));
    }

    private boolean isProblemCheck(Map<String, Object> check) {
        String status = firstNonBlank(
                Objects.toString(check.get("manualStatus"), ""),
                Objects.toString(check.get("status"), "Review"));
        String normalized = normalizeCheckStatus(status);
        // 三级判定下只有 Pass 不算问题；Fail / Review 都需呈现。
        return !"Pass".equals(normalized);
    }

    // ============================ Pipeline ============================
    // Everything below is the AI execution pipeline. Called from
    // executeReviewAsync(taskId) only.

    /**
     * Parses and vectorizes the uploaded document before any checklist item is sent
     * to the chat model. This makes document preparation an explicit pipeline stage.
     */
    public PreparedDocument prepareDocumentVectors(SarReviewTask task) throws Exception {
        String taskId = task.getId();
        webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                "SAR: 正在解析上传文档...", 8);

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
            throw new IllegalStateException("SAR review requires an enabled embedding model");
        }

        webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                "SAR: 正在构建原文分块...", 15);
        List<SarDocumentBlock> blocks = buildBlocks(taskId, chapters);
        if (blocks.isEmpty()) {
            throw new IllegalStateException("No source blocks were produced from the uploaded document");
        }
        documentVectorRepository.deleteByTaskId(taskId);

        webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                "SAR: 正在向量化 " + blocks.size() + " 个原文分块...", 25);
        embedBlocks(blocks, embeddingModel);
        documentVectorRepository.saveAll(blocks);
        int embeddingDimension = blocks.get(0).getEmbeddingDimension();
        String vectorIndexStrategy = vectorIndexEnabled
                ? documentVectorRepository.ensureHnswIndex(embeddingModel.getModelName(), embeddingDimension)
                : "exact";
        webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                "SAR: 文档向量化完成，pgvector " + vectorIndexStrategy
                        + " 索引已就绪（维度 " + embeddingDimension + "）", 34);

        return new PreparedDocument(List.copyOf(blocks), List.copyOf(chapters), embeddingModel,
                embeddingDimension, vectorIndexStrategy);
    }

    public void executeReview(SarReviewTask task, String runStamp) throws Exception {
        executeReview(task, runStamp, prepareDocumentVectors(task));
    }

    /**
     * Public async entry for the SAR pipeline. Called by {@code SarReviewController}
     * (or by the chunk-side controller via dispatch). Loads the task from
     * {@code sar_review_tasks} and runs through {@link #prepareDocumentVectors} →
     * {@link #executeReview}.
     */
    @Async("reviewTaskExecutor")
    public void executeReviewAsync(String taskId) {
        SarReviewTask task = sarReviewTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("SAR task not found for async execution: {}", taskId);
            return;
        }
        String runStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        try {
            updateTaskStatus(task, SarReviewTask.STATUS_PROCESSING, null);
            webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                    "SAR 审查启动，开始文档向量化...", 5);
            executeReview(task, runStamp);
        } catch (Exception e) {
            log.error("SAR review task failed: {}", taskId, e);
            updateTaskStatus(task, SarReviewTask.STATUS_FAILED, e.getMessage());
            webSocketService.sendTaskUpdate(taskId, SarReviewTask.STATUS_FAILED,
                    "审查失败: " + e.getMessage());
        }
    }

    public void executeReview(SarReviewTask task, String runStamp,
                              PreparedDocument preparedDocument) throws Exception {
        String taskId = task.getId();
        AiModelConfig chatModel = aiModelService.getEnabledModel(task.getSelectedModel());
        AiModelConfig embeddingModel = preparedDocument.embeddingModel();
        AiModelConfig rerankerModel =
                aiModelService.getFirstEnabledModelByType(AiModelService.MODEL_TYPE_RERANKER);
        List<SarDocumentBlock> blocks = preparedDocument.blocks();
        List<WordParser.Chapter> chapters = preparedDocument.chapters();

        webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                "SAR: 正在加载检查规则...", 38);
        List<CheckPlan> plans = buildCheckPlans(task.getScenarioId());
        if (plans.isEmpty()) {
            throw new RuntimeException("No active rule checks found for scenario: " + task.getScenarioId());
        }

        List<IndexedPlan> indexedPlans = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            indexedPlans.add(new IndexedPlan(i, plans.get(i)));
        }

        // 没有这条消息时，进度会从 "加载检查规则 38%" 一直停到第一个检查项跑完才跳到
        // "首轮审查 1/N"——召回优先下每个检查项要做召回+多批模型调用，慢模型上首个返回可能
        // 要数十秒，用户会误以为卡死。这里先告知总量与并发，给出明确等待预期。
        webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                "已加载 " + plans.size() + " 个检查项，开始逐项调用模型审查（并发 "
                        + Math.max(1, checkConcurrency) + "，首批结果返回前请稍候）...", 39);

        List<CheckAttempt> finalAttempts = new ArrayList<>(runCheckPass(
                taskId, indexedPlans, plans.size(), chatModel, embeddingModel, rerankerModel,
                blocks, 0, "首轮审查", 40, 82));
        int initialFailedCount = (int) finalAttempts.stream().filter(CheckAttempt::failed).count();
        int retriedCheckCount = 0;

        for (int retry = 1; retry <= Math.max(0, failedCheckRetryAttempts); retry++) {
            List<IndexedPlan> failedPlans = finalAttempts.stream()
                    .filter(CheckAttempt::failed)
                    .map(attempt -> new IndexedPlan(attempt.index(), plans.get(attempt.index())))
                    .toList();
            if (failedPlans.isEmpty()) break;

            retriedCheckCount += failedPlans.size();
            webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                    "首轮审查完成，开始补审 " + failedPlans.size() + " 个失败项"
                            + "（第 " + retry + " 次）", 84);
            List<CheckAttempt> retryAttempts = runCheckPass(
                    taskId, failedPlans, plans.size(), chatModel, embeddingModel, rerankerModel,
                    blocks, retry, "失败项补审", 84, 92);
            for (CheckAttempt attempt : retryAttempts) {
                finalAttempts.set(attempt.index(), attempt);
            }
        }

        List<Map<String, Object>> allCheckResults = new ArrayList<>();
        List<Map<String, Object>> chunkResults = new ArrayList<>();
        Map<String, Integer> statusCounts = new TreeMap<>();
        int remainingFailedCount = 0;
        int rerankedCount = 0;

        int findingCount = 0;
        for (int i = 0; i < plans.size(); i++) {
            CheckPlan plan = plans.get(i);
            CheckAttempt attempt = finalAttempts.get(i);
            if (attempt.reranked()) rerankedCount++;
            // 一个检查项现在可能展开成多行（每处违规一行）；失败则单行兜底。
            List<Map<String, Object>> rows;
            if (attempt.failed()) {
                remainingFailedCount++;
                rows = List.of(buildFailedCheck(plan, attempt));
            } else {
                rows = attempt.results();
            }
            for (Map<String, Object> check : rows) {
                allCheckResults.add(check);
                statusCounts.merge(String.valueOf(check.get("status")), 1, Integer::sum);
            }
            findingCount += rows.size();

            // chunkResults 仍按检查项粒度保留（用于来源追溯/导出），把该检查的全部行塞进 check_results。
            Map<String, Object> firstRow = rows.isEmpty() ? Map.of() : rows.get(0);
            Map<String, Object> chunkResult = new LinkedHashMap<>();
            chunkResult.put("chunk", i + 1);
            chunkResult.put("chapterTitle", plan.checkQuestion());
            chunkResult.put("totalChunks", plans.size());
            chunkResult.put("sourceRefs", firstRow.get("sourceRefs"));
            chunkResult.put("appliedRules", List.of(plan.ruleName()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("summary", String.valueOf(firstRow.getOrDefault("reason", "")));
            result.put("issues", new ArrayList<>());
            result.put("passed_items", new ArrayList<>());
            result.put("check_results", new ArrayList<>(rows));
            chunkResult.put("result", result);
            chunkResults.add(chunkResult);
        }

        // 阶段三：两阶段复核——对每条非 Pass 违规独立复判，标注 verifyStatus（不删除、不降级为 Pass）。
        int verifiedCount = 0;
        int confirmedCount = 0;
        if (verifyEnabled) {
            List<Map<String, Object>> toVerify = allCheckResults.stream()
                    .filter(r -> !"Pass".equals(normalizeCheckStatus(r.get("status"))))
                    .filter(r -> !verifyAdaptive || isUncertain(r))
                    .toList();
            if (!toVerify.isEmpty()) {
                webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                        "开始两阶段复核 " + toVerify.size() + " 条疑似问题...", 93);
                Map<String, Map<String, Object>> verdicts =
                        runVerifyPass(taskId, toVerify, chatModel);
                for (Map.Entry<String, Map<String, Object>> e : verdicts.entrySet()) {
                    verifiedCount++;
                    if ("CONFIRMED".equals(e.getValue().get("verifyStatus"))) confirmedCount++;
                }
            }
        }

        // 阶段四：跨章一致性核对——抽取关键实体、发现跨章节矛盾（两条现有管线都缺这层）。
        int consistencyFindings = 0;
        if (consistencyEnabled && chapters.size() >= 2) {
            webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                    "开始跨章一致性核对...", 96);
            List<Map<String, Object>> consistencyRows = runConsistencyPass(taskId, chapters, chatModel);
            for (Map<String, Object> row : consistencyRows) {
                allCheckResults.add(row);
                statusCounts.merge(String.valueOf(row.get("status")), 1, Integer::sum);
                findingCount++;
                consistencyFindings++;
            }
        }

        Map<String, Object> aiResult = new LinkedHashMap<>();
        aiResult.put("reviewMode", "sar");
        aiResult.put("runStamp", runStamp);
        aiResult.put("modelName", chatModel.getModelName());
        aiResult.put("embeddingModel", embeddingModel.getModelName());
        aiResult.put("rerankerModel", rerankerModel != null ? rerankerModel.getModelName() : null);
        aiResult.put("totalChunks", blocks.size());
        aiResult.put("chunkResults", chunkResults);
        aiResult.put("totalCheckResults", allCheckResults.size());
        aiResult.put("totalFindings", findingCount);
        aiResult.put("allCheckResults", allCheckResults);
        aiResult.put("checkStatusCounts", statusCounts);
        applyProblemSummary(aiResult, allCheckResults);
        aiResult.put("originalSources", java.util.stream.IntStream.range(0, chapters.size())
                .mapToObj(i -> toOriginalSource(chapters.get(i), i + 1))
                .toList());
        aiResult.put("sourceTextMode", "structured_json_markdown_review_html_display");
        Map<String, Object> retrievalStats = new LinkedHashMap<>();
        retrievalStats.put("engine", "pgvector");
        retrievalStats.put("indexStrategy", preparedDocument.vectorIndexStrategy());
        retrievalStats.put("embeddingDimension", preparedDocument.embeddingDimension());
        retrievalStats.put("blockCount", blocks.size());
        retrievalStats.put("checkCount", plans.size());
        retrievalStats.put("checkConcurrency", checkConcurrency);
        retrievalStats.put("recallTopK", recallTopK);
        retrievalStats.put("hnswEfSearch", hnswEfSearch);
        retrievalStats.put("rerankedChecks", rerankedCount);
        retrievalStats.put("initialFailedChecks", initialFailedCount);
        retrievalStats.put("retriedChecks", retriedCheckCount);
        retrievalStats.put("recoveredChecks", initialFailedCount - remainingFailedCount);
        retrievalStats.put("remainingFailedChecks", remainingFailedCount);
        retrievalStats.put("evidenceMaxBlocks", evidenceMaxBlocks);
        retrievalStats.put("totalFindings", findingCount);
        retrievalStats.put("verifyEnabled", verifyEnabled);
        retrievalStats.put("verifyAdaptive", verifyAdaptive);
        retrievalStats.put("verifiedFindings", verifiedCount);
        retrievalStats.put("confirmedFindings", confirmedCount);
        retrievalStats.put("regionMaxBlocks", regionMaxBlocks);
        retrievalStats.put("consistencyEnabled", consistencyEnabled);
        retrievalStats.put("consistencyFindings", consistencyFindings);
        aiResult.put("retrievalStats", retrievalStats);

        task.setAiResult(aiResult);
        updateTaskStatus(task, SarReviewTask.STATUS_COMPLETED, null);
        webSocketService.sendTaskUpdate(taskId, SarReviewTask.STATUS_COMPLETED,
                remainingFailedCount == 0
                        ? "SAR 审查完成：" + plans.size() + " 个检查项"
                        : "SAR 审查完成：" + plans.size() + " 个检查项，其中 "
                                + remainingFailedCount + " 个补审后仍需人工复核");
    }

    /**
     * 分组评估的审查轮：
     * <ol>
     *   <li>阶段 1（并行，无 chat token）：每个检查项各自向量召回 + rerank 取证据；</li>
     *   <li>按"首要证据块所在章节"把检查项分组，再按 {@code maxChecksPerCall} 装箱；</li>
     *   <li>阶段 2（并行）：每个箱一次模型调用，共享去重后的证据、一次评估多项；</li>
     *   <li>按 check_code 回填每项结果；模型漏返回的检查项标失败，交补审轮兜底。</li>
     * </ol>
     * 相比"每检查项一次调用"，同一段原文只发一次、多检查项共用，token 大幅下降。
     * 返回值仍与 indexedPlans 顺序对齐（每检查项一个 CheckAttempt），上层装配/补审逻辑不变。
     */
    private List<CheckAttempt> runCheckPass(
            String taskId,
            List<IndexedPlan> indexedPlans,
            int totalPlanCount,
            AiModelConfig chatModel,
            AiModelConfig embeddingModel,
            AiModelConfig rerankerModel,
            List<SarDocumentBlock> blocks,
            int retryNumber,
            String phaseName,
            int progressStart,
            int progressEnd) {
        if (indexedPlans.isEmpty()) return List.of();

        // 结构化文档索引（按章节/section_path 聚合块、保留阅读顺序），供三路路由与区域级取证。
        SectionIndex sectionIndex = buildSectionIndex(blocks);

        // ---- 阶段 1：三路并联路由（结构 + 词法 + 语义）为每个检查项定位"预期区域"，
        //      取整段区域作为证据；仅嵌入/pgvector，不耗 chat token。----
        Map<Integer, RetrievedCheck> retrieved = new ConcurrentHashMap<>();
        runBounded(indexedPlans, indexed -> {
            try {
                retrieved.put(indexed.index(),
                        routeCheck(taskId, indexed, embeddingModel, rerankerModel, sectionIndex));
            } catch (Exception e) {
                // 路由失败不致命：放空证据，仍进入评估（模型按"无证据/缺失"判定）。
                retrieved.put(indexed.index(), new RetrievedCheck(indexed, List.of(), false, 0.0));
                log.warn("SAR routing failed: task={}, check={}, err={}",
                        taskId, indexed.plan().checkCode(), rootErrorMessage(e));
            }
        });

        // ---- 按"路由命中的区域"分组装箱：同区域的检查项合并到一次调用，共享整段区域证据 ----
        List<CallBin> bins = planCallBins(indexedPlans, retrieved);
        log.info("SAR hybrid pass: task={}, checks={}, regions(bins)={}, phase={}",
                taskId, indexedPlans.size(), bins.size(), phaseName);

        // ---- 阶段 2：并行执行分组调用（一个区域 = 一次模型调用，区域级取证 + 清单式缺失判定）----
        Map<Integer, CheckAttempt> attempts = new ConcurrentHashMap<>();
        AtomicInteger doneChecks = new AtomicInteger();
        int totalChecks = indexedPlans.size();
        runBounded(bins, bin -> {
            try {
                attempts.putAll(reviewGroup(taskId, bin, chatModel, retryNumber, totalPlanCount));
            } finally {
                int done = doneChecks.addAndGet(bin.members().size());
                int progress = progressStart + (int) Math.round(
                        (double) done / totalChecks * (progressEnd - progressStart));
                try {
                    webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                            "SAR " + phaseName + "：" + done + "/" + totalChecks, progress);
                } catch (Exception ignore) {
                    // progress is best-effort
                }
            }
        });

        // ---- 按原顺序产出；任何漏掉的检查项兜底为失败（交补审轮）----
        List<CheckAttempt> out = new ArrayList<>(indexedPlans.size());
        for (IndexedPlan indexed : indexedPlans) {
            CheckAttempt a = attempts.get(indexed.index());
            if (a == null) {
                a = CheckAttempt.failure(indexed.index(), List.of(), false,
                        new IllegalStateException("no result for check " + indexed.plan().checkCode()));
            }
            out.add(a);
        }
        return out;
    }

    /**
     * 有界并行执行：单任务并发上限 = checkConcurrency（per-task 信号量，父线程 acquire，
     * 不在 worker 线程里 park），全部提交到 sarCheckExecutor，等全部完成后返回。
     * 每个 item 的业务逻辑与进度上报由 {@code work} 自行处理（异常应在 work 内消化）。
     */
    private <T> void runBounded(Iterable<T> items, Consumer<? super T> work) {
        Semaphore slots = new Semaphore(Math.max(1, checkConcurrency));
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (T item : items) {
            try {
                slots.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    work.accept(item);
                } finally {
                    slots.release();
                }
            }, sarCheckExecutor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private static final Pattern ASCII_TERM = Pattern.compile("[A-Za-z0-9]{3,}");
    private static final Pattern NON_CJK = Pattern.compile("[^\\u4e00-\\u9fa5]");

    /** 文档结构索引：按区域键聚合块，组内按 (chapterIndex, blockIndex) 阅读顺序排列。 */
    private SectionIndex buildSectionIndex(List<SarDocumentBlock> blocks) {
        LinkedHashMap<String, List<SarDocumentBlock>> bySection = new LinkedHashMap<>();
        for (SarDocumentBlock b : blocks) {
            bySection.computeIfAbsent(blockSectionKey(b), k -> new ArrayList<>()).add(b);
        }
        for (List<SarDocumentBlock> list : bySection.values()) {
            list.sort((a, b) -> {
                int c = Integer.compare(nz(a.getChapterIndex()), nz(b.getChapterIndex()));
                return c != 0 ? c : Integer.compare(nz(a.getBlockIndex()), nz(b.getBlockIndex()));
            });
        }
        return new SectionIndex(bySection);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private String blockSectionKey(SarDocumentBlock b) {
        String sp = b.getSectionPath();
        if (sp != null && !sp.isBlank()) return "S:" + sp;
        Integer ci = b.getChapterIndex();
        return "C:" + (ci == null ? "?" : ci);
    }

    /**
     * 阶段 1：三路并联路由 + 区域级取证。对单个检查项用「结构(applies_to.sections/keywords) +
     * 词法(检查问题/通过标准 的术语命中) + 语义(向量召回落点)」三路打分，选出最匹配的区域，
     * 取该区域完整块（阅读顺序、封顶 regionMaxBlocks）作为证据。三路皆未命中时回退向量 top-N，
     * 保证不因路由失败而漏判。返回的 routeConfidence = best/total，用于降级置信度与自适应复核。
     */
    private RetrievedCheck routeCheck(String taskId, IndexedPlan indexed,
                                      AiModelConfig embeddingModel, AiModelConfig rerankerModel,
                                      SectionIndex sectionIndex) throws Exception {
        CheckPlan plan = indexed.plan();
        Map<String, Double> score = new HashMap<>();
        Set<String> structuralKeys = new HashSet<>();

        // 1) 结构：applies_to.sections 命中章节号；keywords 命中 section_path
        List<String> ruleSections = plan.rule() == null || plan.rule().getSections() == null
                ? List.of() : plan.rule().getSections();
        List<String> ruleKeywords = plan.rule() == null || plan.rule().getKeywords() == null
                ? List.of() : plan.rule().getKeywords();
        for (String key : sectionIndex.bySection().keySet()) {
            String sp = key.startsWith("S:") ? key.substring(2) : key;
            double s = 0;
            for (String sec : ruleSections) if (sectionMatches(sp, sec)) s += 3.0;
            String spLower = sp.toLowerCase(Locale.ROOT);
            for (String kw : ruleKeywords) {
                if (kw != null && !kw.isBlank() && spLower.contains(kw.toLowerCase(Locale.ROOT))) s += 2.0;
            }
            if (s > 0) { score.merge(key, s, Double::sum); structuralKeys.add(key); }
        }

        // 2) 词法：检查问题 + 通过标准 的关键词在各区域文本中的命中（轻量 BM25 近似）
        List<String> terms = lexicalTerms(plan.checkQuestion() + " " + plan.passCriteria());
        if (!terms.isEmpty()) {
            for (Map.Entry<String, List<SarDocumentBlock>> e : sectionIndex.bySection().entrySet()) {
                String hay = sectionText(e.getValue()).toLowerCase(Locale.ROOT);
                int hit = 0;
                for (String t : terms) if (hay.contains(t)) hit++;
                if (hit > 0) score.merge(e.getKey(), 2.0 * hit / terms.size(), Double::sum);
            }
        }

        // 3) 语义：向量召回，按召回块落到的区域累计（召回分加权）
        List<ScoredBlock> recalled = recall(taskId, buildRetrievalQuery(plan), embeddingModel, recallTopK);
        for (ScoredBlock sb : recalled) {
            score.merge(blockSectionKey(sb.block()), Math.max(0.0, sb.score()) * 2.0, Double::sum);
        }

        String bestKey = null;
        double best = 0, secondBest = 0;
        for (Map.Entry<String, Double> e : score.entrySet()) {
            double v = e.getValue();
            if (v > best) { secondBest = best; best = v; bestKey = e.getKey(); }
            else if (v > secondBest) { secondBest = v; }
        }

        List<SarDocumentBlock> region = bestKey == null ? null : sectionIndex.bySection().get(bestKey);
        if (region == null || region.isEmpty()) {
            // 三路皆未命中 → 回退向量 top-N（rerank 取证），保证不漏判，置信度低。
            List<ScoredBlock> ev = rerank(buildRetrievalQuery(plan), recalled, rerankerModel, evidenceMaxBlocks);
            return new RetrievedCheck(indexed, ev,
                    rerankerModel != null && !ev.isEmpty(), ev.isEmpty() ? 0.0 : 0.25);
        }

        // 区域级取证：取整段区域（阅读顺序），封顶 regionMaxBlocks。
        int cap = Math.max(1, regionMaxBlocks);
        List<SarDocumentBlock> picked = region.size() > cap ? region.subList(0, cap) : region;
        List<ScoredBlock> evidence = new ArrayList<>(picked.size());
        for (SarDocumentBlock b : picked) evidence.add(new ScoredBlock(b, best, "route"));
        // 路由置信度：结构命中最可信；否则看与次优区域的分差。
        double routeConf = structuralKeys.contains(bestKey) ? 0.9
                : best >= 2 * secondBest ? 0.7
                : best > secondBest ? 0.5 : 0.3;
        return new RetrievedCheck(indexed, evidence, false, routeConf);
    }

    /** 标准章节号宽松匹配：section_path 以该号开头 / 含"第N章"等。宁可命中。 */
    private boolean sectionMatches(String sectionPath, String sec) {
        if (sec == null || sec.isBlank() || sectionPath == null) return false;
        String s = sec.trim();
        String sp = sectionPath.trim();
        if (sp.equals(s) || sp.startsWith(s + " ") || sp.startsWith(s + ".")
                || sp.startsWith(s + "、") || sp.startsWith(s + "章")) return true;
        if (sp.contains("第" + s + "章")) return true;
        String head = sp.split("[\\s>　]", 2)[0];
        return head.equals(s) || head.startsWith(s + ".");
    }

    /** 轻量分词：ASCII 词(≥3) + 中文 2-gram（去重、封顶 24），用于词法路由。 */
    private List<String> lexicalTerms(String text) {
        if (text == null) return List.of();
        Set<String> terms = new HashSet<>();
        var m = ASCII_TERM.matcher(text);
        while (m.find()) terms.add(m.group().toLowerCase(Locale.ROOT));
        String cjk = NON_CJK.matcher(text).replaceAll("");
        for (int i = 0; i + 2 <= cjk.length(); i++) terms.add(cjk.substring(i, i + 2));
        List<String> out = new ArrayList<>(terms);
        return out.size() > 24 ? out.subList(0, 24) : out;
    }

    /** 区域文本拼接（section_path + 正文），封顶 6000 字以控词法扫描成本。 */
    private String sectionText(List<SarDocumentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (SarDocumentBlock b : blocks) {
            sb.append(b.getSectionPath() == null ? "" : b.getSectionPath()).append(' ')
              .append(b.getTextContent() == null ? "" : b.getTextContent()).append('\n');
            if (sb.length() > 6000) break;
        }
        return sb.toString();
    }

    /** 按"路由命中的区域"分组，组内再按 maxChecksPerCall 装箱；箱内证据取并集（区域整段）。 */
    private List<CallBin> planCallBins(List<IndexedPlan> indexedPlans,
                                       Map<Integer, RetrievedCheck> retrieved) {
        LinkedHashMap<String, List<RetrievedCheck>> bySection = new LinkedHashMap<>();
        for (IndexedPlan indexed : indexedPlans) {
            RetrievedCheck rc = retrieved.getOrDefault(indexed.index(),
                    new RetrievedCheck(indexed, List.of(), false, 0.0));
            bySection.computeIfAbsent(sectionKey(rc.evidence()), k -> new ArrayList<>()).add(rc);
        }
        int cap = Math.max(1, maxChecksPerCall);
        List<CallBin> bins = new ArrayList<>();
        for (List<RetrievedCheck> group : bySection.values()) {
            for (int i = 0; i < group.size(); i += cap) {
                List<RetrievedCheck> sub = new ArrayList<>(group.subList(i, Math.min(group.size(), i + cap)));
                bins.add(new CallBin(sub, unionEvidence(sub)));
            }
        }
        return bins;
    }

    private String sectionKey(List<ScoredBlock> evidence) {
        if (evidence.isEmpty()) return "_no_evidence_";
        return blockSectionKey(evidence.get(0).block());
    }

    /** 箱内证据并集：按 blockId 去重，按阅读顺序排列（区域级连贯原文），封顶 regionMaxBlocks。 */
    private List<ScoredBlock> unionEvidence(List<RetrievedCheck> members) {
        LinkedHashMap<String, ScoredBlock> byId = new LinkedHashMap<>();
        for (RetrievedCheck rc : members) {
            for (ScoredBlock sb : rc.evidence()) {
                ScoredBlock prev = byId.get(sb.block().getBlockId());
                if (prev == null || sb.score() > prev.score()) {
                    byId.put(sb.block().getBlockId(), sb);
                }
            }
        }
        List<ScoredBlock> all = new ArrayList<>(byId.values());
        all.sort((a, b) -> {
            int c = Integer.compare(nz(a.block().getChapterIndex()), nz(b.block().getChapterIndex()));
            return c != 0 ? c : Integer.compare(nz(a.block().getBlockIndex()), nz(b.block().getBlockIndex()));
        });
        int cap = Math.max(1, Math.max(regionMaxBlocks, maxEvidencePerCall));
        return all.size() > cap ? new ArrayList<>(all.subList(0, cap)) : all;
    }

    /** 阶段 2：一个箱 = 一次模型调用，评估箱内全部检查项；返回 index → CheckAttempt。 */
    private Map<Integer, CheckAttempt> reviewGroup(String taskId, CallBin bin,
                                                   AiModelConfig chatModel,
                                                   int retryNumber, int totalPlanCount) {
        Map<Integer, CheckAttempt> out = new LinkedHashMap<>();
        List<RetrievedCheck> members = bin.members();
        try {
            int seedSeq = members.get(0).indexed().index() + 1 + retryNumber * Math.max(1, totalPlanCount);
            Map<String, Map<String, Object>> byCode = callGroup(taskId, bin, chatModel, seedSeq);
            for (RetrievedCheck rc : members) {
                CheckPlan plan = rc.indexed().plan();
                Map<String, Object> res = byCode.get(plan.checkCode());
                if (res == null) {
                    out.put(rc.indexed().index(), CheckAttempt.failure(rc.indexed().index(),
                            rc.evidence(), rc.reranked(),
                            new IllegalStateException("model omitted check_code " + plan.checkCode())));
                } else {
                    List<Map<String, Object>> rows = buildRowsFromResult(plan, res, rc.evidence(), rc.routeConfidence());
                    out.put(rc.indexed().index(),
                            CheckAttempt.success(rc.indexed().index(), rows, rc.reranked()));
                }
            }
        } catch (Exception e) {
            // 整组调用失败 → 组内全部检查项标失败，交补审轮。
            log.warn("SAR group call failed: task={}, size={}, err={}",
                    taskId, members.size(), rootErrorMessage(e));
            for (RetrievedCheck rc : members) {
                out.put(rc.indexed().index(), CheckAttempt.failure(rc.indexed().index(),
                        rc.evidence(), rc.reranked(), e));
            }
        }
        return out;
    }

    private Map<String, Map<String, Object>> callGroup(String taskId, CallBin bin,
                                                       AiModelConfig chatModel, int seed) throws Exception {
        AiCallOptions options = AiCallOptions.builder()
                .temperature(0.0)
                .topP(1.0)
                .maxTokensOverride(8192)
                .seed(stableSeed(taskId, seed))
                .enablePromptCache(true)
                .structuredSchema(com.alibaba.fastjson2.JSON.parseObject(
                        com.alibaba.fastjson2.JSON.toJSONString(ReviewResultSchema.ragGroupSchema())))
                .structuredSchemaName(ReviewResultSchema.RAG_GROUP_SCHEMA_NAME)
                .build();
        String response = aiModelService.callAiModel(
                chatModel, SAR_GROUP_SYSTEM_PROMPT, buildGroupPrompt(bin), options);
        Map<String, Object> parsed = parseJson(response);
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        Object results = parsed.get("results");
        if (results instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rm = (Map<String, Object>) m;
                    String code = Objects.toString(rm.get("check_code"), "");
                    if (!code.isBlank()) byCode.put(code, rm);
                }
            }
        }
        return byCode;
    }

    private String buildGroupPrompt(CallBin bin) {
        StringBuilder sb = new StringBuilder();
        sb.append("【区域原文】以下是本组检查项【预期所在章节/区域】的完整原文（按阅读顺序）。"
                + "请据此判定；要求的内容若在本区域内缺失，即视为 Fail（缺失）:\n");
        if (bin.unionEvidence().isEmpty()) {
            sb.append("(未定位到对应区域；若检查项要求的内容本应出现却无从查到，按'内容缺失'判 Fail)\n");
        } else {
            for (ScoredBlock item : bin.unionEvidence()) {
                SarDocumentBlock block = item.block();
                sb.append("[").append(block.getBlockId()).append("] ")
                        .append(block.getSectionPath()).append('\n')
                        .append(block.getTextContent()).append("\n\n");
            }
        }
        sb.append("\n【待判定检查项】对每个 check_code 各返回一条 results，按编号对齐，不得遗漏/新增/合并:\n");
        for (RetrievedCheck rc : bin.members()) {
            CheckPlan p = rc.indexed().plan();
            String type = p.check() == null ? "presence"
                    : Objects.toString(p.check().getCheckType(), "presence");
            sb.append("- check_code: ").append(p.checkCode()).append('\n');
            sb.append("  类型: ").append(type);
            if ("presence".equalsIgnoreCase(type)) {
                sb.append("（缺失类：本区域为预期位置，若区域内不存在满足通过标准的内容即判 Fail）");
            }
            sb.append('\n');
            sb.append("  规则: ").append(p.ruleName()).append('\n');
            sb.append("  检查问题: ").append(p.checkQuestion()).append('\n');
            sb.append("  通过标准: ").append(p.passCriteria()).append('\n');
        }
        sb.append("\n每个 check_code 独立判定；每处违规作为该结果 findings 里的一条，evidence 逐字摘录上面区域原文。");
        return sb.toString();
    }

    /** 从分组结果里某个 check 的结果对象展开成 finding 行（一处违规一行，Pass 单行）。 */
    private List<Map<String, Object>> buildRowsFromResult(CheckPlan plan, Map<String, Object> res,
                                                          List<ScoredBlock> evidence, double routeConfidence) {
        String status = normalizeCheckStatus(res.get("status"));
        String confidence = Objects.toString(
                res.getOrDefault("confidence", evidence.isEmpty() ? "needs_review" : "medium"), "medium");
        // 定位不确定（三路路由置信度低）→ 降级为 needs_review，优先进入自适应复核。
        if (routeConfidence < routeConfidenceThreshold) confidence = "needs_review";
        String reason = Objects.toString(res.getOrDefault("reason", ""), "");

        LinkedHashMap<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        Object f = res.get("findings");
        if (f instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> fm = (Map<String, Object>) m;
                String key = normalizeForDedup(Objects.toString(fm.get("location"), "")
                        + "|" + Objects.toString(fm.get("evidence"), ""));
                deduped.putIfAbsent(key, fm);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        if (deduped.isEmpty()) {
            rows.add(buildRow(plan, status, reason, "", "",
                    evidence.isEmpty() ? "needs_review" : confidence, evidence, null, 0, 1));
            return rows;
        }
        String rowStatus = "Review".equals(status) ? "Review" : "Fail";
        int total = deduped.size();
        int idx = 0;
        for (Map<String, Object> fm : deduped.values()) {
            String location = Objects.toString(fm.getOrDefault("location", ""), "");
            String evidenceText = Objects.toString(fm.getOrDefault("evidence", ""), "");
            String description = Objects.toString(fm.getOrDefault("description", ""), "");
            String suggestion = Objects.toString(fm.getOrDefault("suggestion", ""), "");
            ScoredBlock matched = matchEvidenceBlock(evidenceText, evidence);
            Map<String, Object> row = buildRow(plan, rowStatus,
                    description.isBlank() ? reason : description,
                    evidenceText, suggestion, confidence, evidence, matched, idx, total);
            if (!location.isBlank()) row.put("location", location);
            rows.add(row);
            idx++;
        }
        return rows;
    }

    private Map<String, Object> buildFailedCheck(CheckPlan plan, CheckAttempt attempt) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("finding_id", plan.checkCode() + "#0");
        check.put("violationIndex", 0);
        check.put("violationCount", 1);
        check.put("check_code", plan.checkCode());
        check.put("rule_code", plan.ruleCode());
        check.put("ruleName", plan.ruleName());
        check.put("ruleDescription", plan.ruleDescription());
        check.put("category", plan.category());
        check.put("check_question", plan.checkQuestion());
        check.put("passCriteria", plan.passCriteria());
        check.put("status", "Review");
        check.put("reason", "单项审查失败，自动补审后仍未成功：" + rootErrorMessage(attempt.error()));
        check.put("evidence", "");
        check.put("missing_items", List.of("模型审查结果"));
        check.put("suggestion", "请检查模型服务状态后对该项执行人工复核或重新审查。");
        check.put("confidence", "needs_review");
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

    private List<SarDocumentBlock> buildBlocks(String taskId, List<WordParser.Chapter> chapters) {
        List<SarDocumentBlock> blocks = new ArrayList<>();
        for (int chapterIdx = 0; chapterIdx < chapters.size(); chapterIdx++) {
            WordParser.Chapter chapter = chapters.get(chapterIdx);
            List<NodeRange> pieces = splitChapterNodes(chapter);
            int blockIdx = 1;
            for (NodeRange piece : pieces) {
                String text = (chapter.getTitle() == null || chapter.getTitle().isBlank())
                        ? piece.text()
                        : chapter.getTitle() + "\n\n" + piece.text();
                if (text.isBlank()) continue;
                SarDocumentBlock block = new SarDocumentBlock();
                block.setTaskId(taskId);
                String chapterKey = "C" + String.format("%03d", chapterIdx + 1);
                block.setBlockId("BLOCK-" + chapterKey + "-" + String.format("%04d", blockIdx));
                block.setBlockType("node_range");
                block.setChapterIndex(chapterIdx + 1);
                block.setBlockIndex(blockIdx++);
                block.setSectionPath(firstNonBlank(piece.sectionPath(), chapter.getTitle()));
                block.setStartNodeId(piece.startNodeId());
                block.setEndNodeId(piece.endNodeId());
                block.setTextContent(text);
                block.setTextHash(sha1(text));
                blocks.add(block);
            }
        }
        return blocks;
    }

    private List<NodeRange> splitChapterNodes(WordParser.Chapter chapter) {
        List<WordParser.DocumentNode> sourceNodes = chapter.getNodes().stream()
                .filter(node -> !"chapter_title".equals(node.getType()))
                .filter(node -> node.getReviewText() != null && !node.getReviewText().isBlank())
                .toList();
        if (sourceNodes.isEmpty()) {
            String content = chapter.getContent() == null ? "" : chapter.getContent().trim();
            if (content.isBlank()) return List.of();
            return splitOversizedNode(content, null, null, chapter.getTitle());
        }

        List<NodeRange> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        WordParser.DocumentNode startNode = null;
        WordParser.DocumentNode endNode = null;
        for (WordParser.DocumentNode node : sourceNodes) {
            String part = node.getReviewText().trim();
            if (part.length() > blockMaxChars) {
                if (current.length() > 0 && startNode != null && endNode != null) {
                    pieces.add(new NodeRange(
                            current.toString().trim(),
                            startNode.getId(),
                            endNode.getId(),
                            firstNonBlank(startNode.getSectionPath(), chapter.getTitle())));
                }
                current.setLength(0);
                startNode = null;
                endNode = null;
                pieces.addAll(splitOversizedNode(
                        part,
                        node.getId(),
                        node.getId(),
                        firstNonBlank(node.getSectionPath(), chapter.getTitle())));
                continue;
            }
            if (current.length() > 0 && current.length() + part.length() + 2 > blockMaxChars) {
                pieces.add(new NodeRange(
                        current.toString().trim(),
                        startNode.getId(),
                        endNode.getId(),
                        firstNonBlank(startNode.getSectionPath(), chapter.getTitle())));
                current.setLength(0);
                startNode = null;
                endNode = null;
            }
            if (startNode == null) {
                startNode = node;
            }
            endNode = node;
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(part);
        }
        if (current.length() > 0 && startNode != null && endNode != null) {
            pieces.add(new NodeRange(
                    current.toString().trim(),
                    startNode.getId(),
                    endNode.getId(),
                    firstNonBlank(startNode.getSectionPath(), chapter.getTitle())));
        }
        return pieces;
    }

    private List<NodeRange> splitOversizedNode(String text, String startNodeId,
                                                String endNodeId, String sectionPath) {
        List<NodeRange> pieces = new ArrayList<>();
        int maxChars = Math.max(1, blockMaxChars);
        for (int start = 0; start < text.length(); start += maxChars) {
            int end = Math.min(text.length(), start + maxChars);
            String part = text.substring(start, end).trim();
            if (!part.isBlank()) {
                pieces.add(new NodeRange(part, startNodeId, endNodeId, sectionPath));
            }
        }
        return pieces;
    }

    private void embedBlocks(List<SarDocumentBlock> blocks, AiModelConfig embeddingModel) throws Exception {
        int batchSize = Math.max(1, embeddingBatchSize);
        Integer detectedDimension = null;
        for (int start = 0; start < blocks.size(); start += batchSize) {
            int end = Math.min(blocks.size(), start + batchSize);
            List<SarDocumentBlock> batch = blocks.subList(start, end);
            List<String> texts = batch.stream().map(SarDocumentBlock::getTextContent).toList();
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
                SarDocumentBlock block = batch.get(i);
                block.setEmbeddingModel(embeddingModel.getModelName());
                block.setEmbeddingVector(objectMapper.writeValueAsString(vector));
                block.setEmbeddingDimension(vector.size());
            }
        }
    }

    private List<CheckPlan> buildCheckPlans(Long scenarioId) {
        List<SarRule> rules = sarRuleService.getRulesByScenarioId(scenarioId);
        List<CheckPlan> plans = new ArrayList<>();
        int auto = 1;
        for (SarRule rule : rules) {
            List<SarRuleCheck> checks = sarRuleCheckMapper.findActiveByRuleId(rule.getId());
            String ruleCode = firstNonBlank(rule.getRuleCode(), "R-AUTO-" + String.format("%03d", auto++));
            if (checks.isEmpty()) {
                plans.add(new CheckPlan(rule, null, ruleCode, ruleCode + "-C001",
                        firstNonBlank(rule.getDescription(), rule.getRuleName()),
                        rule.getContent(), "other"));
                continue;
            }
            for (SarRuleCheck check : checks) {
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

    /** 区域级 + 清单式的分组系统提示：证据是检查项预期所在区域的完整原文，缺失即 Fail。 */
    private static final String SAR_GROUP_SYSTEM_PROMPT = """
            你是严格的航空机载文档审查员。下面会给你【某一章节/区域的完整原文】，以及一组应在该区域内核查的检查项（每个有唯一 check_code）。
            请对每一个 check_code 独立判定，目标是「在预期区域内尽可能找全」违规，宁可多报，不可漏报。

            判定规则（三级，对每个检查项分别给出）：
            - Fail：区域内存在违规，或该检查项要求的内容在本区域（其预期所在位置）缺失。缺失即为 Fail，绝不要因为"没看到"就判通过或待复核。
            - Review：证据自相矛盾，或确实无法判断（仅在这种情况下使用）。
            - Pass：当且仅当你能在区域原文中引用到明确满足该项要求的内容。

            输出要求：
            - 对输入里的每一个 check_code 各返回一条 results，按 check_code 对齐，不得遗漏、不得新增、不得合并。
            - 每个检查项的每一处违规作为它 findings 里的一条，给出可定位的 location 与逐字摘录的 evidence。
            - 同一检查项的问题在多个位置出现时，每个位置各列一条，不要合并。
            - 只依据提供的区域原文判断，不要臆造；"要求的内容在预期区域中不存在"本身就是判 Fail 的有效依据。
            严格按给定 JSON Schema 输出。
            """;

    /** 组装一行 finding 结果（与前端检查矩阵 / 导出字段对齐）。 */
    private Map<String, Object> buildRow(CheckPlan plan, String status, String reason,
                                         String evidenceText, String suggestion, String confidence,
                                         List<ScoredBlock> evidence, ScoredBlock matched,
                                         int violationIndex, int violationCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("finding_id", plan.checkCode() + "#" + violationIndex);
        row.put("check_code", plan.checkCode());
        row.put("rule_code", plan.ruleCode());
        row.put("ruleName", plan.ruleName());
        row.put("ruleDescription", plan.ruleDescription());
        row.put("category", plan.category());
        row.put("check_question", plan.checkQuestion());
        row.put("passCriteria", plan.passCriteria());
        row.put("status", status);
        row.put("reason", reason);
        row.put("evidence", evidenceText);
        row.put("missing_items", new ArrayList<>());
        row.put("suggestion", suggestion);
        row.put("confidence", confidence);
        row.put("violationIndex", violationIndex);
        row.put("violationCount", violationCount);
        // 来源：优先用匹配到该违规证据的块，回退到首个证据块。
        ScoredBlock source = matched != null ? matched
                : (evidence.isEmpty() ? null : evidence.get(0));
        row.put("sourceTitle", source == null ? "" : source.block().getSectionPath());
        if (source != null) {
            row.put("location", source.block().getSectionPath());
            row.put("startNodeId", source.block().getStartNodeId());
            row.put("endNodeId", source.block().getEndNodeId());
        }
        row.put("sourceRefs", evidence.stream().map(this::toSourceRef).toList());
        row.put("retrievalScores", evidence.stream().map(this::toRetrievalScore).toList());
        return row;
    }

    /** 找出包含该违规证据文字的证据块，用于精确定位高亮；找不到回退 null。 */
    private ScoredBlock matchEvidenceBlock(String evidenceText, List<ScoredBlock> evidence) {
        if (evidenceText == null || evidenceText.isBlank()) return null;
        String needle = normalizeForDedup(evidenceText);
        if (needle.length() < 6) return null;
        String probe = needle.length() > 40 ? needle.substring(0, 40) : needle;
        for (ScoredBlock sb : evidence) {
            String hay = normalizeForDedup(sb.block().getTextContent());
            if (hay.contains(probe)) return sb;
        }
        return null;
    }

    private static final Pattern DEDUP_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern DEDUP_PUNCT = Pattern.compile("[\\p{Punct}，。；、：（）()【】\\[\\]]");

    private static String normalizeForDedup(String s) {
        if (s == null) return "";
        String noWhitespace = DEDUP_WHITESPACE.matcher(s).replaceAll("");
        return DEDUP_PUNCT.matcher(noWhitespace).replaceAll("").toLowerCase(Locale.ROOT);
    }

    // ---------------- 阶段三：两阶段复核 ----------------

    /**
     * 对候选违规并发跑独立复核；返回 finding_id → verdict map（同时已就地写回每行的
     * verifyStatus / verifyReason）。召回优先：只标注，不删除、不把 Fail 改判为 Pass。
     */
    /** 自适应复核候选判定：仅低置信/定位不确定的违规才复核。 */
    private boolean isUncertain(Map<String, Object> row) {
        String c = Objects.toString(row.get("confidence"), "").toLowerCase(Locale.ROOT);
        return c.isBlank() || c.equals("low") || c.equals("needs_review");
    }

    /**
     * 阶段四：跨章一致性核对。把各章节标题 + 截断正文拼成摘要，让模型只找【跨章节】矛盾
     * （取值不一致 / 图表号、术语、参数前后矛盾），返回一致性违规行。失败返回空列表（不致命）。
     */
    private List<Map<String, Object>> runConsistencyPass(String taskId,
                                                         List<WordParser.Chapter> chapters,
                                                         AiModelConfig chatModel) {
        try {
            StringBuilder digest = new StringBuilder();
            for (int i = 0; i < chapters.size(); i++) {
                WordParser.Chapter ch = chapters.get(i);
                String content = ch.getContent() == null ? "" : ch.getContent().trim();
                if (content.length() > 1200) content = content.substring(0, 1200);
                digest.append("== 第").append(i + 1).append("章 ")
                        .append(ch.getTitle() == null ? "" : ch.getTitle()).append(" ==\n")
                        .append(content).append("\n\n");
                if (digest.length() > 24000) break;
            }
            String sys = "你是严格的航空机载文档一致性审查员。只找【跨章节】的相互矛盾或不一致："
                    + "同一实体在不同章节取值不同（温度范围、鉴定试验类别、设备型号/数量、合格判据数值等），"
                    + "图号/表号/章节引用前后不一致，术语前后不统一。不要报单章节内的问题，找不到明确矛盾就返回空列表。"
                    + "严格输出 JSON：{\"issues\":[{\"location\":\"涉及章节/位置\",\"description\":\"矛盾说明\","
                    + "\"evidence\":\"两处相互矛盾的原文摘录\",\"suggestion\":\"如何统一\"}]}";
            AiCallOptions options = AiCallOptions.builder()
                    .temperature(0.0).topP(1.0).maxTokensOverride(4096)
                    .seed(stableSeed(taskId, 800000)).enablePromptCache(true).build();
            String response = aiModelService.callAiModel(chatModel, sys,
                    "文档各章节原文摘要如下，请核对跨章一致性：\n\n" + digest, options);
            Map<String, Object> parsed = parseJson(response);
            List<Map<String, Object>> rows = new ArrayList<>();
            if (parsed.get("issues") instanceof List<?> list) {
                int total = list.size(), idx = 0;
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> im = (Map<String, Object>) m;
                    rows.add(buildConsistencyRow(idx++, total,
                            Objects.toString(im.getOrDefault("location", ""), ""),
                            Objects.toString(im.getOrDefault("description", ""), ""),
                            Objects.toString(im.getOrDefault("evidence", ""), ""),
                            Objects.toString(im.getOrDefault("suggestion", ""), "")));
                }
            }
            return rows;
        } catch (Exception e) {
            log.warn("SAR consistency pass failed: task={}, err={}", taskId, rootErrorMessage(e));
            return List.of();
        }
    }

    /** 组装一行跨章一致性违规（与检查矩阵字段对齐，rule_code/check_code 用 CONSISTENCY 标识）。 */
    private Map<String, Object> buildConsistencyRow(int idx, int total, String location,
                                                    String description, String evidence, String suggestion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("finding_id", "CONSISTENCY#" + idx);
        row.put("check_code", "CONSISTENCY");
        row.put("rule_code", "CONSISTENCY");
        row.put("ruleName", "跨章一致性核对");
        row.put("ruleDescription", "检测跨章节取值矛盾、图表号/术语/参数前后不一致");
        row.put("category", "逻辑一致性");
        row.put("check_question", "文档跨章节内容是否一致、无相互矛盾");
        row.put("passCriteria", "同一实体/图表号/术语/参数在各章节取值一致、引用正确");
        row.put("status", "Fail");
        row.put("reason", description);
        row.put("evidence", evidence);
        row.put("missing_items", new ArrayList<>());
        row.put("suggestion", suggestion);
        row.put("confidence", "needs_review");
        row.put("violationIndex", idx);
        row.put("violationCount", Math.max(1, total));
        if (!location.isBlank()) {
            row.put("location", location);
            row.put("sourceTitle", location);
        }
        row.put("sourceRefs", new ArrayList<>());
        row.put("retrievalScores", new ArrayList<>());
        return row;
    }

    private Map<String, Map<String, Object>> runVerifyPass(String taskId,
                                                           List<Map<String, Object>> candidates,
                                                           AiModelConfig chatModel) {
        Map<String, Map<String, Object>> verdicts = new ConcurrentHashMap<>();
        AtomicInteger seq = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        int total = candidates.size();
        runBounded(candidates, row -> {
            try {
                Map<String, Object> verdict = verifyOneFinding(taskId, row, chatModel, seq.incrementAndGet());
                row.put("verifyStatus", verdict.get("verifyStatus"));
                row.put("verifyReason", verdict.get("verifyReason"));
                verdicts.put(Objects.toString(row.get("finding_id"),
                        Objects.toString(row.get("check_code"), "")), verdict);
            } finally {
                // 复核阶段也给增量进度（93→99），否则会一直停在 93% 直到全部复核完。
                int n = done.incrementAndGet();
                int progress = 93 + (int) Math.round((double) n / total * 6);
                try {
                    webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                            "两阶段复核：" + n + "/" + total, progress);
                } catch (Exception ignore) {
                    // progress is best-effort
                }
            }
        });
        return verdicts;
    }

    private Map<String, Object> verifyOneFinding(String taskId, Map<String, Object> row,
                                                 AiModelConfig chatModel, int seq) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        try {
            String userPrompt = "请独立复核下面这条审查发现是否确实成立。\n\n"
                    + "检查项：" + Objects.toString(row.get("check_question"), "") + "\n"
                    + "通过标准：" + Objects.toString(row.get("passCriteria"), "") + "\n"
                    + "系统判定：" + Objects.toString(row.get("status"), "") + "\n"
                    + "定位：" + Objects.toString(row.get("location"), "") + "\n"
                    + "证据原文：" + Objects.toString(row.get("evidence"), "") + "\n"
                    + "判定理由：" + Objects.toString(row.get("reason"), "") + "\n\n"
                    + "若证据确实违反通过标准，回 CONFIRMED；若证据不足以支撑该结论或可能是误报，回 UNCERTAIN。"
                    + "注意：召回优先，存疑时回 UNCERTAIN（不要回 CONFIRMED 也不要判其为通过）。";
            AiCallOptions options = AiCallOptions.builder()
                    .temperature(0.0)
                    .topP(1.0)
                    .maxTokensOverride(1024)
                    .seed(stableSeed(taskId, 900000 + seq))
                    .structuredSchema(com.alibaba.fastjson2.JSON.parseObject(
                            com.alibaba.fastjson2.JSON.toJSONString(ReviewResultSchema.ragVerifySchema())))
                    .structuredSchemaName(ReviewResultSchema.RAG_VERIFY_SCHEMA_NAME)
                    .build();
            String response = aiModelService.callAiModel(chatModel,
                    "你是严格的航空文档审查复核员，只做证据核对，不放过真实问题，也不轻易确认证据不足的结论。",
                    userPrompt, options);
            Map<String, Object> parsed = parseJson(response);
            String v = Objects.toString(parsed.getOrDefault("verdict", "UNCERTAIN"), "UNCERTAIN");
            verdict.put("verifyStatus", "CONFIRMED".equalsIgnoreCase(v) ? "CONFIRMED" : "UNCERTAIN");
            verdict.put("verifyReason", Objects.toString(parsed.getOrDefault("reason", ""), ""));
        } catch (Exception e) {
            // 复核调用失败：召回优先，保留原 finding，标 UNCERTAIN。
            verdict.put("verifyStatus", "UNCERTAIN");
            verdict.put("verifyReason", "复核调用失败：" + rootErrorMessage(e));
        }
        return verdict;
    }

    private Map<String, Object> parseJson(String response) {
        JsonNode node = JsonExtractor.extract(response, objectMapper);
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    private Map<String, Object> toSourceRef(ScoredBlock item) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("sourceId", item.block().getBlockId());
        ref.put("title", item.block().getSectionPath());
        ref.put("sectionPath", item.block().getSectionPath());
        ref.put("chapterIndex", item.block().getChapterIndex());
        ref.put("startNodeId", item.block().getStartNodeId());
        ref.put("endNodeId", item.block().getEndNodeId());
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

    private Map<String, Object> toOriginalSource(WordParser.Chapter chapter, int chapterIndex) {
        return DocumentSourceMapper.toChapterSource(
                chapter,
                chapterIndex,
                "CHAPTER-" + String.format("%03d", chapterIndex));
    }

    private String normalizeCheckStatus(Object raw) {
        if (raw == null) return "Review";
        String value = raw.toString().trim();
        if (ReviewResultSchema.CHECK_STATUS_ENUM.contains(value)) return value;
        String lower = value.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "pass", "passed" -> "Pass";
            case "fail", "failed" -> "Fail";
            // 三级判定：部分通过(Partial) 与 不适用(N/A) 一律并入待复核(Review)。
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
        String src = taskId + ":sar:" + sequence;
        long value = 1125899906842597L;
        for (int i = 0; i < src.length(); i++) {
            value = 31 * value + src.charAt(i);
        }
        return Math.abs(value == Long.MIN_VALUE ? 0L : value);
    }

    private void updateTaskStatus(SarReviewTask task, String status, String failReason) {
        task.setStatus(status);
        task.setFailReason(failReason);
        task.setUpdatedAt(LocalDateTime.now());
        if (task.getAiResult() != null) {
            task.setProblemCount(ReviewExportUtil.computeProblemCount(task.getAiResult()));
        }
        sarReviewTaskMapper.updateById(task);
    }

    private record CheckPlan(SarRule rule, SarRuleCheck check, String ruleCode, String checkCode,
                             String checkQuestion, String passCriteria, String category) {
        String ruleName() {
            return rule != null ? rule.getRuleName() : "";
        }

        String ruleDescription() {
            return rule != null ? Objects.toString(rule.getDescription(), "") : "";
        }

        String ruleContent() {
            return rule != null ? rule.getContent() : "";
        }
    }

    public record PreparedDocument(List<SarDocumentBlock> blocks,
                                   List<WordParser.Chapter> chapters,
                                   AiModelConfig embeddingModel,
                                   int embeddingDimension,
                                   String vectorIndexStrategy) {
    }

    private record NodeRange(String text, String startNodeId,
                             String endNodeId, String sectionPath) {
    }

    private record IndexedPlan(int index, CheckPlan plan) {
    }

    /**
     * 一个检查项的执行结果。{@code results} 是展开后的 finding 行列表：
     * 一处违规一行，Pass 检查产 1 行 Pass。失败时 results 为 null、error 非空。
     */
    private record CheckAttempt(int index,
                                List<Map<String, Object>> results,
                                List<ScoredBlock> evidence,
                                boolean reranked,
                                Exception error) {
        static CheckAttempt success(int index, List<Map<String, Object>> results, boolean reranked) {
            return new CheckAttempt(index, results, List.of(), reranked, null);
        }

        static CheckAttempt failure(int index, List<ScoredBlock> evidence,
                                    boolean reranked, Exception error) {
            return new CheckAttempt(index, null, List.copyOf(evidence), reranked, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    private record ScoredBlock(SarDocumentBlock block, double score, String reason) {
    }

    /** 阶段 1 的路由产物：某检查项命中区域的证据、是否经过 rerank、以及三路路由置信度(0..1)。 */
    private record RetrievedCheck(IndexedPlan indexed, List<ScoredBlock> evidence,
                                  boolean reranked, double routeConfidence) {
    }

    /** 文档结构索引：区域键（S:section_path 或 C:chapterIndex）→ 该区域按阅读顺序排列的块。 */
    private record SectionIndex(LinkedHashMap<String, List<SarDocumentBlock>> bySection) {
    }

    /** 一次分组调用的装箱：成员检查项 + 它们共享的去重证据。 */
    private record CallBin(List<RetrievedCheck> members, List<ScoredBlock> unionEvidence) {
    }
}
