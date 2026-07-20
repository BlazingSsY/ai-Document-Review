package com.aireview.review.sar.service;

import com.aireview.review.dto.ManualCheckDecisionRequest;
import com.aireview.common.dto.PageResponse;
import com.aireview.review.dto.ReviewTaskDTO;
import com.aireview.modelconfig.entity.AiModelConfig;
import com.aireview.review.sar.entity.SarDocumentBlock;
import com.aireview.review.sar.entity.SarReviewAuditLog;
import com.aireview.review.sar.entity.SarReviewTask;
import com.aireview.rule.engine.RuleDispatcher;
import com.aireview.rule.entity.Rule;
import com.aireview.rule.entity.RuleCheck;
import com.aireview.rule.entity.SarRule;
import com.aireview.rule.entity.SarRuleCheck;
import com.aireview.review.sar.repository.SarDocumentVectorRepository;
import com.aireview.review.sar.repository.SarReviewAuditLogMapper;
import com.aireview.review.sar.repository.SarReviewTaskMapper;
import com.aireview.common.websocket.WebSocketService;
import com.aireview.export.ReviewExportUtil;
import com.aireview.modelconfig.service.AiCallOptions;
import com.aireview.modelconfig.service.AiModelService;
import com.aireview.rule.repository.RuleCheckMapper;
import com.aireview.rule.repository.RuleMapper;
import com.aireview.rule.repository.SarRuleCheckMapper;
import com.aireview.rule.service.SarRuleService;
import com.aireview.scenario.service.SarScenarioService;
import com.aireview.review.core.ReviewResultSchema;
import com.aireview.review.llm.JsonExtractor;
import com.aireview.document.ChunkUtils;
import com.aireview.document.DocumentEvidenceLocator;
import com.aireview.document.DocumentSourceMapper;
import com.aireview.document.WordParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SarReviewService {

    private final SarReviewTaskMapper sarReviewTaskMapper;
    private final SarReviewAuditLogMapper sarReviewAuditLogMapper;
    private final SarRuleService sarRuleService;
    private final SarScenarioService sarScenarioService;
    private final SarRuleCheckMapper sarRuleCheckMapper;
    private final RuleMapper ruleMapper;
    private final RuleCheckMapper ruleCheckMapper;
    private final SarDocumentVectorRepository documentVectorRepository;
    private final AiModelService aiModelService;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern FIGURE_TABLE_CAPTION = Pattern.compile(
            "^\\s*([图表])\\s*([0-9０-９一二三四五六七八九十百]+(?:[.．\\-－][0-9０-９一二三四五六七八九十百]+)*)\\s*([:：、.．\\-－\\s].*)?$");
    private static final Pattern FIGURE_TABLE_REFERENCE = Pattern.compile(
            "([图表])\\s*([0-9０-９一二三四五六七八九十百]+(?:[.．\\-－][0-9０-９一二三四五六七八九十百]+)*)");
    private static final Pattern UPPERCASE_TERM = Pattern.compile("\\b[A-Z][A-Z0-9][A-Z0-9\\-/]{1,}\\b");
    private static final Pattern TERM_ALIAS_PAIR = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9\\-/]{1,24})[（(]([A-Za-z][A-Za-z0-9\\-/]{1,20})[）)]");
    private static final Pattern CHINESE_TECH_TERM = Pattern.compile(
            "([\\u4e00-\\u9fa5]{1,12}(?:设备|系统|组件|模块|试验|参数|温度|电压|电流|频率|模式|状态|接口|信号|线束|电缆|线缆|样机|试件))");
    private static final List<String> KNOWN_TERMINOLOGY_ALIASES = List.of(
            "受试设备", "被测设备", "被试设备", "受试件", "被试件", "试验样机", "试验件", "样机",
            "试验对象", "EUT", "UUT", "DUT");
    private static final Set<String> UPPERCASE_TERM_STOPWORDS = Set.of(
            "AND", "THE", "FOR", "WITH", "FROM", "THIS", "THAT", "PASS", "FAIL", "REVIEW");
    private static final String SAR_TEXT_QUALITY_SYSTEM_PROMPT = """
            你是严格的航空文档文字质量审查员。你的任务不是审查工程内容是否满足 DO-160G，
            而是检查文字表达、格式编号、术语一致性和跨章节表述一致性。

            判定只允许三类：
            - Pass：能确认未发现该检查项问题。
            - Fail：能用提供原文或索引证据定位明确问题。
            - Review：证据不足、上下文冲突或无法可靠判断。

            输出要求：
            - 对输入中的每个 check_code 各返回一条 results，不得新增 check_code。
            - 只报告有证据的问题；findings.evidence 必须是从输入中逐字复制的最小充分片段，不得改写或添加说明性前后缀。
            - findings.description 必须按“原文“<evidence逐字内容>”存在/表明……”表述，中文引号内文字必须与 evidence 完全一致。
            - 没有问题时 status=Pass 且 findings=[]。
            严格按给定 JSON Schema 输出。
            """;

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

    @Value("${review.sar.quality.full-scan.enabled:true}")
    private boolean qualityFullScanEnabled;

    @Value("${review.sar.quality.full-scan.batch-blocks:4}")
    private int qualityFullScanBatchBlocks;

    @Value("${review.sar.quality.full-scan.max-blocks:0}")
    private int qualityFullScanMaxBlocks;

    @Value("${review.sar.quality.structure-index.enabled:true}")
    private boolean qualityStructureIndexEnabled;

    @Value("${review.sar.quality.terminology.enabled:true}")
    private boolean qualityTerminologyEnabled;

    @Value("${review.sar.quality.terminology.max-observations:160}")
    private int qualityTerminologyMaxObservations;

    @Value("${review.sar.consistency.max-input-chars:48000}")
    private int consistencyMaxInputChars;

    @Value("${review.sar.consistency.per-chapter-max-chars:4800}")
    private int consistencyPerChapterMaxChars;

    @Value("${review.sar.consistency.windows-per-chapter:4}")
    private int consistencyWindowsPerChapter;

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
        return submitReview(file, scenarioId, selectedModel, userId, true);
    }

    public ReviewTaskDTO submitReview(MultipartFile file, Long scenarioId,
                                      String selectedModel, Long userId,
                                      boolean qualityCheckEnabled) throws IOException {
        return submitReview(file, scenarioId, selectedModel, userId, qualityCheckEnabled, "user");
    }

    public ReviewTaskDTO submitReview(MultipartFile file, Long scenarioId,
                                      String selectedModel, Long userId,
                                      boolean qualityCheckEnabled, String role) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || (!originalFilename.endsWith(".doc") && !originalFilename.endsWith(".docx"))) {
            throw new IllegalArgumentException("Only Word documents (.doc, .docx) are supported");
        }
        sarScenarioService.requireReviewAccess(scenarioId, userId, role);

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
        task.setQualityCheckEnabled(qualityCheckEnabled);
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
        SarReviewTask patch = new SarReviewTask();
        patch.setStatus(SarReviewTask.STATUS_CANCELLED);
        patch.setFailReason("User cancelled");
        patch.setUpdatedAt(LocalDateTime.now());
        int updated = sarReviewTaskMapper.update(patch,
                new LambdaUpdateWrapper<SarReviewTask>()
                        .eq(SarReviewTask::getId, taskId)
                        .in(SarReviewTask::getStatus,
                                SarReviewTask.STATUS_PENDING, SarReviewTask.STATUS_PROCESSING));
        if (updated == 0) {
            cancelledTasks.remove(taskId);
            throw new IllegalArgumentException("Task status changed and can no longer be cancelled");
        }
        task.setStatus(SarReviewTask.STATUS_CANCELLED);
        task.setFailReason("User cancelled");
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
        task.setQualityCheckEnabled(original.getQualityCheckEnabled());
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
        String status = task.getStatus();
        if (!SarReviewTask.STATUS_FAILED.equals(status)
                && !SarReviewTask.STATUS_COMPLETED.equals(status)
                && !SarReviewTask.STATUS_CANCELLED.equals(status)) {
            throw new IllegalArgumentException("Only failed, completed or cancelled tasks can be retried");
        }
        int updated = sarReviewTaskMapper.update(null,
                new LambdaUpdateWrapper<SarReviewTask>()
                        .eq(SarReviewTask::getId, taskId)
                        .eq(SarReviewTask::getStatus, status)
                        .set(SarReviewTask::getStatus, SarReviewTask.STATUS_PENDING)
                        .set(SarReviewTask::getFailReason, null)
                        .set(SarReviewTask::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw new IllegalArgumentException("Task status changed; retry was not started");
        }
        cancelledTasks.remove(taskId);
        task.setStatus(SarReviewTask.STATUS_PENDING);
        task.setFailReason(null);
        webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PENDING,
                "SAR 重新审查已进入队列...", 2);
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
        throwIfCancelled(taskId);
        webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                "SAR: 正在解析上传文档...", 8);

        List<WordParser.Chapter> rawChapters = WordParser.parseChapters(task.getFilePath());
        if (rawChapters.isEmpty() || rawChapters.stream().allMatch(ch -> ch.getContent().isBlank())) {
            throw new RuntimeException("Document content is empty or cannot be parsed");
        }
        throwIfCancelled(taskId);
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
        embedBlocks(taskId, blocks, embeddingModel);
        throwIfCancelled(taskId);
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
        if (!claimPendingTask(task)) {
            log.info("SAR async execution skipped because task is not pending: task={}, status={}",
                    taskId, sarReviewTaskMapper.selectStatusById(taskId));
            return;
        }
        String runStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        try {
            webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                    "SAR 审查启动，开始文档向量化...", 5);
            executeReview(task, runStamp);
        } catch (TaskCancelledException e) {
            log.info("SAR review task stopped after cancellation: {}", taskId);
            webSocketService.sendTaskUpdate(taskId, SarReviewTask.STATUS_CANCELLED, "Task cancelled by user");
        } catch (Exception e) {
            log.error("SAR review task failed: {}", taskId, e);
            if (failTaskIfProcessing(taskId, e.getMessage())) {
                webSocketService.sendTaskUpdate(taskId, SarReviewTask.STATUS_FAILED,
                        "审查失败: " + e.getMessage());
            }
        } finally {
            cancelledTasks.remove(taskId);
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
        throwIfCancelled(taskId);

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
        throwIfCancelled(taskId);
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

        // 主线二：文字质量审查。与业务规则向量召回分离，避免用 topK 召回漏掉全文分散型问题。
        boolean textQualityEnabled = !Boolean.FALSE.equals(task.getQualityCheckEnabled());
        TextQualityReviewResult qualityResult = TextQualityReviewResult.empty();
        if (textQualityEnabled) {
            throwIfCancelled(taskId);
            webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                    "开始文字质量审查主线：全文扫描、结构索引、术语一致性...", 92);
            qualityResult = runTextQualityPipeline(taskId, blocks, chapters, chatModel);
            for (Map<String, Object> row : qualityResult.rows()) {
                allCheckResults.add(row);
                statusCounts.merge(String.valueOf(row.get("status")), 1, Integer::sum);
                findingCount++;
            }
            appendTextQualityChunkResults(chunkResults, qualityResult.rows());
        }

        // 阶段三：两阶段复核——对每条非 Pass 违规独立复判，标注 verifyStatus（不删除、不降级为 Pass）。
        int verifiedCount = 0;
        int confirmedCount = 0;
        if (verifyEnabled) {
            throwIfCancelled(taskId);
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
        aiResult.put("reviewPipelines", buildReviewPipelineSummary(plans.size(), qualityResult));
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
        retrievalStats.put("textQualityEnabled", textQualityEnabled);
        retrievalStats.put("textQualityStats", qualityResult.stats());
        retrievalStats.put("consistencyEnabled", consistencyEnabled);
        retrievalStats.put("consistencyFindings", qualityResult.consistencyFindings());
        aiResult.put("retrievalStats", retrievalStats);

        completeTaskIfProcessing(task, aiResult);
        webSocketService.sendTaskUpdate(taskId, SarReviewTask.STATUS_COMPLETED,
                remainingFailedCount == 0
                        ? "SAR 审查完成：" + plans.size() + " 个业务检查项，"
                                + qualityResult.rows().size() + " 条文字质量/一致性发现"
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
        runBounded(taskId, indexedPlans, indexed -> {
            try {
                retrieved.put(indexed.index(),
                        routeCheck(taskId, indexed, embeddingModel, rerankerModel, sectionIndex));
            } catch (Exception e) {
                // 路由失败不致命：放空证据，仍进入评估（模型按"无证据/缺失"判定）。
                retrieved.put(indexed.index(), new RetrievedCheck(indexed, List.of(), false, 0.0, false));
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
        runBounded(taskId, bins, bin -> {
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
    private <T> void runBounded(String taskId, Iterable<T> items, Consumer<? super T> work) {
        Semaphore slots = new Semaphore(Math.max(1, checkConcurrency));
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (T item : items) {
            throwIfCancelled(taskId);
            try {
                slots.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    throwIfCancelled(taskId);
                    work.accept(item);
                } finally {
                    slots.release();
                }
            }, sarCheckExecutor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            if (rootCause(e) instanceof TaskCancelledException cancelled) throw cancelled;
            throw e;
        }
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
                    rerankerModel != null && !ev.isEmpty(), ev.isEmpty() ? 0.0 : 0.25, false);
        }

        // 区域级取证：短区域完整送入；长区域以语义/词法命中块为锚点向两侧扩展，
        // 避免简单截取区域开头而丢掉真正命中的后半段证据。
        int cap = Math.max(1, regionMaxBlocks);
        boolean regionComplete = region.size() <= cap;
        double selectedRegionScore = best;
        List<ScoredBlock> evidence = regionComplete
                ? region.stream().map(b -> new ScoredBlock(
                        b, selectedRegionScore, "complete_region")).toList()
                : selectRegionEvidence(region, recalled, buildRetrievalQuery(plan), cap);
        // 路由置信度：结构命中最可信；否则看与次优区域的分差。
        double routeConf = structuralKeys.contains(bestKey) ? 0.9
                : best >= 2 * secondBest ? 0.7
                : best > secondBest ? 0.5 : 0.3;
        return new RetrievedCheck(indexed, evidence, false, routeConf, regionComplete);
    }

    private List<ScoredBlock> selectRegionEvidence(List<SarDocumentBlock> region,
                                                    List<ScoredBlock> recalled,
                                                    String query,
                                                    int cap) {
        Map<String, Integer> indexByBlockId = new HashMap<>();
        Map<String, ScoredBlock> recalledByBlockId = new HashMap<>();
        for (int i = 0; i < region.size(); i++) {
            indexByBlockId.put(region.get(i).getBlockId(), i);
        }

        List<Integer> anchors = new ArrayList<>();
        int maxAnchors = Math.max(1, Math.min(4, cap));
        for (ScoredBlock item : recalled) {
            Integer index = indexByBlockId.get(item.block().getBlockId());
            if (index == null || anchors.contains(index)) continue;
            anchors.add(index);
            recalledByBlockId.put(item.block().getBlockId(), item);
            if (anchors.size() >= maxAnchors) break;
        }

        if (anchors.isEmpty()) {
            List<String> terms = lexicalTerms(query);
            List<int[]> lexical = new ArrayList<>();
            for (int i = 0; i < region.size(); i++) {
                String text = Objects.toString(region.get(i).getTextContent(), "").toLowerCase(Locale.ROOT);
                int hits = 0;
                for (String term : terms) if (text.contains(term)) hits++;
                if (hits > 0) lexical.add(new int[]{i, hits});
            }
            lexical.sort((a, b) -> {
                int byHits = Integer.compare(b[1], a[1]);
                return byHits != 0 ? byHits : Integer.compare(a[0], b[0]);
            });
            for (int[] item : lexical) {
                anchors.add(item[0]);
                if (anchors.size() >= maxAnchors) break;
            }
        }
        if (anchors.isEmpty()) anchors.add(0);

        Set<Integer> selected = new HashSet<>();
        for (int radius = 0; selected.size() < cap && radius < region.size(); radius++) {
            for (Integer anchor : anchors) {
                int left = anchor - radius;
                int right = anchor + radius;
                if (left >= 0) selected.add(left);
                if (selected.size() >= cap) break;
                if (right < region.size()) selected.add(right);
                if (selected.size() >= cap) break;
            }
        }

        return selected.stream().sorted().limit(cap).map(index -> {
            SarDocumentBlock block = region.get(index);
            ScoredBlock recalledItem = recalledByBlockId.get(block.getBlockId());
            return recalledItem != null
                    ? new ScoredBlock(block, recalledItem.score(), "route_anchor")
                    : new ScoredBlock(block, 0.0, "route_context");
        }).toList();
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
                    new RetrievedCheck(indexed, List.of(), false, 0.0, false));
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
                    List<Map<String, Object>> rows = buildRowsFromResult(
                            plan, res, rc.evidence(), rc.routeConfidence(), rc.regionComplete());
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
        sb.append("【区域证据】以下是各检查项预期所在章节/区域的原文证据（按阅读顺序）。"
                + "仅当检查项标记为 COMPLETE 时，才可因整个区域缺失要求内容判 Fail；"
                + "PARTIAL 表示长区域抽样，不得仅凭未看到判缺失，应判 Review。\n");
        if (bin.unionEvidence().isEmpty()) {
            sb.append("(未定位到可靠区域证据；不得据此判内容缺失，应返回 Review)\n");
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
                sb.append(rc.regionComplete()
                        ? "（完整区域：若区域内不存在满足通过标准的内容可判 Fail）"
                        : "（部分区域：未看到要求内容时只能判 Review，不可判缺失）");
            }
            sb.append('\n');
            sb.append("  证据覆盖: ").append(rc.regionComplete() ? "COMPLETE" : "PARTIAL").append('\n');
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
        return buildRowsFromResult(plan, res, evidence, routeConfidence, true);
    }

    private List<Map<String, Object>> buildRowsFromResult(CheckPlan plan, Map<String, Object> res,
                                                           List<ScoredBlock> evidence, double routeConfidence,
                                                           boolean evidenceComplete) {
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
            String coverageAdjustedStatus = statusForEvidenceCoverage(
                    status, evidenceComplete, false);
            if (!coverageAdjustedStatus.equals(status)) {
                status = coverageAdjustedStatus;
                confidence = "needs_review";
                reason = "当前仅覆盖长区域的部分证据，不能据此确认内容缺失。"
                        + (reason.isBlank() ? "" : " 模型原判定：" + reason);
            }
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
        String failureReason = "单项审查失败，自动补审后仍未成功：" + rootErrorMessage(attempt.error());
        check.put("finding_id", buildFindingId(
                plan, attempt.evidence(), "", failureReason, 0));
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
        check.put("reason", failureReason);
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

    // ---------------- 主线二：文字质量审查 ----------------

    private TextQualityReviewResult runTextQualityPipeline(String taskId,
                                                           List<SarDocumentBlock> blocks,
                                                           List<WordParser.Chapter> chapters,
                                                           AiModelConfig chatModel) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", true);
        Map<String, QualityCheckTemplate> templates = qualityCheckTemplatesByCode();

        if (qualityFullScanEnabled) {
            try {
                throwIfCancelled(taskId);
                webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                        "文字质量审查：全文切片扫描...", 92);
                List<Map<String, Object>> fullScanRows =
                        runFullScanQualityReview(taskId, blocks, chatModel, templates);
                rows.addAll(fullScanRows);
                stats.put("fullScanFindings", fullScanRows.size());
            } catch (TaskCancelledException e) {
                throw e;
            } catch (Exception e) {
                log.warn("SAR text quality full scan failed: task={}, err={}", taskId, rootErrorMessage(e));
                stats.put("fullScanError", rootErrorMessage(e));
                rows.addAll(buildQualityExecutionErrorRows(
                        qualityTemplates(templates, "R-Q-C001", "R-Q-C002"),
                        List.of(), "FULL_SCAN", "全文切片扫描", rootErrorMessage(e)));
            }
        } else {
            stats.put("fullScanFindings", 0);
            stats.put("fullScanSkipped", true);
        }

        if (qualityStructureIndexEnabled) {
            try {
                throwIfCancelled(taskId);
                webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                        "文字质量审查：图表/章节结构索引检查...", 94);
                List<Map<String, Object>> structureRows =
                        runStructureIndexQualityReview(blocks, templates);
                rows.addAll(structureRows);
                stats.put("structureIndexFindings", structureRows.size());
            } catch (TaskCancelledException e) {
                throw e;
            } catch (Exception e) {
                log.warn("SAR text quality structure-index check failed: task={}, err={}",
                        taskId, rootErrorMessage(e));
                stats.put("structureIndexError", rootErrorMessage(e));
                rows.addAll(buildQualityExecutionErrorRows(
                        qualityTemplates(templates, "R-Q-C004", "R-Q-C005", "R-Q-C006", "R-Q-C007"),
                        List.of(), "STRUCTURE_INDEX", "结构化索引检查", rootErrorMessage(e)));
            }
        } else {
            stats.put("structureIndexFindings", 0);
            stats.put("structureIndexSkipped", true);
        }

        if (qualityTerminologyEnabled) {
            try {
                throwIfCancelled(taskId);
                webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                        "文字质量审查：全文术语一致性审查...", 95);
                List<Map<String, Object>> terminologyRows =
                        runTerminologyQualityReview(taskId, blocks, chatModel, templates);
                rows.addAll(terminologyRows);
                stats.put("terminologyFindings", terminologyRows.size());
            } catch (TaskCancelledException e) {
                throw e;
            } catch (Exception e) {
                log.warn("SAR terminology consistency check failed: task={}, err={}",
                        taskId, rootErrorMessage(e));
                stats.put("terminologyError", rootErrorMessage(e));
                rows.addAll(buildQualityExecutionErrorRows(
                        qualityTemplates(templates, "R-Q-C003"),
                        List.of(), "TERMINOLOGY_CONSISTENCY", "术语一致性审查", rootErrorMessage(e)));
            }
        } else {
            stats.put("terminologyFindings", 0);
            stats.put("terminologySkipped", true);
        }

        int consistencyFindings = 0;
        if (consistencyEnabled && chapters.size() >= 2) {
            try {
                throwIfCancelled(taskId);
                webSocketService.sendTaskProgress(taskId, SarReviewTask.STATUS_PROCESSING,
                        "文字质量审查：跨章一致性核对...", 96);
                List<Map<String, Object>> consistencyRows = runConsistencyPass(taskId, chapters, chatModel);
                for (Map<String, Object> row : consistencyRows) {
                    annotateTextQualityRow(row, "CROSS_CHAPTER_CONSISTENCY", "跨章一致性审查");
                }
                rows.addAll(consistencyRows);
                consistencyFindings = consistencyRows.size();
                stats.put("crossChapterConsistencyFindings", consistencyFindings);
            } catch (TaskCancelledException e) {
                throw e;
            } catch (Exception e) {
                log.warn("SAR cross-chapter consistency check failed: task={}, err={}",
                        taskId, rootErrorMessage(e));
                stats.put("crossChapterConsistencyError", rootErrorMessage(e));
                Map<String, Object> errorRow = buildConsistencyRow(0, 1, "",
                        "跨章一致性审查执行失败：" + rootErrorMessage(e), "",
                        "请重新审查，或对跨章节参数和术语执行人工复核。");
                errorRow.put("status", "Review");
                errorRow.put("confidence", "needs_review");
                annotateTextQualityRow(errorRow, "CROSS_CHAPTER_CONSISTENCY", "跨章一致性审查");
                rows.add(errorRow);
            }
        } else {
            stats.put("crossChapterConsistencyFindings", 0);
            stats.put("crossChapterConsistencySkipped", !consistencyEnabled || chapters.size() < 2);
        }

        stats.put("totalTextQualityFindings", rows.size());
        return new TextQualityReviewResult(rows, stats, consistencyFindings);
    }

    private List<Map<String, Object>> runFullScanQualityReview(String taskId,
                                                               List<SarDocumentBlock> blocks,
                                                               AiModelConfig chatModel,
                                                               Map<String, QualityCheckTemplate> templates) throws Exception {
        List<QualityCheckTemplate> checks = List.of(
                templates.getOrDefault(RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C001",
                        defaultQualityCheckTemplates().get(RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C001")),
                templates.getOrDefault(RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C002",
                        defaultQualityCheckTemplates().get(RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C002")))
                .stream()
                .filter(Objects::nonNull)
                .toList();
        if (checks.isEmpty() || blocks.isEmpty()) return List.of();

        int limit = qualityFullScanMaxBlocks > 0
                ? Math.min(blocks.size(), qualityFullScanMaxBlocks)
                : blocks.size();
        int batchSize = Math.max(1, qualityFullScanBatchBlocks);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int start = 0; start < limit; start += batchSize) {
            throwIfCancelled(taskId);
            int end = Math.min(limit, start + batchSize);
            List<ScoredBlock> evidence = blocks.subList(start, end).stream()
                    .map(block -> new ScoredBlock(block, 1.0, "full_scan"))
                    .toList();
            try {
                Map<String, Map<String, Object>> byCode = callQualityChecks(
                        taskId,
                        chatModel,
                        checks,
                        evidence,
                        "FULL_SCAN",
                        "请逐块检查错别字、漏字、多字、重复词、明显标点错误、语病、语序不当和歧义。"
                                + "只报告能用原文证据定位的问题；未发现问题的检查项返回 Pass 且 findings 为空。",
                        300000 + start);
                for (QualityCheckTemplate check : checks) {
                    rows.addAll(buildRowsFromQualityResult(check, byCode.get(check.checkCode()),
                            evidence, "FULL_SCAN", "全文切片扫描"));
                }
            } catch (TaskCancelledException e) {
                throw e;
            } catch (Exception e) {
                log.warn("SAR text quality batch failed: task={}, blocks={}-{}, err={}",
                        taskId, start, end - 1, rootErrorMessage(e));
                rows.addAll(buildQualityExecutionErrorRows(checks, evidence,
                        "FULL_SCAN", "全文切片扫描", rootErrorMessage(e)));
            }
        }
        return rows;
    }

    private List<Map<String, Object>> runStructureIndexQualityReview(List<SarDocumentBlock> blocks,
                                                                     Map<String, QualityCheckTemplate> templates) {
        List<FigureTableMention> mentions = extractFigureTableMentions(blocks);
        if (mentions.isEmpty()) return List.of();

        QualityCheckTemplate uniqueness = templates.getOrDefault(
                RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C004",
                defaultQualityCheckTemplates().get(RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C004"));
        QualityCheckTemplate refExists = templates.getOrDefault(
                RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C005",
                defaultQualityCheckTemplates().get(RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C005"));
        QualityCheckTemplate order = templates.getOrDefault(
                RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C006",
                defaultQualityCheckTemplates().get(RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C006"));
        QualityCheckTemplate allReferenced = templates.getOrDefault(
                RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C007",
                defaultQualityCheckTemplates().get(RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C007"));

        List<Map<String, Object>> rows = new ArrayList<>();
        List<FigureTableMention> captions = mentions.stream().filter(FigureTableMention::caption).toList();
        List<FigureTableMention> refs = mentions.stream().filter(m -> !m.caption()).toList();

        Map<String, List<FigureTableMention>> captionsByKey = new LinkedHashMap<>();
        for (FigureTableMention caption : captions) {
            captionsByKey.computeIfAbsent(caption.key(), k -> new ArrayList<>()).add(caption);
        }
        for (Map.Entry<String, List<FigureTableMention>> entry : captionsByKey.entrySet()) {
            if (entry.getValue().size() <= 1) continue;
            rows.add(buildQualityFindingRow(uniqueness, "Fail",
                    "发现重复的" + entry.getValue().get(0).kind() + "编号：" + entry.getKey(),
                    joinMentionEvidence(entry.getValue()),
                    "请保证全文图号、表号唯一，重复编号应重新编号并同步修改正文引用。",
                    "high",
                    scoredMentions(entry.getValue(), "structure_index"),
                    "STRUCTURE_INDEX",
                    "结构化索引检查"));
        }

        Map<String, Set<String>> numberStyles = new LinkedHashMap<>();
        for (FigureTableMention mention : mentions) {
            numberStyles.computeIfAbsent(mention.kind(), k -> new HashSet<>())
                    .add(numberStyle(mention.rawNumber()));
        }
        for (Map.Entry<String, Set<String>> entry : numberStyles.entrySet()) {
            if (entry.getValue().size() <= 1) continue;
            rows.add(buildQualityFindingRow(uniqueness, "Fail",
                    entry.getKey() + "编号存在阿拉伯数字/中文数字等格式混用。",
                    entry.getKey() + "编号格式集合：" + entry.getValue(),
                    "请统一图号、表号的编号格式。",
                    "medium",
                    scoredMentions(mentions.stream().filter(m -> entry.getKey().equals(m.kind())).toList(),
                            "structure_index"),
                    "STRUCTURE_INDEX",
                    "结构化索引检查"));
        }

        Set<String> captionKeys = new HashSet<>(captionsByKey.keySet());
        Map<String, List<FigureTableMention>> refsByKey = new LinkedHashMap<>();
        for (FigureTableMention ref : refs) {
            refsByKey.computeIfAbsent(ref.key(), k -> new ArrayList<>()).add(ref);
        }
        for (Map.Entry<String, List<FigureTableMention>> entry : refsByKey.entrySet()) {
            if (captionKeys.contains(entry.getKey())) continue;
            rows.add(buildQualityFindingRow(refExists, "Fail",
                    "正文引用了不存在的图表编号：" + entry.getKey(),
                    joinMentionEvidence(entry.getValue()),
                    "请补充对应图表，或修正文中引用编号。",
                    "high",
                    scoredMentions(entry.getValue(), "structure_index"),
                    "STRUCTURE_INDEX",
                    "结构化索引检查"));
        }

        rows.addAll(checkFigureTableSequence(captions, order));

        Set<String> referencedKeys = new HashSet<>(refsByKey.keySet());
        for (Map.Entry<String, List<FigureTableMention>> entry : captionsByKey.entrySet()) {
            if (referencedKeys.contains(entry.getKey())) continue;
            rows.add(buildQualityFindingRow(allReferenced, "Fail",
                    "存在未被正文引用的图表：" + entry.getKey(),
                    joinMentionEvidence(entry.getValue()),
                    "请在正文中引用该图表，或删除未使用的图表。",
                    "medium",
                    scoredMentions(entry.getValue(), "structure_index"),
                    "STRUCTURE_INDEX",
                    "结构化索引检查"));
        }
        return rows;
    }

    private List<Map<String, Object>> checkFigureTableSequence(List<FigureTableMention> captions,
                                                               QualityCheckTemplate template) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, List<FigureTableMention>> byKind = new LinkedHashMap<>();
        for (FigureTableMention caption : captions) {
            byKind.computeIfAbsent(caption.kind(), k -> new ArrayList<>()).add(caption);
        }
        for (Map.Entry<String, List<FigureTableMention>> entry : byKind.entrySet()) {
            Integer previous = null;
            FigureTableMention previousMention = null;
            for (FigureTableMention caption : entry.getValue()) {
                Integer current = parseSimplePositiveNumber(caption.rawNumber());
                if (current == null) continue;
                if (previous != null && (current < previous || current > previous + 1)) {
                    List<FigureTableMention> evidence = previousMention == null
                            ? List.of(caption) : List.of(previousMention, caption);
                    rows.add(buildQualityFindingRow(template, "Fail",
                            entry.getKey() + "编号未按出现顺序连续递增。",
                            joinMentionEvidence(evidence),
                            "请按正文出现顺序重新编号，并同步修正文中引用。",
                            "high",
                            scoredMentions(evidence, "structure_index"),
                            "STRUCTURE_INDEX",
                            "结构化索引检查"));
                }
                previous = current;
                previousMention = caption;
            }
        }
        return rows;
    }

    private List<Map<String, Object>> runTerminologyQualityReview(String taskId,
                                                                  List<SarDocumentBlock> blocks,
                                                                  AiModelConfig chatModel,
                                                                  Map<String, QualityCheckTemplate> templates) throws Exception {
        QualityCheckTemplate check = templates.getOrDefault(
                RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C003",
                defaultQualityCheckTemplates().get(RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C003"));
        if (check == null) return List.of();
        List<TermObservation> observations = selectTerminologyObservations(
                extractTerminologyObservations(blocks));
        long distinctTerms = observations.stream().map(TermObservation::term).distinct().count();
        if (observations.size() < 2 || distinctTerms < 2) return List.of();

        List<ScoredBlock> evidence = observations.stream()
                .map(obs -> new ScoredBlock(obs.block(), 1.0, "terminology_index"))
                .collect(LinkedHashMap<String, ScoredBlock>::new,
                        (map, item) -> map.putIfAbsent(item.block().getBlockId(), item),
                        LinkedHashMap::putAll)
                .values().stream().toList();
        String prompt = buildTerminologyQualityPrompt(check, observations);
        Map<String, Map<String, Object>> byCode = callQualityPrompt(
                taskId, chatModel, prompt, 400000, ReviewResultSchema.RAG_GROUP_SCHEMA_NAME);
        if (!byCode.containsKey(check.checkCode())) {
            throw new IllegalStateException("model omitted check_code " + check.checkCode());
        }
        return buildRowsFromQualityResult(check, byCode.get(check.checkCode()), evidence,
                "TERMINOLOGY_CONSISTENCY", "术语一致性审查");
    }

    private Map<String, Map<String, Object>> callQualityChecks(String taskId,
                                                               AiModelConfig chatModel,
                                                               List<QualityCheckTemplate> checks,
                                                               List<ScoredBlock> evidence,
                                                               String mechanism,
                                                               String instruction,
                                                               int seed) throws Exception {
        String prompt = buildQualityPrompt(checks, evidence, mechanism, instruction);
        Map<String, Map<String, Object>> byCode = callQualityPrompt(
                taskId, chatModel, prompt, seed, ReviewResultSchema.RAG_GROUP_SCHEMA_NAME);
        for (QualityCheckTemplate check : checks) {
            if (!byCode.containsKey(check.checkCode())) {
                throw new IllegalStateException("model omitted check_code " + check.checkCode());
            }
        }
        return byCode;
    }

    Map<String, Map<String, Object>> callQualityPrompt(String taskId,
                                                       AiModelConfig chatModel,
                                                       String prompt,
                                                       int seed,
                                                       String schemaName) throws Exception {
        AiCallOptions options = AiCallOptions.builder()
                .temperature(0.0)
                .topP(1.0)
                .maxTokensOverride(8192)
                .seed(stableSeed(taskId, seed))
                .enablePromptCache(true)
                .structuredSchema(com.alibaba.fastjson2.JSON.parseObject(
                        com.alibaba.fastjson2.JSON.toJSONString(ReviewResultSchema.ragGroupSchema())))
                .structuredSchemaName(schemaName)
                .build();
        String response = aiModelService.callAiModel(chatModel, SAR_TEXT_QUALITY_SYSTEM_PROMPT, prompt, options);
        Map<String, Object> parsed = parseJson(response);
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        Object results = parsed.get("results");
        if (!(results instanceof List<?> list)) {
            throw new IllegalStateException("quality model response omitted results array");
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rm = (Map<String, Object>) m;
                String code = Objects.toString(rm.get("check_code"), "");
                if (!code.isBlank()) byCode.put(code, rm);
            }
        }
        return byCode;
    }

    String statusForEvidenceCoverage(String status, boolean evidenceComplete,
                                     boolean hasConcreteFindings) {
        if (!evidenceComplete && !hasConcreteFindings && "Fail".equals(status)) {
            return "Review";
        }
        return status;
    }

    private String buildQualityPrompt(List<QualityCheckTemplate> checks,
                                      List<ScoredBlock> evidence,
                                      String mechanism,
                                      String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("【执行机制】").append(mechanism).append('\n')
                .append(instruction).append("\n\n")
                .append("【待审查原文切片】\n");
        for (ScoredBlock item : evidence) {
            SarDocumentBlock block = item.block();
            sb.append("[").append(block.getBlockId()).append("] ")
                    .append(block.getSectionPath()).append('\n')
                    .append(block.getTextContent()).append("\n\n");
        }
        sb.append("【文字质量检查项】必须对每个 check_code 返回一条 result；"
                + "没有问题时 status=Pass 且 findings=[]；发现问题时每处问题写入 findings。\n");
        for (QualityCheckTemplate check : checks) {
            sb.append("- check_code: ").append(check.checkCode()).append('\n')
                    .append("  检查问题: ").append(check.question()).append('\n')
                    .append("  通过标准: ").append(check.passCriteria()).append('\n');
        }
        return sb.toString();
    }

    private String buildTerminologyQualityPrompt(QualityCheckTemplate check,
                                                 List<TermObservation> observations) {
        StringBuilder sb = new StringBuilder();
        sb.append("【执行机制】TERMINOLOGY_CONSISTENCY\n")
                .append("下面是全文切片扫描得到的术语/缩略语/设备称谓出现索引。")
                .append("请只基于索引中的原文证据判断同一对象是否存在多种称谓、同一缩写是否含义不一致、")
                .append("中英文/简称是否前后混用。没有明确不一致时返回 Pass。\n\n")
                .append("【术语出现索引】\n");
        int idx = 1;
        for (TermObservation obs : observations) {
            sb.append(idx++).append(". term=").append(obs.term())
                    .append(" | location=").append(obs.block().getSectionPath())
                    .append(" | source=").append(obs.block().getBlockId())
                    .append(" | evidence=").append(obs.evidence()).append('\n');
        }
        sb.append("\n【待判定检查项】\n")
                .append("- check_code: ").append(check.checkCode()).append('\n')
                .append("  检查问题: ").append(check.question()).append('\n')
                .append("  通过标准: ").append(check.passCriteria()).append('\n')
                .append("\n输出 findings 时，evidence 必须同时引用至少两处相互冲突的术语证据。");
        return sb.toString();
    }

    private List<Map<String, Object>> buildRowsFromQualityResult(QualityCheckTemplate template,
                                                                 Map<String, Object> result,
                                                                 List<ScoredBlock> evidence,
                                                                 String mechanism,
                                                                 String mechanismLabel) {
        if (template == null || result == null) return List.of();
        List<Map<String, Object>> rows = buildRowsFromResult(toQualityPlan(template), result, evidence, 0.9);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if ("Pass".equals(normalizeCheckStatus(row.get("status")))) continue;
            annotateTextQualityRow(row, mechanism, mechanismLabel);
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> buildQualityFindingRow(QualityCheckTemplate template,
                                                       String status,
                                                       String reason,
                                                       String evidenceText,
                                                       String suggestion,
                                                       String confidence,
                                                       List<ScoredBlock> evidence,
                                                       String mechanism,
                                                       String mechanismLabel) {
        Map<String, Object> row = buildRow(toQualityPlan(template), status, reason, evidenceText,
                suggestion, confidence, evidence, evidence.isEmpty() ? null : evidence.get(0), 0, 1);
        annotateTextQualityRow(row, mechanism, mechanismLabel);
        return row;
    }

    private List<Map<String, Object>> buildQualityExecutionErrorRows(
            List<QualityCheckTemplate> checks,
            List<ScoredBlock> evidence,
            String mechanism,
            String mechanismLabel,
            String error) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QualityCheckTemplate check : checks) {
            if (check == null) continue;
            rows.add(buildQualityFindingRow(check, "Review",
                    "该检查项未完成可靠审查：" + firstNonBlank(error, "模型未返回有效结果"),
                    "", "请重新执行该检查项，或进行人工复核。", "needs_review",
                    evidence, mechanism, mechanismLabel));
        }
        return rows;
    }

    private List<QualityCheckTemplate> qualityTemplates(
            Map<String, QualityCheckTemplate> templates, String... codes) {
        List<QualityCheckTemplate> out = new ArrayList<>();
        Map<String, QualityCheckTemplate> defaults = defaultQualityCheckTemplates();
        for (String code : codes) {
            QualityCheckTemplate template = templates.getOrDefault(code, defaults.get(code));
            if (template != null) out.add(template);
        }
        return out;
    }

    private void annotateTextQualityRow(Map<String, Object> row, String mechanism, String mechanismLabel) {
        row.put("mainLine", "TEXT_QUALITY_REVIEW");
        row.put("reviewPipeline", "文字质量审查");
        row.put("executionMechanism", mechanism);
        row.put("executionMechanismLabel", mechanismLabel);
        row.putIfAbsent("rule_code", RuleDispatcher.BASIC_QUALITY_RULE_CODE);
        row.putIfAbsent("ruleName", RuleDispatcher.BASIC_QUALITY_RULE_NAME);
        if (Objects.toString(row.get("ruleName"), "").isBlank()) {
            row.put("ruleName", RuleDispatcher.BASIC_QUALITY_RULE_NAME);
        }
        row.putIfAbsent("ruleDescription", "SAR 文字质量审查主线：全文扫描、结构化索引、术语一致性与跨章一致性。");
        if (Objects.toString(row.get("ruleDescription"), "").isBlank()) {
            row.put("ruleDescription", "SAR 文字质量审查主线：全文扫描、结构化索引、术语一致性与跨章一致性。");
        }
    }

    private CheckPlan toQualityPlan(QualityCheckTemplate template) {
        return new CheckPlan(null, null,
                RuleDispatcher.BASIC_QUALITY_RULE_CODE,
                template.checkCode(),
                template.question(),
                template.passCriteria(),
                template.category());
    }

    private Map<String, QualityCheckTemplate> qualityCheckTemplatesByCode() {
        Map<String, QualityCheckTemplate> checks = new LinkedHashMap<>(defaultQualityCheckTemplates());
        Rule dbRule = loadEditableQualityRule();
        if (dbRule == null || dbRule.getId() == null) return checks;
        try {
            List<RuleCheck> dbChecks = ruleCheckMapper.findActiveByRuleId(dbRule.getId());
            if (dbChecks != null) {
                for (RuleCheck check : dbChecks) {
                    String code = firstNonBlank(check.getCheckCode(), "");
                    if (code.isBlank()) continue;
                    checks.put(code, new QualityCheckTemplate(
                            code,
                            firstNonBlank(check.getQuestion(), code),
                            firstNonBlank(check.getPassCriteria(), ""),
                            firstNonBlank(check.getCategory(), "其他")));
                }
            }
        } catch (Exception e) {
            log.warn("加载可编辑基础文字质量检查项失败，回退默认项：{}", e.getMessage());
        }
        return checks;
    }

    private Rule loadEditableQualityRule() {
        try {
            Long id = ruleMapper.findIdByRuleCode(RuleDispatcher.BASIC_QUALITY_RULE_CODE);
            return id == null ? null : ruleMapper.selectById(id);
        } catch (Exception e) {
            log.warn("加载基础文字质量规则失败，使用默认定义：{}", e.getMessage());
            return null;
        }
    }

    private Map<String, QualityCheckTemplate> defaultQualityCheckTemplates() {
        Map<String, QualityCheckTemplate> checks = new LinkedHashMap<>();
        checks.put("R-Q-C001", new QualityCheckTemplate("R-Q-C001",
                "是否存在错别字、漏字、多字、重复词或明显标点错误",
                "未发现错别字、漏字、多字、重复词或明显标点错误", "其他"));
        checks.put("R-Q-C002", new QualityCheckTemplate("R-Q-C002",
                "语句是否通顺，是否存在语序不当、语病或明显歧义",
                "语句通顺、语义明确，不存在语序不当、语病或明显歧义", "逻辑一致性"));
        checks.put("R-Q-C003", new QualityCheckTemplate("R-Q-C003",
                "全文范围内术语、缩略语、设备名称、试验项目和参数名称是否一致",
                "全文范围内相同对象的术语、名称和称谓保持一致", "术语一致性"));
        checks.put("R-Q-C004", new QualityCheckTemplate("R-Q-C004",
                "图号、表号是否全文唯一不重复、编号格式统一？",
                "编号唯一且格式统一即通过；存在重复编号或格式不统一则不通过。", "格式"));
        checks.put("R-Q-C005", new QualityCheckTemplate("R-Q-C005",
                "正文引用的图号/表号是否真实存在，且引用编号与图表实际编号一致、表述统一？",
                "引用均真实且编号表述一致即通过；引用不存在的编号或表述混乱则不通过。", "逻辑一致性"));
        checks.put("R-Q-C006", new QualityCheckTemplate("R-Q-C006",
                "图表编号是否按出现顺序递增、不跳号、不倒序？",
                "顺序合理即通过；跳号或倒序则不通过。", "逻辑一致性"));
        checks.put("R-Q-C007", new QualityCheckTemplate("R-Q-C007",
                "是否所有图表均被正文引用？",
                "图表均被引用即通过；存在未被引用的图表则不通过。", "完整性"));
        return checks;
    }

    private List<FigureTableMention> extractFigureTableMentions(List<SarDocumentBlock> blocks) {
        List<FigureTableMention> mentions = new ArrayList<>();
        int order = 0;
        for (SarDocumentBlock block : blocks) {
            String text = block.getTextContent();
            if (text == null || text.isBlank()) continue;
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) continue;
                Matcher captionMatcher = FIGURE_TABLE_CAPTION.matcher(trimmed);
                boolean caption = captionMatcher.find() && isLikelyFigureTableCaption(trimmed);
                if (caption) {
                    String kind = captionMatcher.group(1);
                    String number = captionMatcher.group(2);
                    mentions.add(new FigureTableMention(kind, number, normalizeFigureTableNumber(number),
                            trimmed, block, true, order++));
                    continue;
                }
                Matcher refMatcher = FIGURE_TABLE_REFERENCE.matcher(trimmed);
                while (refMatcher.find()) {
                    String kind = refMatcher.group(1);
                    String number = refMatcher.group(2);
                    mentions.add(new FigureTableMention(kind, number, normalizeFigureTableNumber(number),
                            trimmed, block, false, order++));
                }
            }
        }
        return mentions;
    }

    private boolean isLikelyFigureTableCaption(String line) {
        if (line.length() > 120) return false;
        return !(line.contains("见图") || line.contains("见表")
                || line.contains("如图") || line.contains("如表")
                || line.contains("所示") || line.contains("参见")
                || line.contains("详见"));
    }

    private String joinMentionEvidence(List<FigureTableMention> mentions) {
        StringBuilder sb = new StringBuilder();
        for (FigureTableMention mention : mentions) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[").append(mention.block().getBlockId()).append("] ")
                    .append(mention.block().getSectionPath()).append("：")
                    .append(mention.text());
        }
        return sb.toString();
    }

    private List<ScoredBlock> scoredMentions(List<FigureTableMention> mentions, String reason) {
        LinkedHashMap<String, ScoredBlock> byBlock = new LinkedHashMap<>();
        for (FigureTableMention mention : mentions) {
            byBlock.putIfAbsent(mention.block().getBlockId(),
                    new ScoredBlock(mention.block(), 1.0, reason));
        }
        return new ArrayList<>(byBlock.values());
    }

    private String normalizeFigureTableNumber(String raw) {
        if (raw == null) return "";
        return toHalfWidth(raw).replace('．', '.').replace('－', '-').trim();
    }

    private String numberStyle(String raw) {
        String s = normalizeFigureTableNumber(raw);
        if (s.matches("[0-9]+([.\\-][0-9]+)*")) return "arabic";
        if (s.matches("[一二三四五六七八九十百]+")) return "chinese";
        return "mixed";
    }

    private Integer parseSimplePositiveNumber(String raw) {
        String s = normalizeFigureTableNumber(raw);
        if (!s.matches("[0-9]+")) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toHalfWidth(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') {
                out.append((char) ('0' + c - '０'));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private List<TermObservation> extractTerminologyObservations(List<SarDocumentBlock> blocks) {
        List<TermObservation> observations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SarDocumentBlock block : blocks) {
            String text = block.getTextContent();
            if (text == null || text.isBlank()) continue;
            for (String term : KNOWN_TERMINOLOGY_ALIASES) {
                if (!text.contains(term)) continue;
                addTermObservation(observations, seen, term, block, snippetAround(text, term));
            }
            Matcher matcher = UPPERCASE_TERM.matcher(text);
            while (matcher.find()) {
                String term = matcher.group();
                if (UPPERCASE_TERM_STOPWORDS.contains(term)) continue;
                addTermObservation(observations, seen, term, block, snippetAround(text, term));
            }
            Matcher aliasMatcher = TERM_ALIAS_PAIR.matcher(text);
            while (aliasMatcher.find()) {
                addTermObservation(observations, seen, aliasMatcher.group(1), block,
                        snippetAround(text, aliasMatcher.group()));
                addTermObservation(observations, seen, aliasMatcher.group(2), block,
                        snippetAround(text, aliasMatcher.group()));
            }
            Matcher chineseMatcher = CHINESE_TECH_TERM.matcher(text);
            int chineseTermsInBlock = 0;
            while (chineseMatcher.find() && chineseTermsInBlock < 12) {
                String term = chineseMatcher.group(1);
                addTermObservation(observations, seen, term, block, snippetAround(text, term));
                chineseTermsInBlock++;
            }
        }
        return observations;
    }

    private List<TermObservation> selectTerminologyObservations(List<TermObservation> observations) {
        int limit = Math.max(2, qualityTerminologyMaxObservations);
        if (observations.size() <= limit) return observations;

        Map<String, List<TermObservation>> byTerm = new LinkedHashMap<>();
        for (TermObservation observation : observations) {
            byTerm.computeIfAbsent(observation.term(), ignored -> new ArrayList<>()).add(observation);
        }
        List<Map.Entry<String, List<TermObservation>>> ranked = new ArrayList<>(byTerm.entrySet());
        ranked.sort((left, right) -> {
            int leftKnown = KNOWN_TERMINOLOGY_ALIASES.contains(left.getKey()) ? 1 : 0;
            int rightKnown = KNOWN_TERMINOLOGY_ALIASES.contains(right.getKey()) ? 1 : 0;
            int byKnown = Integer.compare(rightKnown, leftKnown);
            if (byKnown != 0) return byKnown;
            long leftChapters = left.getValue().stream()
                    .map(item -> item.block().getChapterIndex()).distinct().count();
            long rightChapters = right.getValue().stream()
                    .map(item -> item.block().getChapterIndex()).distinct().count();
            int byChapters = Long.compare(rightChapters, leftChapters);
            return byChapters != 0 ? byChapters
                    : Integer.compare(right.getValue().size(), left.getValue().size());
        });

        LinkedHashMap<String, TermObservation> selected = new LinkedHashMap<>();
        for (Map.Entry<String, List<TermObservation>> entry : ranked) {
            if (selected.size() >= limit) break;
            List<TermObservation> values = entry.getValue();
            addSelectedTermObservation(selected, values.get(0));
            if (selected.size() < limit && values.size() > 1) {
                addSelectedTermObservation(selected, values.get(values.size() - 1));
            }
        }
        if (selected.size() < limit) {
            double step = (double) observations.size() / (limit - selected.size());
            for (double cursor = 0; selected.size() < limit && cursor < observations.size(); cursor += step) {
                addSelectedTermObservation(selected, observations.get(Math.min(
                        observations.size() - 1, (int) cursor)));
            }
        }
        return new ArrayList<>(selected.values());
    }

    private void addSelectedTermObservation(Map<String, TermObservation> selected,
                                            TermObservation observation) {
        String key = observation.term() + "|" + observation.block().getBlockId()
                + "|" + observation.evidence();
        selected.putIfAbsent(key, observation);
    }

    private void addTermObservation(List<TermObservation> observations, Set<String> seen,
                                    String term, SarDocumentBlock block, String evidence) {
        String key = term + "|" + block.getBlockId() + "|" + evidence;
        if (!seen.add(key)) return;
        observations.add(new TermObservation(term, evidence, block));
    }

    private String snippetAround(String text, String needle) {
        if (text == null || text.isBlank()) return "";
        int idx = needle == null || needle.isBlank() ? -1 : text.indexOf(needle);
        if (idx < 0) {
            String trimmed = text.trim();
            return trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed;
        }
        int start = Math.max(0, idx - 60);
        int end = Math.min(text.length(), idx + needle.length() + 80);
        return text.substring(start, end).replaceAll("\\s+", " ").trim();
    }

    private void appendTextQualityChunkResults(List<Map<String, Object>> chunkResults,
                                               List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Map<String, Object> chunkResult = new LinkedHashMap<>();
            chunkResult.put("chunk", chunkResults.size() + 1);
            chunkResult.put("chapterTitle", Objects.toString(
                    row.getOrDefault("executionMechanismLabel", "文字质量审查"), "文字质量审查"));
            chunkResult.put("totalChunks", chunkResults.size() + 1);
            chunkResult.put("sourceRefs", row.get("sourceRefs"));
            chunkResult.put("appliedRules", List.of(Objects.toString(
                    row.getOrDefault("ruleName", RuleDispatcher.BASIC_QUALITY_RULE_NAME), "")));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("summary", Objects.toString(row.getOrDefault("reason", ""), ""));
            result.put("issues", new ArrayList<>());
            result.put("passed_items", new ArrayList<>());
            result.put("check_results", List.of(row));
            chunkResult.put("result", result);
            chunkResults.add(chunkResult);
        }
    }

    private List<Map<String, Object>> buildReviewPipelineSummary(int businessCheckCount,
                                                                 TextQualityReviewResult qualityResult) {
        List<Map<String, Object>> pipelines = new ArrayList<>();
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("mainLine", "BUSINESS_RULE_REVIEW");
        business.put("label", "业务规则审查");
        business.put("checkCount", businessCheckCount);
        business.put("executionMechanisms", List.of(
                "STRUCTURAL_ROUTE", "LEXICAL_ROUTE", "VECTOR_RECALL", "REGION_EVIDENCE", "GROUP_LLM_REVIEW"));
        pipelines.add(business);

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("mainLine", "TEXT_QUALITY_REVIEW");
        quality.put("label", "文字质量审查");
        quality.put("enabled", Boolean.TRUE.equals(qualityResult.stats().get("enabled")));
        quality.put("findingCount", qualityResult.rows().size());
        quality.put("executionMechanisms", List.of(
                "FULL_SCAN", "STRUCTURE_INDEX", "TERMINOLOGY_CONSISTENCY", "CROSS_CHAPTER_CONSISTENCY"));
        quality.put("stats", qualityResult.stats());
        pipelines.add(quality);
        return pipelines;
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

    private void embedBlocks(String taskId, List<SarDocumentBlock> blocks,
                             AiModelConfig embeddingModel) throws Exception {
        int batchSize = Math.max(1, embeddingBatchSize);
        Integer detectedDimension = null;
        for (int start = 0; start < blocks.size(); start += batchSize) {
            throwIfCancelled(taskId);
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

    /** 区域级 + 清单式的分组系统提示：完整区域可判缺失，长区域抽样只能据实判定。 */
    private static final String SAR_GROUP_SYSTEM_PROMPT = """
            你是严格的航空机载文档审查员。下面会给你某一章节/区域的原文证据，以及一组应在该区域内核查的检查项（每个有唯一 check_code）。
            请对每一个 check_code 独立判定，目标是「在预期区域内尽可能找全」违规，宁可多报，不可漏报。

            判定规则（三级，对每个检查项分别给出）：
            - Fail：原文证据明确存在违规；或证据覆盖标记为 COMPLETE，且要求内容在完整区域内缺失。
            - Review：证据覆盖为 PARTIAL 且只能确认“没看到”，或证据自相矛盾、无法可靠判断。
            - Pass：当且仅当你能在区域原文中引用到明确满足该项要求的内容。

            输出要求：
            - 对输入里的每一个 check_code 各返回一条 results，按 check_code 对齐，不得遗漏、不得新增、不得合并。
            - 每个检查项的每一处违规作为它 findings 里的一条，给出可定位的 location；evidence 必须是从区域原文逐字复制的最小充分片段，不得改写或添加说明性前后缀。
            - findings.description 必须按“原文“<evidence逐字内容>”存在/表明……”表述，中文引号内文字必须与 evidence 完全一致。
            - 同一检查项的问题在多个位置出现时，每个位置各列一条，不要合并。
            - 只依据提供的区域原文判断，不要臆造；PARTIAL 证据不得用于证明全文缺失。
            严格按给定 JSON Schema 输出。
            """;

    /** 组装一行 finding 结果（与前端检查矩阵 / 导出字段对齐）。 */
    private Map<String, Object> buildRow(CheckPlan plan, String status, String reason,
                                         String evidenceText, String suggestion, String confidence,
                                         List<ScoredBlock> evidence, ScoredBlock matched,
                                         int violationIndex, int violationCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("finding_id", buildFindingId(
                plan, evidence, evidenceText, reason, violationIndex));
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

    private String buildFindingId(CheckPlan plan,
                                  List<ScoredBlock> evidence,
                                  String evidenceText,
                                  String reason,
                                  int violationIndex) {
        String sourceIds = evidence.stream()
                .map(item -> item.block().getBlockId())
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String ruleIdentity = plan.rule() != null && plan.rule().getId() != null
                ? String.valueOf(plan.rule().getId()) : plan.ruleCode();
        return stableFindingId(ruleIdentity, plan.checkCode(), sourceIds,
                evidenceText, reason, violationIndex);
    }

    String stableFindingId(String ruleIdentity,
                           String checkCode,
                           String sourceIds,
                           String evidenceText,
                           String reason,
                           int violationIndex) {
        String fingerprint = String.join("|",
                ruleIdentity,
                checkCode,
                sourceIds,
                normalizeForDedup(evidenceText),
                normalizeForDedup(reason),
                String.valueOf(violationIndex));
        return checkCode + "#" + sha1(fingerprint).substring(0, 16);
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
                                                         AiModelConfig chatModel) throws Exception {
        StringBuilder digest = new StringBuilder();
        int chapterCount = Math.max(1, chapters.size());
        int totalBudget = Math.max(chapterCount, consistencyMaxInputChars);
        int fairChapterBudget = Math.max(1, (totalBudget - chapterCount * 80) / chapterCount);
        int perChapterBudget = Math.max(1,
                Math.min(Math.max(1, consistencyPerChapterMaxChars), fairChapterBudget));
        for (int i = 0; i < chapters.size(); i++) {
            throwIfCancelled(taskId);
            WordParser.Chapter ch = chapters.get(i);
            String content = ch.getContent() == null ? "" : ch.getContent().trim();
            content = sampleEvenWindows(content, perChapterBudget,
                    Math.max(1, consistencyWindowsPerChapter));
            digest.append("== 第").append(i + 1).append("章 ")
                    .append(ch.getTitle() == null ? "" : ch.getTitle()).append(" ==\n")
                    .append(content).append("\n\n");
        }
        String sys = "你是严格的航空机载文档一致性审查员。只找【跨章节】的相互矛盾或不一致："
                + "同一实体在不同章节取值不同（温度范围、鉴定试验类别、设备型号/数量、合格判据数值等），"
                + "图号/表号/章节引用前后不一致，术语前后不统一。不要报单章节内的问题，找不到明确矛盾就返回空列表。"
                + "evidence 必须逐字复制两处相互矛盾的原文，不得改写或添加说明性前后缀；"
                + "description 必须按“原文“第一处证据”与“第二处证据”存在矛盾……”表述。"
                + "严格输出 JSON：{\"issues\":[{\"location\":\"涉及章节/位置\",\"description\":\"带原文引号的矛盾说明\","
                + "\"evidence\":\"两处逐字原文摘录\",\"suggestion\":\"如何统一\"}]}";
        AiCallOptions options = AiCallOptions.builder()
                .temperature(0.0).topP(1.0).maxTokensOverride(4096)
                .seed(stableSeed(taskId, 800000)).enablePromptCache(true).build();
        String response = aiModelService.callAiModel(chatModel, sys,
                "以下内容按章节公平取样，长章节包含头部、中段和尾部原文。请核对跨章一致性：\n\n" + digest,
                options);
        Map<String, Object> parsed = parseJson(response);
        if (!(parsed.get("issues") instanceof List<?> list)) {
            throw new IllegalStateException("consistency model response omitted issues array");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int total = list.size(), idx = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> im = (Map<String, Object>) m;
            Map<String, Object> row = buildConsistencyRow(idx++, total,
                    Objects.toString(im.getOrDefault("location", ""), ""),
                    Objects.toString(im.getOrDefault("description", ""), ""),
                    Objects.toString(im.getOrDefault("evidence", ""), ""),
                    Objects.toString(im.getOrDefault("suggestion", ""), ""));
            row.put("sourceRefs", locateConsistencySourceRefs(
                    chapters, Objects.toString(im.getOrDefault("evidence", ""), "")));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> locateConsistencySourceRefs(
            List<WordParser.Chapter> chapters, String evidence) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (int index = 0; index < chapters.size(); index++) {
            WordParser.Chapter chapter = chapters.get(index);
            int chapterIndex = index + 1;
            DocumentEvidenceLocator.locate(chapter.getNodes(), evidence).ifPresent(range -> {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("sourceId", "CHAPTER-" + String.format("%03d", chapterIndex));
                ref.put("chapterIndex", chapterIndex);
                ref.put("title", range.sectionPath());
                ref.put("sectionPath", range.sectionPath());
                ref.put("startNodeId", range.startNodeId());
                ref.put("endNodeId", range.endNodeId());
                ref.put("reason", "matched_evidence");
                refs.add(ref);
            });
        }
        return refs;
    }

    String sampleEvenWindows(String content, int maxChars, int windows) {
        if (content == null || content.isBlank() || maxChars <= 0) return "";
        if (content.length() <= maxChars) return content;
        int windowCount = Math.max(1, Math.min(windows, maxChars));
        int windowSize = Math.max(1, maxChars / windowCount);
        StringBuilder sampled = new StringBuilder(maxChars + windowCount * 20);
        for (int i = 0; i < windowCount; i++) {
            int start = windowCount == 1 ? 0
                    : (int) Math.round((double) i * (content.length() - windowSize) / (windowCount - 1));
            int end = Math.min(content.length(), start + windowSize);
            if (sampled.length() > 0) sampled.append("\n...[中间省略]...\n");
            sampled.append(content, start, end);
        }
        return sampled.toString();
    }

    /** 组装一行跨章一致性违规（与检查矩阵字段对齐，rule_code/check_code 用 CONSISTENCY 标识）。 */
    private Map<String, Object> buildConsistencyRow(int idx, int total, String location,
                                                    String description, String evidence, String suggestion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("finding_id", "CONSISTENCY#" + sha1(String.join("|",
                location, normalizeForDedup(description), normalizeForDedup(evidence),
                String.valueOf(idx))).substring(0, 16));
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
        runBounded(taskId, candidates, row -> {
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

    private boolean claimPendingTask(SarReviewTask task) {
        LocalDateTime now = LocalDateTime.now();
        int updated = sarReviewTaskMapper.update(null,
                new LambdaUpdateWrapper<SarReviewTask>()
                        .eq(SarReviewTask::getId, task.getId())
                        .eq(SarReviewTask::getStatus, SarReviewTask.STATUS_PENDING)
                        .set(SarReviewTask::getStatus, SarReviewTask.STATUS_PROCESSING)
                        .set(SarReviewTask::getFailReason, null)
                        .set(SarReviewTask::getUpdatedAt, now));
        if (updated == 0) return false;
        task.setStatus(SarReviewTask.STATUS_PROCESSING);
        task.setFailReason(null);
        task.setUpdatedAt(now);
        return true;
    }

    private void completeTaskIfProcessing(SarReviewTask task, Map<String, Object> aiResult) {
        throwIfCancelled(task.getId());
        LocalDateTime now = LocalDateTime.now();
        SarReviewTask patch = new SarReviewTask();
        patch.setStatus(SarReviewTask.STATUS_COMPLETED);
        patch.setAiResult(aiResult);
        patch.setProblemCount(ReviewExportUtil.computeProblemCount(aiResult));
        patch.setUpdatedAt(now);
        int updated = sarReviewTaskMapper.update(patch,
                new LambdaUpdateWrapper<SarReviewTask>()
                        .eq(SarReviewTask::getId, task.getId())
                        .eq(SarReviewTask::getStatus, SarReviewTask.STATUS_PROCESSING)
                        .set(SarReviewTask::getFailReason, null));
        if (updated == 0) {
            String current = sarReviewTaskMapper.selectStatusById(task.getId());
            if (SarReviewTask.STATUS_CANCELLED.equals(current)) {
                throw new TaskCancelledException(task.getId());
            }
            throw new IllegalStateException("Task status changed before completion: " + current);
        }
        task.setStatus(SarReviewTask.STATUS_COMPLETED);
        task.setAiResult(aiResult);
        task.setProblemCount(patch.getProblemCount());
        task.setFailReason(null);
        task.setUpdatedAt(now);
    }

    private boolean failTaskIfProcessing(String taskId, String failReason) {
        return sarReviewTaskMapper.update(null,
                new LambdaUpdateWrapper<SarReviewTask>()
                        .eq(SarReviewTask::getId, taskId)
                        .eq(SarReviewTask::getStatus, SarReviewTask.STATUS_PROCESSING)
                        .set(SarReviewTask::getStatus, SarReviewTask.STATUS_FAILED)
                        .set(SarReviewTask::getFailReason, rootErrorMessage(
                                new IllegalStateException(firstNonBlank(failReason, "Unknown review error"))))
                        .set(SarReviewTask::getUpdatedAt, LocalDateTime.now())) > 0;
    }

    private void throwIfCancelled(String taskId) {
        if (cancelledTasks.contains(taskId)
                || SarReviewTask.STATUS_CANCELLED.equals(sarReviewTaskMapper.selectStatusById(taskId))) {
            throw new TaskCancelledException(taskId);
        }
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static final class TaskCancelledException extends RuntimeException {
        private TaskCancelledException(String taskId) {
            super("SAR task cancelled: " + taskId);
        }
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
                                  boolean reranked, double routeConfidence,
                                  boolean regionComplete) {
    }

    /** 文档结构索引：区域键（S:section_path 或 C:chapterIndex）→ 该区域按阅读顺序排列的块。 */
    private record SectionIndex(LinkedHashMap<String, List<SarDocumentBlock>> bySection) {
    }

    /** 一次分组调用的装箱：成员检查项 + 它们共享的去重证据。 */
    private record CallBin(List<RetrievedCheck> members, List<ScoredBlock> unionEvidence) {
    }

    private record TextQualityReviewResult(List<Map<String, Object>> rows,
                                           Map<String, Object> stats,
                                           int consistencyFindings) {
        static TextQualityReviewResult empty() {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("enabled", false);
            return new TextQualityReviewResult(List.of(), stats, 0);
        }
    }

    private record QualityCheckTemplate(String checkCode,
                                        String question,
                                        String passCriteria,
                                        String category) {
    }

    private record FigureTableMention(String kind,
                                      String rawNumber,
                                      String normalizedNumber,
                                      String text,
                                      SarDocumentBlock block,
                                      boolean caption,
                                      int order) {
        String key() {
            return kind + ":" + normalizedNumber;
        }
    }

    private record TermObservation(String term,
                                   String evidence,
                                   SarDocumentBlock block) {
    }
}
