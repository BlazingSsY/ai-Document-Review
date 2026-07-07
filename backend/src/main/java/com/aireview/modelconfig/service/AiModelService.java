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
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelService {

    public static final String MODEL_TYPE_CHAT = "chat";
    public static final String MODEL_TYPE_EMBEDDING = "embedding";
    public static final String MODEL_TYPE_RERANKER = "reranker";
    private static final int MAX_PGVECTOR_DIMENSIONS = 16000;

    private final AiModelConfigMapper aiModelConfigMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public record RerankResult(int index, double score) {
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
        if (dto.getThinkingMode() != null) config.setThinkingMode(dto.getThinkingMode());
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
        // Carry the thinking-mode flag through to the probe. Resolution order:
        //   1. Explicit value from the DTO (the form's switch).
        //   2. Saved value on the persisted row (when editing an existing model).
        //   3. Null → isThinkingModel falls back to the regex heuristic on modelKey.
        if (dto.getThinkingMode() != null) {
            probe.setThinkingMode(dto.getThinkingMode());
        } else if (persistedFallback != null && persistedFallback.getThinkingMode() != null) {
            probe.setThinkingMode(persistedFallback.getThinkingMode());
        }
        // Thinking models share max_tokens between reasoning_content and content.
        // The probe needs a budget large enough for the model to finish its chain
        // of thought AND produce the single-character reply, otherwise the
        // response comes back with content="" and the connection test misreports
        // success/failure. 16 tokens is fine for non-thinking models; thinking
        // models need at least 16 000 per the Moonshot/Z.ai docs.
        boolean probeThinking = isThinkingModel(probe);
        probe.setMaxTokens(probeThinking ? 16000 : 16);
        // For thinking models the server enforces its own temperature; callAiModel
        // detects this and omits the parameter. For everything else we still want
        // a low temperature so the probe is deterministic.
        probe.setTemperature(resolveProbeTemperature(dto, persistedFallback));
        // Thinking models can take a while to finish their reasoning even for a
        // tiny prompt, so allow more time for the probe than the legacy 30 s.
        probe.setTimeout(probeThinking ? 120 : 30);
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
        String reply = callAiModel(probe, "你是一个用于连接性测试的助手，请用一个汉字回答 \"好\"。",
                "ping");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("modelType", MODEL_TYPE_CHAT);
        String snippet = reply == null ? "" : reply.trim();
        if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "...";
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
     *   <li>结构化输出：有 schema 走 {@code response_format=json_schema}，
     *       否则 fallback 到 {@code response_format=json_object} 或仅 prompt 约束。</li>
     * </ol>
     */
    public String callAiModel(AiModelConfig config, String systemPrompt, String userContent,
                               AiCallOptions options) throws Exception {
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

        boolean thinking = isThinkingModel(config);
        String provider = config.getProvider() != null ? config.getProvider().toLowerCase(Locale.ROOT) : "openai";

        int timeoutSec = config.getTimeout() != null ? config.getTimeout() : 180;
        int maxTokens = resolveMaxTokens(config, options, thinking);

        JSONObject requestBody = buildOpenAiRequestBody(config, systemPrompt, userContent, options, thinking, maxTokens);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(timeoutSec))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toJSONString()))
                .build();

        log.debug("AI request body (truncated): provider={}, model={}, thinking={}, structured={}, seed={}, contentLen={}",
                provider, requestBody.getString("model"), thinking,
                options.getStructuredSchema() != null, options.getSeed(), userContent.length());

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("AI model API returned status {}: {}", response.statusCode(), response.body());
            throw new AiApiException(response.statusCode(), response.body(),
                    "AI API HTTP " + response.statusCode() + ": " + response.body(),
                    parseRetryAfterSeconds(response));
        }

        String content = parseOpenAiResponse(response.body());
        log.info("AI model response received, length: {}", content.length());
        return content;
    }

    /** 解析 max_tokens：optional 覆盖 → 思维模型最小 16000 → 配置默认 → 4096 兜底。 */
    private int resolveMaxTokens(AiModelConfig config, AiCallOptions options, boolean thinking) {
        int maxTokens = options.getMaxTokensOverride() != null
                ? options.getMaxTokensOverride()
                : (config.getMaxTokens() != null ? config.getMaxTokens() : 4096);
        if (thinking && maxTokens < 16000) {
            log.info("Bumping max_tokens from {} → 16000 for thinking-mode model {}",
                    maxTokens, config.getModelKey());
            maxTokens = 16000;
        }
        return maxTokens;
    }

    /** OpenAI / Moonshot / GLM / Qwen / DeepSeek 等兼容协议的请求体。 */
    private JSONObject buildOpenAiRequestBody(AiModelConfig config, String systemPrompt, String userContent,
                                               AiCallOptions options, boolean thinking, int maxTokens) {
        JSONObject body = new JSONObject();
        body.put("model", resolveModelId(config));
        // Thinking-mode models lock temperature server-side (Moonshot 拒绝 ≠ 1 / GLM 也是),
        // 因此只在非思维模型上传 temperature；options 优先于 config 的历史默认值。
        if (!thinking) {
            Double t = options.getTemperature() != null ? options.getTemperature()
                    : (config.getTemperature() != null ? config.getTemperature() : 0.1);
            body.put("temperature", t);
        } else {
            log.info("Thinking-mode model ({}): omitting temperature so server default applies",
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

        // 结构化输出：优先用 json_schema，没有 schema 但要求 JSON 时回退 json_object。
        if (options.getStructuredSchema() != null) {
            JSONObject responseFormat = new JSONObject();
            responseFormat.put("type", "json_schema");
            JSONObject jsonSchema = new JSONObject();
            jsonSchema.put("name", options.getStructuredSchemaName());
            jsonSchema.put("strict", true);
            jsonSchema.put("schema", options.getStructuredSchema());
            responseFormat.put("json_schema", jsonSchema);
            body.put("response_format", responseFormat);
        } else if (options.isForceJsonObjectFallback()) {
            JSONObject responseFormat = new JSONObject();
            responseFormat.put("type", "json_object");
            body.put("response_format", responseFormat);
        }
        return body;
    }

    private String resolveModelId(AiModelConfig config) {
        return config.getModelKey() != null && !config.getModelKey().isBlank()
                ? config.getModelKey() : config.getModelName();
    }

    /** 解析 OpenAI 兼容协议的响应；处理 thinking 模型的 reasoning_content 回退。 */
    private String parseOpenAiResponse(String body) {
        JSONObject responseBody = JSON.parseObject(body);
        JSONArray choices = responseBody.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            log.error("AI model returned empty choices. Full response: {}", body);
            throw new RuntimeException("AI model returned empty response");
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message.getString("content");
        if (content == null || content.isBlank()) {
            String reasoning = message.getString("reasoning_content");
            if (reasoning != null && !reasoning.isBlank()) {
                log.warn("AI model returned empty content but non-empty reasoning_content "
                        + "(likely truncated); using reasoning text as fallback");
                content = reasoning;
            }
        }
        return content == null ? "" : content;
    }

    /**
     * Decide whether the API call should treat this config as a thinking-mode model
     * (i.e. omit temperature, raise max_tokens, parse reasoning_content fallback).
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If the user explicitly set the {@code thinking_mode} flag in the model
     *       config (via the UI), trust their choice — this lets new models be
     *       configured without code changes, and lets a model that later relaxes
     *       its temperature lock be switched back.</li>
     *   <li>Otherwise fall back to the regex heuristic for legacy rows / configs
     *       whose flag is null.</li>
     * </ol>
     */
    static boolean isThinkingModel(AiModelConfig config) {
        if (config == null) return false;
        Boolean explicit = config.getThinkingMode();
        if (explicit != null) return explicit;
        String key = config.getModelKey();
        if (key == null || key.isBlank()) key = config.getModelName();
        return matchesThinkingPattern(key);
    }

    /**
     * Heuristic: does this model id look like a thinking/reasoning model? Used as
     * the auto-suggestion when the UI doesn't yet have a value, and as the
     * fallback when {@link #isThinkingModel} sees no explicit flag.
     */
    public static boolean matchesThinkingPattern(String modelKeyOrName) {
        if (modelKeyOrName == null || modelKeyOrName.isBlank()) return false;
        return THINKING_MODEL_PATTERN.matcher(modelKeyOrName.toLowerCase(Locale.ROOT)).find();
    }

    private static final Pattern THINKING_MODEL_PATTERN = Pattern.compile(
            // Kimi: kimi-k2-thinking, kimi-k2.5, kimi-k2.6, kimi-k2-5, kimi-k2-6, ...
            "kimi-?k?2[.\\-]?(?:thinking|5|6|7|8|9)"
            // GLM: glm-4.5, glm-4.6, glm-5, glm-5.1, glm-5-air, glm-4.5-thinking ...
            + "|glm-?(?:4\\.5|4\\.6|4\\.7|5(?:\\.[0-9]+)?)"
            // Generic catch-all: any name containing "thinking" / "reasoner" / "-r1"
            + "|thinking|reasoner|-r1\\b|deepseek-r1");

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
        boolean isThinking = isThinkingModel(config);
        dto.setThinkingMode(isThinking);
        // 思维模型不能参与跨模型对比：温度服务器锁、seed 通常不支持、采样不收敛。
        dto.setCrossModelEligible(!isThinking);
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
        // If the UI didn't provide a value, fall back to the regex heuristic so historic
        // rows and forms-without-the-field still get sensible auto-detection.
        config.setThinkingMode(dto.getThinkingMode() != null
                ? dto.getThinkingMode()
                : matchesThinkingPattern(dto.getModelKey() != null ? dto.getModelKey() : dto.getName()));
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
