package com.aireview.modelconfig.service;

import com.aireview.modelconfig.dto.AiModelConfigDTO;
import com.aireview.common.dto.PageResponse;
import com.aireview.modelconfig.entity.AiModelConfig;
import com.aireview.modelconfig.repository.AiModelConfigMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelService {

    public static final String MODEL_TYPE_CHAT = "chat";
    public static final String MODEL_TYPE_EMBEDDING = "embedding";
    public static final String MODEL_TYPE_RERANKER = "reranker";
    public static final String RESPONSE_FORMAT_AUTO = "auto";
    public static final String RESPONSE_FORMAT_JSON_SCHEMA = "json_schema";
    public static final String RESPONSE_FORMAT_JSON_OBJECT = "json_object";
    public static final String RESPONSE_FORMAT_PROMPT_ONLY = "prompt_only";
    private static final int MAX_PGVECTOR_DIMENSIONS = 16000;

    private final Map<String, String> autoResponseFormatCache = new ConcurrentHashMap<>();

    /** Metadata for the latest call on the current worker thread, used by review diagnostics. */
    private final ThreadLocal<AiResponseMetadata> lastResponseMetadata = new ThreadLocal<>();

    private final AiModelConfigMapper aiModelConfigMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public record RerankResult(int index, double score) {
    }

    public record AiResponseMetadata(String finishReason, int contentLength, int reasoningLength,
                                     Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                     int requestedMaxTokens,
                                     String reasoningControl, String reasoningControlValue) {
    }

    record ParsedChatResponse(String content, String finishReason, int reasoningLength,
                              Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }

    public AiResponseMetadata getLastResponseMetadata() {
        return lastResponseMetadata.get();
    }

    public AiModelConfigDTO createConfig(AiModelConfigDTO dto) {
        AiModelConfig config = toEntity(dto);
        validateEmbeddingDimension(config);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.insert(config);
        log.info("AI model config created: {}", config.getModelName());
        return toDTO(config);
    }

    public AiModelConfigDTO updateConfig(Long id, AiModelConfigDTO dto) {
        AiModelConfig config = aiModelConfigMapper.selectById(id);
        if (config == null) {
            throw new IllegalArgumentException("AI model config not found: " + id);
        }
        if (dto.getName() != null) config.setModelName(dto.getName());
        if (dto.getProvider() != null) config.setProvider(dto.getProvider());
        if (dto.getModelType() != null) config.setModelType(normalizeModelType(dto.getModelType()));
        if (dto.getModelKey() != null) config.setModelKey(dto.getModelKey());
        if (dto.getApiEndpoint() != null) config.setEndpoint(dto.getApiEndpoint());
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank() && !dto.getApiKey().contains("****")) {
            config.setApiKey(dto.getApiKey());
        }
        if (dto.getMaxTokens() != null) config.setMaxTokens(dto.getMaxTokens());
        if (dto.getEmbeddingDimension() != null) config.setEmbeddingDimension(dto.getEmbeddingDimension());
        if (dto.getTemperature() != null) config.setTemperature(dto.getTemperature());
        if (dto.getTimeout() != null) config.setTimeout(dto.getTimeout());
        if (dto.getEnabled() != null) config.setIsEnabled(dto.getEnabled());
        if (dto.getResponseFormatMode() != null) {
            config.setResponseFormatMode(normalizeResponseFormatMode(dto.getResponseFormatMode()));
        }
        if (dto.getReasoningControl() != null) {
            config.setReasoningControl(ReasoningModeAdapter.normalize(dto.getReasoningControl()));
        }
        if (dto.getOmitTemperature() != null) config.setOmitTemperature(dto.getOmitTemperature());
        if (dto.getOutputTokenBudget() != null) config.setOutputTokenBudget(dto.getOutputTokenBudget());
        validateEmbeddingDimension(config);
        config.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.updateById(config);
        log.info("AI model config updated: {}", config.getModelName());
        return toDTO(config);
    }

    public void deleteConfig(Long id) {
        aiModelConfigMapper.deleteById(id);
        log.info("AI model config deleted: {}", id);
    }

    public AiModelConfigDTO getConfigById(Long id) {
        AiModelConfig config = aiModelConfigMapper.selectById(id);
        if (config == null) {
            throw new IllegalArgumentException("AI model config not found: " + id);
        }
        return toDTO(config);
    }

    public PageResponse<AiModelConfigDTO> listConfigs(int page, int size) {
        return listConfigs(page, size, null);
    }

    public PageResponse<AiModelConfigDTO> listConfigs(int page, int size, String modelType) {
        Page<AiModelConfig> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<AiModelConfig> query = new LambdaQueryWrapper<>();
        String normalizedType = (modelType == null || modelType.isBlank()) ? null : normalizeModelType(modelType);
        if (normalizedType != null) {
            query.eq(AiModelConfig::getModelType, normalizedType);
        }
        query.orderByDesc(AiModelConfig::getCreatedAt);
        Page<AiModelConfig> result = aiModelConfigMapper.selectPage(pageParam, query);
        List<AiModelConfigDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    public List<AiModelConfigDTO> listEnabledConfigs() {
        return listEnabledConfigs(null);
    }

    public List<AiModelConfigDTO> listEnabledConfigs(String modelType) {
        LambdaQueryWrapper<AiModelConfig> query = new LambdaQueryWrapper<>();
        query.eq(AiModelConfig::getIsEnabled, true);
        String normalizedType = normalizeModelType(modelType);
        if (normalizedType != null) {
            query.eq(AiModelConfig::getModelType, normalizedType);
        }
        List<AiModelConfig> configs = aiModelConfigMapper.selectList(query);
        return configs.stream().map(this::toDTO).toList();
    }

    public void toggleConfig(Long id, boolean enabled) {
        AiModelConfig config = aiModelConfigMapper.selectById(id);
        if (config == null) {
            throw new IllegalArgumentException("AI model config not found: " + id);
        }
        config.setIsEnabled(enabled);
        config.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.updateById(config);
        log.info("AI model config {} {}", config.getModelName(), enabled ? "enabled" : "disabled");
    }

    /** Look up a stored config by ID for the test endpoint to recover a masked API key. */
    public AiModelConfig getEntityById(Long id) {
        if (id == null) return null;
        return aiModelConfigMapper.selectById(id);
    }

    public AiModelConfig getEnabledModel(String modelName) {
        LambdaQueryWrapper<AiModelConfig> query = new LambdaQueryWrapper<>();
        query.eq(AiModelConfig::getModelName, modelName)
             .eq(AiModelConfig::getModelType, MODEL_TYPE_CHAT)
             .eq(AiModelConfig::getIsEnabled, true);
        AiModelConfig config = aiModelConfigMapper.selectOne(query);
        if (config == null) {
            throw new IllegalArgumentException("AI model not found or disabled: " + modelName);
        }
        return config;
    }

    public AiModelConfig getFirstEnabledModelByType(String modelType) {
        String normalizedType = normalizeModelType(modelType);
        LambdaQueryWrapper<AiModelConfig> query = new LambdaQueryWrapper<>();
        query.eq(AiModelConfig::getModelType, normalizedType)
             .eq(AiModelConfig::getIsEnabled, true)
             .orderByDesc(AiModelConfig::getUpdatedAt)
             .last("LIMIT 1");
        return aiModelConfigMapper.selectOne(query);
    }

    public List<List<Double>> embedTexts(AiModelConfig embeddingModel, List<String> texts) throws Exception {
        if (embeddingModel == null) {
            throw new IllegalArgumentException("Embedding model is required");
        }
        if (!MODEL_TYPE_EMBEDDING.equals(normalizeModelType(embeddingModel.getModelType()))) {
            throw new IllegalArgumentException("Model is not an embedding model: " + embeddingModel.getModelName());
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        JSONObject body = new JSONObject();
        body.put("model", resolveModelId(embeddingModel));
        JSONArray input = new JSONArray();
        input.addAll(texts);
        body.put("input", input);

        JSONObject response = postJson(embeddingModel, buildFullApiUrl(embeddingModel), body);
        List<List<Double>> vectors = parseEmbeddingVectors(response);
        if (vectors.size() != texts.size()) {
            throw new RuntimeException("Embedding API returned " + vectors.size()
                    + " vector(s) for " + texts.size() + " input text(s)");
        }
        return vectors;
    }

    public List<Double> embedText(AiModelConfig embeddingModel, String text) throws Exception {
        List<List<Double>> vectors = embedTexts(embeddingModel, List.of(text == null ? "" : text));
        return vectors.isEmpty() ? List.of() : vectors.get(0);
    }

    public List<RerankResult> rerank(AiModelConfig rerankerModel, String queryText,
                                      List<String> documents, int topN) throws Exception {
        if (rerankerModel == null) {
            throw new IllegalArgumentException("Reranker model is required");
        }
        if (!MODEL_TYPE_RERANKER.equals(normalizeModelType(rerankerModel.getModelType()))) {
            throw new IllegalArgumentException("Model is not a reranker model: " + rerankerModel.getModelName());
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        JSONObject body = new JSONObject();
        body.put("model", resolveModelId(rerankerModel));
        body.put("query", queryText == null ? "" : queryText);
        JSONArray docs = new JSONArray();
        docs.addAll(documents);
        body.put("documents", docs);
        body.put("top_n", Math.max(1, Math.min(topN, documents.size())));

        JSONObject response = postJson(rerankerModel, buildFullApiUrl(rerankerModel), body);
        List<RerankResult> parsed = parseRerankResults(response);
        if (parsed.isEmpty()) {
            throw new RuntimeException("Reranker API returned no ranked result");
        }
        return parsed.stream()
                .filter(r -> r.index() >= 0 && r.index() < documents.size())
                .sorted(Comparator.comparingDouble(RerankResult::score).reversed())
                .limit(Math.max(1, Math.min(topN, documents.size())))
                .toList();
    }

    /**
     * Probe an AI model configuration by issuing a tiny chat-completion request
     * with a 30-second timeout. Returns the resolved URL plus a short snippet
     * of the model's reply on success; throws a descriptive RuntimeException
     * with the HTTP status / body on failure so the UI can surface the cause.
     *
     * <p>The request shape is intentionally minimal (1 system + 1 user message,
     * 16-token cap) to minimise cost when the model is real.
     */
    public Map<String, Object> testConnection(AiModelConfigDTO dto, AiModelConfig persistedFallback) throws Exception {
        AiModelConfig probe = new AiModelConfig();
        probe.setModelName(dto.getName() != null ? dto.getName() : "test-probe");
        probe.setProvider(dto.getProvider() != null ? dto.getProvider() : "openai");
        probe.setModelType(normalizeModelType(dto.getModelType(), persistedFallback));
        probe.setModelKey(dto.getModelKey() != null && !dto.getModelKey().isBlank()
                ? dto.getModelKey() : probe.getModelName());
        probe.setEndpoint(dto.getApiEndpoint());
        // Allow editing an existing record without re-typing the API key: when the UI sends
        // an empty / masked key, fall back to the stored key on the persisted record.
        String key = dto.getApiKey();
        if ((key == null || key.isBlank() || key.contains("****")) && persistedFallback != null) {
            key = persistedFallback.getApiKey();
        }
        probe.setApiKey(key);
        if (dto.getResponseFormatMode() != null) {
            probe.setResponseFormatMode(normalizeResponseFormatMode(dto.getResponseFormatMode()));
        } else if (persistedFallback != null) {
            probe.setResponseFormatMode(normalizeResponseFormatMode(persistedFallback.getResponseFormatMode()));
        } else {
            probe.setResponseFormatMode(RESPONSE_FORMAT_AUTO);
        }
        // 探针要按真实配置发请求，否则测出来的兼容性和实际审查时不是一回事。
        probe.setReasoningControl(dto.getReasoningControl() != null
                ? dto.getReasoningControl()
                : (persistedFallback != null ? persistedFallback.getReasoningControl() : null));
        probe.setOmitTemperature(dto.getOmitTemperature() != null
                ? dto.getOmitTemperature()
                : (persistedFallback != null ? persistedFallback.getOmitTemperature() : null));

        // 关不掉思考的模型，思维链和最终回答共用 max_tokens；探针预算太小会让 content 为空、
        // 连接测试误报。这类模型在配置里勾了 omit_temperature，按它决定探针预算。
        boolean probeOmitsTemperature = omitsTemperature(probe);
        probe.setMaxTokens(probeOmitsTemperature ? 16000 : 16);
        // For thinking models the server enforces its own temperature; callAiModel
        // detects this and omits the parameter. For everything else we still want
        // a low temperature so the probe is deterministic.
        probe.setTemperature(resolveProbeTemperature(dto, persistedFallback));
        // Thinking models can take a while to finish their reasoning even for a
        // tiny prompt, so allow more time for the probe than the legacy 30 s.
        probe.setTimeout(probeOmitsTemperature ? 120 : 30);
        probe.setIsEnabled(true);

        if (probe.getEndpoint() == null || probe.getEndpoint().isBlank()) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        if (probe.getApiKey() == null || probe.getApiKey().isBlank() || probe.getApiKey().contains("****")) {
            throw new IllegalArgumentException("API Key 无效或已被脱敏，请重新填写后再测试");
        }

        long start = System.currentTimeMillis();
        Map<String, Object> result = testTypedModelConnection(probe);
        long elapsed = System.currentTimeMillis() - start;

        result.put("ok", true);
        result.put("resolvedUrl", buildFullApiUrl(probe));
        result.put("latencyMs", elapsed);
        return result;
    }

    private Map<String, Object> testTypedModelConnection(AiModelConfig probe) throws Exception {
        String type = normalizeModelType(probe.getModelType());
        if (MODEL_TYPE_EMBEDDING.equals(type)) {
            return testEmbeddingConnection(probe);
        }
        if (MODEL_TYPE_RERANKER.equals(type)) {
            return testRerankerConnection(probe);
        }
        return testChatReviewCompatibility(probe);
    }
    /**
     * Exercise the same structured-output capability used by document review.
     * A plain ping only proves that the endpoint and key are reachable.
     */
    private Map<String, Object> testChatReviewCompatibility(AiModelConfig probe) throws Exception {
        JSONObject booleanProperty = new JSONObject();
        booleanProperty.put("type", "boolean");
        JSONObject properties = new JSONObject();
        properties.put("ok", booleanProperty);
        JSONArray required = new JSONArray();
        required.add("ok");
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);

        AiCallOptions.AiCallOptionsBuilder options = AiCallOptions.builder()
                .structuredSchema(schema)
                .structuredSchemaName("connection_test")
                .maxTokensOverride(omitsTemperature(probe) ? null : 64);
        if (!omitsTemperature(probe)) {
            options.temperature(0.0).topP(1.0);
        }
        String reply = callAiModel(probe,
                "This is a document-review compatibility test. Return JSON only and follow the requested schema.",
                "Return {\"ok\":true}.", options.build());

        String json = reply == null ? "" : reply.trim();
        int objectStart = json.indexOf('{');
        int objectEnd = json.lastIndexOf('}');
        if (objectStart < 0 || objectEnd < objectStart) {
            throw new IllegalArgumentException("Model is reachable but did not return JSON during the review compatibility test");
        }
        JSONObject parsed = JSON.parseObject(json.substring(objectStart, objectEnd + 1));
        if (!Boolean.TRUE.equals(parsed.getBoolean("ok"))) {
            throw new IllegalArgumentException("Model is reachable but failed the structured review compatibility test");
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("modelType", MODEL_TYPE_CHAT);
        result.put("responseFormatMode", resolveResponseFormatMode(probe));
        String snippet = reply.trim();
        if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
        result.put("reply", snippet);
        return result;
    }

    private Map<String, Object> testEmbeddingConnection(AiModelConfig probe) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", resolveModelId(probe));
        body.put("input", "ping");
        JSONObject response = postJson(probe, buildFullApiUrl(probe), body);

        int dimension = 0;
        JSONArray data = response.getJSONArray("data");
        if (data != null && !data.isEmpty()) {
            JSONObject first = data.getJSONObject(0);
            JSONArray embedding = first != null ? first.getJSONArray("embedding") : null;
            dimension = embedding != null ? embedding.size() : 0;
        }
        if (dimension <= 0) {
            JSONArray embedding = response.getJSONArray("embedding");
            dimension = embedding != null ? embedding.size() : 0;
        }
        if (dimension <= 0) {
            throw new RuntimeException("Embedding API 未返回向量，请检查模型类型、地址和模型标识");
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("modelType", MODEL_TYPE_EMBEDDING);
        result.put("embeddingDimension", dimension);
        result.put("reply", "向量维度 " + dimension);
        return result;
    }

    private Map<String, Object> testRerankerConnection(AiModelConfig probe) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", resolveModelId(probe));
        body.put("query", "文档审查");
        JSONArray documents = new JSONArray();
        documents.add("文档审查需要依据规则定位证据");
        documents.add("天气晴朗");
        body.put("documents", documents);
        body.put("top_n", 1);
        JSONObject response = postJson(probe, buildFullApiUrl(probe), body);

        boolean hasRankedResult = false;
        JSONArray results = response.getJSONArray("results");
        if (results != null && !results.isEmpty()) {
            hasRankedResult = true;
        }
        JSONArray data = response.getJSONArray("data");
        if (!hasRankedResult && data != null && !data.isEmpty()) {
            hasRankedResult = true;
        }
        if (!hasRankedResult) {
            Object ranked = response.get("ranked_documents");
            hasRankedResult = ranked instanceof JSONArray arr && !arr.isEmpty();
        }
        if (!hasRankedResult) {
            throw new RuntimeException("Reranker API 未返回重排结果，请检查模型类型、地址和模型标识");
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("modelType", MODEL_TYPE_RERANKER);
        result.put("reply", "重排接口返回正常");
        return result;
    }

    private JSONObject postJson(AiModelConfig config, String fullUrl, JSONObject requestBody) throws Exception {
        int timeoutSec = config.getTimeout() != null ? config.getTimeout() : 60;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(timeoutSec))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toJSONString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new AiApiException(response.statusCode(), response.body(),
                    "AI API HTTP " + response.statusCode() + ": " + response.body(),
                    parseRetryAfterSeconds(response));
        }
        return JSON.parseObject(response.body());
    }

    /**
     * Read the {@code Retry-After} response header (seconds form) so the retry layer can
     * wait exactly as long as the provider asked on a 429. Returns {@code -1} when the
     * header is absent or not an integer count of seconds (HTTP-date form is ignored —
     * all the providers we call emit the seconds form).
     */
    private static long parseRetryAfterSeconds(HttpResponse<?> response) {
        try {
            return response.headers().firstValue("Retry-After")
                    .map(String::trim)
                    .filter(v -> v.matches("\\d+"))
                    .map(Long::parseLong)
                    .orElse(-1L);
        } catch (Exception e) {
            return -1L;
        }
    }

    private List<List<Double>> parseEmbeddingVectors(JSONObject response) {
        List<List<Double>> vectors = new ArrayList<>();
        JSONArray data = response.getJSONArray("data");
        if (data != null && !data.isEmpty()) {
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                JSONArray embedding = item != null ? item.getJSONArray("embedding") : null;
                if (embedding != null) {
                    vectors.add(toDoubleList(embedding));
                }
            }
        }
        if (vectors.isEmpty()) {
            JSONArray embedding = response.getJSONArray("embedding");
            if (embedding != null) {
                vectors.add(toDoubleList(embedding));
            }
        }
        return vectors;
    }

    private List<Double> toDoubleList(JSONArray array) {
        List<Double> values = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (value instanceof Number n) {
                values.add(n.doubleValue());
            } else if (value != null) {
                values.add(Double.parseDouble(value.toString()));
            }
        }
        return values;
    }

    private List<RerankResult> parseRerankResults(JSONObject response) {
        JSONArray array = response.getJSONArray("results");
        if (array == null || array.isEmpty()) {
            array = response.getJSONArray("data");
        }
        if (array == null || array.isEmpty()) {
            array = response.getJSONArray("ranked_documents");
        }
        if (array == null) {
            return List.of();
        }

        List<RerankResult> results = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) continue;
            Integer index = firstInteger(item, "index", "document_index", "documentIndex", "id");
            if (index == null) index = i;
            Double score = firstDouble(item, "relevance_score", "relevanceScore", "score");
            if (score == null) score = 1.0d / (i + 1);
            results.add(new RerankResult(index, score));
        }
        return results;
    }

    private Integer firstInteger(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.get(key);
            if (value instanceof Number n) return n.intValue();
            if (value != null) {
                try {
                    return Integer.parseInt(value.toString());
                } catch (NumberFormatException ignored) {
                    // try next key
                }
            }
        }
        return null;
    }

    private Double firstDouble(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.get(key);
            if (value instanceof Number n) return n.doubleValue();
            if (value != null) {
                try {
                    return Double.parseDouble(value.toString());
                } catch (NumberFormatException ignored) {
                    // try next key
                }
            }
        }
        return null;
    }

    public String callAiModel(AiModelConfig config, String systemPrompt, String userContent) throws Exception {
        return callAiModel(config, systemPrompt, userContent, AiCallOptions.defaults());
    }

    /**
     * 收敛性审查主用入口。统一走 OpenAI 兼容协议，在 legacy 调用基础上加两件事：
     * <ol>
     *   <li>统一参数：temperature / top_p / seed / max_tokens 全部按 {@link AiCallOptions} 强制；
     *       思维模型仍 omit temperature 以避免 Moonshot/GLM 的 400 拒绝。</li>
     *   <li>结构化输出：根据模型配置选择 json_schema / json_object / prompt-only，
     *       auto 模式会按供应商能力选择，并在 response_format 不兼容时降级。</li>
     * </ol>
     */
    public String callAiModel(AiModelConfig config, String systemPrompt, String userContent,
                               AiCallOptions options) throws Exception {
        lastResponseMetadata.remove();
        if (options == null) options = AiCallOptions.defaults();
        if (!MODEL_TYPE_CHAT.equals(normalizeModelType(config.getModelType()))) {
            throw new IllegalArgumentException("Only chat models can be used for document review: "
                    + config.getModelName());
        }
        String fullUrl = buildFullApiUrl(config);
        log.info("Calling AI model: {} at {} (resolved URL: {})", config.getModelName(), config.getEndpoint(), fullUrl);

        if (config.getApiKey() == null || config.getApiKey().contains("****")) {
            throw new RuntimeException("API Key 无效或已被脱敏，请重新配置模型的 API Key");
        }

        boolean omitTemperature = omitsTemperature(config);
        String provider = config.getProvider() != null ? config.getProvider().toLowerCase(Locale.ROOT) : "openai";
        int timeoutSec = config.getTimeout() != null ? config.getTimeout() : 180;
        if (options.getTimeoutSecondsOverride() != null && options.getTimeoutSecondsOverride() > 0) {
            timeoutSec = Math.min(timeoutSec, options.getTimeoutSecondsOverride());
        }
        int maxTokens = resolveMaxTokens(config, options);
        List<String> responseFormatCandidates = resolveResponseFormatCandidates(config, options);
        boolean automaticMode = RESPONSE_FORMAT_AUTO.equals(
                normalizeResponseFormatMode(config.getResponseFormatMode()));

        for (int formatIndex = 0; formatIndex < responseFormatCandidates.size(); formatIndex++) {
            String responseFormatMode = responseFormatCandidates.get(formatIndex);
            String effectiveSystemPrompt = buildStructuredOutputPrompt(systemPrompt, options, responseFormatMode);
            JSONObject requestBody = buildOpenAiRequestBody(config, effectiveSystemPrompt, userContent,
                    options, omitTemperature, maxTokens, responseFormatMode);
            // 思考控制参数由配置声明，配错就让供应商的 400 直接抛出去：不缓存、不静默回退。
            ReasoningModeAdapter.AppliedControl reasoningControl =
                    ReasoningModeAdapter.apply(config, requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toJSONString()))
                    .build();

            log.debug("AI request: provider={}, model={}, omitTemperature={}, reasoningControl={}={}, "
                            + "responseFormat={}, maxTokens={}, seed={}, contentLen={}",
                    provider, requestBody.getString("model"), omitTemperature,
                    reasoningControl.parameter(), reasoningControl.value(), responseFormatMode,
                    maxTokens, options.getSeed(), userContent.length());

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                boolean canDowngrade = automaticMode
                        && formatIndex + 1 < responseFormatCandidates.size()
                        && isResponseFormatCompatibilityError(response.statusCode(), response.body());
                if (canDowngrade) {
                    String fallbackMode = responseFormatCandidates.get(formatIndex + 1);
                    autoResponseFormatCache.put(responseFormatCacheKey(config), fallbackMode);
                    log.warn("Model {} rejected response_format mode {}; retrying once with {}",
                            config.getModelName(), responseFormatMode, fallbackMode);
                    continue;
                }
                log.error("AI model API returned status {}: {}", response.statusCode(), response.body());
                throw new AiApiException(response.statusCode(), response.body(),
                        "AI API HTTP " + response.statusCode() + ": " + response.body(),
                        parseRetryAfterSeconds(response));
            }

            ParsedChatResponse parsed = parseOpenAiResponse(response.body());
            AiResponseMetadata metadata = new AiResponseMetadata(
                    parsed.finishReason(), parsed.content().length(), parsed.reasoningLength(),
                    parsed.promptTokens(), parsed.completionTokens(), parsed.totalTokens(),
                    maxTokens,
                    reasoningControl.parameter(), reasoningControl.value());
            lastResponseMetadata.set(metadata);
            if ("length".equalsIgnoreCase(parsed.finishReason())) {
                log.warn("AI model response was truncated: model={}, finishReason=length, "
                                + "contentLength={}, reasoningLength={}, maxTokens={}, responseFormat={}",
                        config.getModelName(), parsed.content().length(), parsed.reasoningLength(),
                        maxTokens, responseFormatMode);
            } else {
                log.info("AI model response received, length: {}, reasoningLength={}, "
                                + "finishReason={}, responseFormat={}",
                        parsed.content().length(), parsed.reasoningLength(),
                        parsed.finishReason(), responseFormatMode);
            }
            return parsed.content();
        }
        throw new IllegalStateException("No compatible structured-output mode is available for " + config.getModelName());
    }

    private List<String> resolveResponseFormatCandidates(AiModelConfig config, AiCallOptions options) {
        boolean structuredOutputRequested = options.getStructuredSchema() != null
                || options.isForceJsonObjectFallback();
        if (!structuredOutputRequested) {
            return List.of(RESPONSE_FORMAT_PROMPT_ONLY);
        }
        String configuredMode = normalizeResponseFormatMode(config.getResponseFormatMode());
        if (!RESPONSE_FORMAT_AUTO.equals(configuredMode)) {
            return List.of(configuredMode);
        }
        String primary = resolveResponseFormatMode(config);
        return switch (primary) {
            case RESPONSE_FORMAT_JSON_SCHEMA -> List.of(
                    RESPONSE_FORMAT_JSON_SCHEMA, RESPONSE_FORMAT_JSON_OBJECT, RESPONSE_FORMAT_PROMPT_ONLY);
            case RESPONSE_FORMAT_JSON_OBJECT -> List.of(
                    RESPONSE_FORMAT_JSON_OBJECT, RESPONSE_FORMAT_PROMPT_ONLY);
            default -> List.of(RESPONSE_FORMAT_PROMPT_ONLY);
        };
    }

    private String resolveResponseFormatMode(AiModelConfig config) {
        String configuredMode = normalizeResponseFormatMode(config.getResponseFormatMode());
        if (!RESPONSE_FORMAT_AUTO.equals(configuredMode)) {
            return configuredMode;
        }
        String cached = autoResponseFormatCache.get(responseFormatCacheKey(config));
        if (cached != null) {
            return cached;
        }
        String provider = config.getProvider() == null ? "" : config.getProvider().toLowerCase(Locale.ROOT);
        String endpoint = config.getEndpoint() == null ? "" : config.getEndpoint().toLowerCase(Locale.ROOT);
        String modelIdentity = ((config.getModelKey() == null ? "" : config.getModelKey()) + " "
                + (config.getModelName() == null ? "" : config.getModelName())).toLowerCase(Locale.ROOT);
        // Aggregation gateways often use their own provider/endpoint names even
        // though the selected model is DeepSeek. Detect the model identity too;
        // otherwise auto mode falls back to prompt-only output and reasoning text
        // can consume the whole token budget before the JSON answer is emitted.
        //
        // json_schema rather than json_object: under json_object the schema is only
        // appended to the system prompt as advice, so the check_results minItems floor
        // that enforces rule coverage is unenforced and the model silently omits rules.
        // Auto mode still degrades json_schema → json_object → prompt_only on a
        // response_format 400, and caches the outcome, so a gateway that cannot honour
        // json_schema costs one extra round trip and never breaks.
        if (provider.contains("deepseek") || endpoint.contains("api.deepseek.com")
                || modelIdentity.contains("deepseek")) {
            return RESPONSE_FORMAT_JSON_SCHEMA;
        }
        if ("openai".equals(provider) || endpoint.contains("api.openai.com")) {
            return RESPONSE_FORMAT_JSON_SCHEMA;
        }
        return RESPONSE_FORMAT_PROMPT_ONLY;
    }

    private String normalizeResponseFormatMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return RESPONSE_FORMAT_AUTO;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case RESPONSE_FORMAT_AUTO, RESPONSE_FORMAT_JSON_SCHEMA,
                    RESPONSE_FORMAT_JSON_OBJECT, RESPONSE_FORMAT_PROMPT_ONLY -> normalized;
            default -> throw new IllegalArgumentException("Unsupported response format mode: " + mode);
        };
    }

    private String responseFormatCacheKey(AiModelConfig config) {
        return String.join("|",
                config.getProvider() == null ? "" : config.getProvider(),
                config.getEndpoint() == null ? "" : config.getEndpoint(),
                config.getModelKey() == null ? "" : config.getModelKey());
    }

    private boolean isResponseFormatCompatibilityError(int statusCode, String responseBody) {
        if (statusCode != 400 || responseBody == null) {
            return false;
        }
        String lower = responseBody.toLowerCase(Locale.ROOT);
        return lower.contains("response_format") || lower.contains("response format");
    }

    private String buildStructuredOutputPrompt(String systemPrompt, AiCallOptions options,
                                               String responseFormatMode) {
        String base = systemPrompt == null ? "" : systemPrompt;
        String strictJsonContract = "\n\n【严格 JSON 输出】立即输出最终 JSON，不得输出分析、推理过程、计划、说明或 Markdown。"
                + "第一个非空白字符必须是 {，最后一个非空白字符必须是 }。"
                + "示例仅表示语法形式：{\"field\":\"value\"}；实际字段必须完全遵守给定 Schema。";
        if (options.getStructuredSchema() == null) {
            return options.isForceJsonObjectFallback() && RESPONSE_FORMAT_PROMPT_ONLY.equals(responseFormatMode)
                    ? base + strictJsonContract
                    : base;
        }
        if (RESPONSE_FORMAT_JSON_SCHEMA.equals(responseFormatMode)) {
            return base + strictJsonContract;
        }
        if (options.isPromptCarriesSchema()) {
            // 提示词自己已经写了一份 schema，再追加就是同一份出现两次。
            return base + strictJsonContract;
        }
        return base
                + strictJsonContract
                + "\n输出必须符合以下 JSON Schema：\n"
                + options.getStructuredSchema().toJSONString();
    }

    /**
     * 解析本次请求的 max_tokens。配置里的 {@code output_token_budget} 是**下限**：
     * 关不掉思考的模型靠它保证思维链写完之后还有地方写最终 JSON。
     */
    private int resolveMaxTokens(AiModelConfig config, AiCallOptions options) {
        int maxTokens = options.getMaxTokensOverride() != null
                ? options.getMaxTokensOverride()
                : (config.getMaxTokens() != null ? config.getMaxTokens() : 4096);
        Integer configuredFloor = config.getOutputTokenBudget();
        if (configuredFloor != null && configuredFloor > maxTokens) {
            log.info("Raising max_tokens {} → {} for {} (configured output_token_budget)",
                    maxTokens, configuredFloor, config.getModelKey());
            maxTokens = configuredFloor;
        }
        return maxTokens;
    }

    /** OpenAI / Moonshot / GLM / Qwen / DeepSeek 等兼容协议的请求体。 */
    private JSONObject buildOpenAiRequestBody(AiModelConfig config, String systemPrompt, String userContent,
                                              AiCallOptions options, boolean omitTemperature, int maxTokens,
                                              String responseFormatMode) {
        JSONObject body = new JSONObject();
        body.put("model", resolveModelId(config));
        // 服务端锁定 temperature 的模型（配置里勾了 omit_temperature）不下发该参数，
        // 否则 Moonshot / GLM 一类会直接 400。options 优先于 config 的历史默认值。
        if (!omitTemperature && !options.isOmitTemperature()) {
            Double t = options.getTemperature() != null ? options.getTemperature()
                    : (config.getTemperature() != null ? config.getTemperature() : 0.1);
            body.put("temperature", t);
        } else {
            log.info("Reasoning response mode ({}): omitting temperature so server default applies",
                    config.getModelKey());
        }
        if (options.getTopP() != null) {
            body.put("top_p", options.getTopP());
        }
        if (options.getSeed() != null) {
            // OpenAI / Moonshot / DeepSeek 都支持 seed；GLM 忽略；不会报错。
            body.put("seed", options.getSeed());
        }
        body.put("max_tokens", maxTokens);

        JSONArray messages = new JSONArray();
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
        messages.add(userMsg);
        body.put("messages", messages);

        // Structured output is provider-specific; responseFormatMode has already been resolved.
        if (options.getStructuredSchema() != null) {
            if (RESPONSE_FORMAT_JSON_SCHEMA.equals(responseFormatMode)) {
                JSONObject responseFormat = new JSONObject();
                responseFormat.put("type", RESPONSE_FORMAT_JSON_SCHEMA);
                JSONObject jsonSchema = new JSONObject();
                jsonSchema.put("name", options.getStructuredSchemaName());
                jsonSchema.put("strict", true);
                jsonSchema.put("schema", options.getStructuredSchema());
                responseFormat.put(RESPONSE_FORMAT_JSON_SCHEMA, jsonSchema);
                body.put("response_format", responseFormat);
            } else if (RESPONSE_FORMAT_JSON_OBJECT.equals(responseFormatMode)) {
                JSONObject responseFormat = new JSONObject();
                responseFormat.put("type", RESPONSE_FORMAT_JSON_OBJECT);
                body.put("response_format", responseFormat);
            }
        } else if (options.isForceJsonObjectFallback()
                && !RESPONSE_FORMAT_PROMPT_ONLY.equals(responseFormatMode)) {
            JSONObject responseFormat = new JSONObject();
            responseFormat.put("type", RESPONSE_FORMAT_JSON_OBJECT);
            body.put("response_format", responseFormat);
        }
        return body;
    }

    private String resolveModelId(AiModelConfig config) {
        return config.getModelKey() != null && !config.getModelKey().isBlank()
                ? config.getModelKey() : config.getModelName();
    }

    /** Parse content plus completion diagnostics from an OpenAI-compatible response. */
    ParsedChatResponse parseOpenAiResponse(String body) {
        JSONObject responseBody = JSON.parseObject(body);
        JSONArray choices = responseBody.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            log.error("AI model returned empty choices. Full response: {}", body);
            throw new RuntimeException("AI model returned empty response");
        }
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        String content = message.getString("content");
        String reasoning = message.getString("reasoning_content");
        if ((content == null || content.isBlank()) && reasoning != null && !reasoning.isBlank()) {
            log.warn("AI model returned reasoning_content but no final content; reasoning is discarded");
        }
        JSONObject usage = responseBody.getJSONObject("usage");
        return new ParsedChatResponse(
                content == null ? "" : content,
                choice.getString("finish_reason"),
                reasoning == null ? 0 : reasoning.length(),
                usage == null ? null : usage.getInteger("prompt_tokens"),
                usage == null ? null : usage.getInteger("completion_tokens"),
                usage == null ? null : usage.getInteger("total_tokens"));
    }

    /**
     * 是否不下发 temperature。直接读配置，不做任何按模型 id 的推断。
     *
     * <p>服务端锁定自身 temperature 的推理模型（R1 / Reasoner / o 系列）需要勾上，
     * 否则发过去要么被忽略要么直接 400。
     */
    public static boolean omitsTemperature(AiModelConfig config) {
        return config != null && Boolean.TRUE.equals(config.getOmitTemperature());
    }

    private Double resolveProbeTemperature(AiModelConfigDTO dto, AiModelConfig persistedFallback) {
        if (dto.getTemperature() != null) {
            return dto.getTemperature();
        }
        if (persistedFallback != null && persistedFallback.getTemperature() != null) {
            return persistedFallback.getTemperature();
        }
        return 0.1;
    }

    private AiModelConfigDTO toDTO(AiModelConfig config) {
        AiModelConfigDTO dto = new AiModelConfigDTO();
        dto.setId(config.getId());
        dto.setName(config.getModelName());
        dto.setProvider(config.getProvider() != null ? config.getProvider() : "openai");
        dto.setModelType(normalizeModelType(config.getModelType()));
        dto.setModelKey(config.getModelKey() != null ? config.getModelKey() : config.getModelName());
        dto.setApiEndpoint(config.getEndpoint());
        dto.setApiKey(maskApiKey(config.getApiKey()));
        dto.setMaxTokens(config.getMaxTokens() != null ? config.getMaxTokens() : 4096);
        dto.setEmbeddingDimension(config.getEmbeddingDimension());
        dto.setTemperature(config.getTemperature() != null ? config.getTemperature() : 0.7);
        dto.setTimeout(config.getTimeout() != null ? config.getTimeout() : 180);
        dto.setEnabled(config.getIsEnabled());
        dto.setResponseFormatMode(normalizeResponseFormatMode(config.getResponseFormatMode()));
        dto.setReasoningControl(ReasoningModeAdapter.normalize(config.getReasoningControl()));
        dto.setOmitTemperature(omitsTemperature(config));
        dto.setOutputTokenBudget(config.getOutputTokenBudget());
        // 温度被服务端锁定的模型不能参与跨模型对比：参数对齐不完整、采样不收敛。
        dto.setCrossModelEligible(!omitsTemperature(config));
        dto.setCreatedAt(config.getCreatedAt());
        dto.setUpdatedAt(config.getUpdatedAt());
        return dto;
    }

    private AiModelConfig toEntity(AiModelConfigDTO dto) {
        AiModelConfig config = new AiModelConfig();
        config.setModelName(dto.getName());
        config.setProvider(dto.getProvider() != null ? dto.getProvider() : "openai");
        config.setModelType(normalizeModelType(dto.getModelType()));
        config.setModelKey(dto.getModelKey() != null ? dto.getModelKey() : dto.getName());
        config.setApiKey(dto.getApiKey());
        config.setEndpoint(dto.getApiEndpoint());
        config.setContextWindow(128000);
        config.setMaxTokens(dto.getMaxTokens() != null ? dto.getMaxTokens() : 4096);
        config.setEmbeddingDimension(dto.getEmbeddingDimension());
        config.setTemperature(dto.getTemperature() != null ? dto.getTemperature() : 0.7);
        config.setTimeout(dto.getTimeout() != null ? dto.getTimeout() : 180);
        config.setIsEnabled(dto.getEnabled() != null ? dto.getEnabled() : true);
        config.setResponseFormatMode(normalizeResponseFormatMode(dto.getResponseFormatMode()));
        config.setReasoningControl(ReasoningModeAdapter.normalize(dto.getReasoningControl()));
        config.setOmitTemperature(dto.getOmitTemperature() != null ? dto.getOmitTemperature() : false);
        config.setOutputTokenBudget(dto.getOutputTokenBudget());
        return config;
    }

    private String normalizeModelType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return MODEL_TYPE_CHAT;
        }
        String normalized = modelType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case MODEL_TYPE_CHAT, "llm", "completion", "chat-completion" -> MODEL_TYPE_CHAT;
            case MODEL_TYPE_EMBEDDING, "embeddings", "vector" -> MODEL_TYPE_EMBEDDING;
            case MODEL_TYPE_RERANKER, "rerank", "ranker" -> MODEL_TYPE_RERANKER;
            default -> throw new IllegalArgumentException("Unsupported model type: " + modelType);
        };
    }

    private String normalizeModelType(String modelType, AiModelConfig persistedFallback) {
        if (modelType != null && !modelType.isBlank()) {
            return normalizeModelType(modelType);
        }
        if (persistedFallback != null && persistedFallback.getModelType() != null) {
            return normalizeModelType(persistedFallback.getModelType());
        }
        return MODEL_TYPE_CHAT;
    }

    private void validateEmbeddingDimension(AiModelConfig config) {
        if (!MODEL_TYPE_EMBEDDING.equals(normalizeModelType(config.getModelType()))) {
            return;
        }
        Integer dimension = config.getEmbeddingDimension();
        if (dimension != null && (dimension < 1 || dimension > MAX_PGVECTOR_DIMENSIONS)) {
            throw new IllegalArgumentException(
                    "Embedding dimension must be between 1 and " + MAX_PGVECTOR_DIMENSIONS);
        }
    }

    private String buildFullApiUrl(AiModelConfig config) {
        String endpoint = config.getEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("API endpoint cannot be empty");
        }

        String provider = config.getProvider() != null ? config.getProvider().toLowerCase() : "openai";

        // 本地模型：用户提供完整地址，后端原样使用，不补全 /v1、/chat/completions、
        // /embeddings、/rerank 等任何路径（仅去掉末尾斜杠、缺协议头时补 http://）。
        if ("local".equals(provider)) {
            String url = endpoint.trim();
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            return url;
        }

        String baseUrl = endpoint.trim();

        // 确保URL以http开头
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "https://" + baseUrl;
        }

        // 移除末尾的斜杠
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String modelType = normalizeModelType(config.getModelType());
        if (MODEL_TYPE_EMBEDDING.equals(modelType)) {
            if (baseUrl.endsWith("/embeddings") || baseUrl.contains("/embeddings/")) {
                return baseUrl;
            }
            return baseUrl.endsWith("/v1") ? baseUrl + "/embeddings" : baseUrl + "/v1/embeddings";
        }
        if (MODEL_TYPE_RERANKER.equals(modelType)) {
            if (baseUrl.endsWith("/rerank") || baseUrl.contains("/rerank/")) {
                return baseUrl;
            }
            return baseUrl.endsWith("/v1") ? baseUrl + "/rerank" : baseUrl + "/v1/rerank";
        }

        // 统一按 OpenAI 兼容协议补全路径：moonshot / 阿里千问 / deepseek / minimax / glm /
        // 自定义供应商都走这套规则（本地模型已在前面提前返回，embedding/reranker 也已处理）。
        // 用户只需填到 ".../v1"，系统自动补 /chat/completions；已是完整路径则原样使用。
        if (baseUrl.contains("/chat/completions")) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/chat/completions";
        }
        if (baseUrl.contains("/v1/")) {
            // 已经包含 /v1/xxx 等子路径，直接使用
            return baseUrl;
        }
        // 既不含 /v1 也不含 /chat/completions：补全完整路径
        return baseUrl + "/v1/chat/completions";
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
