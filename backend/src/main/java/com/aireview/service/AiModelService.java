package com.aireview.service;

import com.aireview.dto.AiModelConfigDTO;
import com.aireview.dto.PageResponse;
import com.aireview.entity.AiModelConfig;
import com.aireview.repository.AiModelConfigMapper;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelService {

    private final AiModelConfigMapper aiModelConfigMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public AiModelConfigDTO createConfig(AiModelConfigDTO dto) {
        AiModelConfig config = toEntity(dto);
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
        if (dto.getModelKey() != null) config.setModelKey(dto.getModelKey());
        if (dto.getApiEndpoint() != null) config.setEndpoint(dto.getApiEndpoint());
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank() && !dto.getApiKey().contains("****")) {
            config.setApiKey(dto.getApiKey());
        }
        if (dto.getMaxTokens() != null) config.setMaxTokens(dto.getMaxTokens());
        if (dto.getTemperature() != null) config.setTemperature(dto.getTemperature());
        if (dto.getEnabled() != null) config.setIsEnabled(dto.getEnabled());
        if (dto.getThinkingMode() != null) config.setThinkingMode(dto.getThinkingMode());
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
        Page<AiModelConfig> pageParam = new Page<>(page, size);
        Page<AiModelConfig> result = aiModelConfigMapper.selectPage(pageParam, null);
        List<AiModelConfigDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    public List<AiModelConfigDTO> listEnabledConfigs() {
        LambdaQueryWrapper<AiModelConfig> query = new LambdaQueryWrapper<>();
        query.eq(AiModelConfig::getIsEnabled, true);
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
             .eq(AiModelConfig::getIsEnabled, true);
        AiModelConfig config = aiModelConfigMapper.selectOne(query);
        if (config == null) {
            throw new IllegalArgumentException("AI model not found or disabled: " + modelName);
        }
        return config;
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
        String reply = callAiModel(probe, "你是一个用于连接性测试的助手，请用一个汉字回答 \"好\"。",
                "ping");
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("ok", true);
        result.put("resolvedUrl", buildFullApiUrl(probe));
        result.put("latencyMs", elapsed);
        String snippet = reply == null ? "" : reply.trim();
        if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "...";
        result.put("reply", snippet);
        return result;
    }

    public String callAiModel(AiModelConfig config, String systemPrompt, String userContent) throws Exception {
        String fullUrl = buildFullApiUrl(config);
        log.info("Calling AI model: {} at {} (resolved URL: {})", config.getModelName(), config.getEndpoint(), fullUrl);

        // Guard against masked API keys
        if (config.getApiKey() == null || config.getApiKey().contains("****")) {
            throw new RuntimeException("API Key 无效或已被脱敏，请重新配置模型的 API Key");
        }

        boolean thinking = isThinkingModel(config);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModelKey() != null && !config.getModelKey().isBlank()
                ? config.getModelKey() : config.getModelName());

        // Thinking-mode models (kimi-k2-thinking / kimi-k2.6 / GLM-4.5 / GLM-4.6 /
        // GLM-5 / GLM-5.1, ...) enforce a fixed temperature on the server side. The
        // Moonshot API explicitly rejects any temperature ≠ 1 with a 400 "invalid
        // temperature" error. The cleanest behaviour is to OMIT temperature for
        // these models so the API applies its own default.
        if (!thinking) {
            requestBody.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.1);
        } else {
            log.info("Detected thinking-mode model ({}): omitting temperature so server default applies",
                    config.getModelKey());
        }

        // For thinking models, max_tokens covers BOTH reasoning_content and the
        // final content. The Kimi docs recommend ≥ 16 000 to avoid the answer
        // being truncated mid-thought.
        int maxTokens = config.getMaxTokens() != null ? config.getMaxTokens() : 4096;
        if (thinking && maxTokens < 16000) {
            log.info("Bumping max_tokens from {} → 16000 for thinking-mode model {}",
                    maxTokens, config.getModelKey());
            maxTokens = 16000;
        }
        requestBody.put("max_tokens", maxTokens);

        JSONArray messages = new JSONArray();
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
        messages.add(userMsg);

        requestBody.put("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(config.getTimeout() != null ? config.getTimeout() : 180))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toJSONString()))
                .build();

        log.debug("AI request body (truncated): model={}, messages count={}, content length={}, thinking={}",
                requestBody.getString("model"), messages.size(), userContent.length(), thinking);

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("AI model API returned status {}: {}", response.statusCode(), response.body());
            throw new RuntimeException("AI API HTTP " + response.statusCode() + ": " + response.body());
        }

        JSONObject responseBody = JSON.parseObject(response.body());
        JSONArray choices = responseBody.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            log.error("AI model returned empty choices. Full response: {}", response.body());
            throw new RuntimeException("AI model returned empty response");
        }

        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        // Thinking models return both `reasoning_content` (the chain-of-thought) and
        // `content` (the final answer). We always want `content`; fall back to
        // `reasoning_content` only if `content` is missing — that usually indicates
        // the model was truncated before producing the final answer and the user
        // still benefits from seeing the reasoning text.
        String content = message.getString("content");
        if (content == null || content.isBlank()) {
            String reasoning = message.getString("reasoning_content");
            if (reasoning != null && !reasoning.isBlank()) {
                log.warn("AI model returned empty content but non-empty reasoning_content "
                        + "(likely truncated); using reasoning text as fallback");
                content = reasoning;
            }
        }
        if (content == null) content = "";

        log.info("AI model response received, length: {}", content.length());
        return content;
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
        dto.setModelKey(config.getModelKey() != null ? config.getModelKey() : config.getModelName());
        dto.setApiEndpoint(config.getEndpoint());
        dto.setApiKey(maskApiKey(config.getApiKey()));
        dto.setMaxTokens(config.getMaxTokens() != null ? config.getMaxTokens() : 4096);
        dto.setTemperature(config.getTemperature() != null ? config.getTemperature() : 0.7);
        dto.setEnabled(config.getIsEnabled());
        dto.setThinkingMode(config.getThinkingMode() != null ? config.getThinkingMode() : Boolean.FALSE);
        dto.setCreatedAt(config.getCreatedAt());
        dto.setUpdatedAt(config.getUpdatedAt());
        return dto;
    }

    private AiModelConfig toEntity(AiModelConfigDTO dto) {
        AiModelConfig config = new AiModelConfig();
        config.setModelName(dto.getName());
        config.setProvider(dto.getProvider() != null ? dto.getProvider() : "openai");
        config.setModelKey(dto.getModelKey() != null ? dto.getModelKey() : dto.getName());
        config.setApiKey(dto.getApiKey());
        config.setEndpoint(dto.getApiEndpoint());
        config.setContextWindow(128000);
        config.setMaxTokens(dto.getMaxTokens() != null ? dto.getMaxTokens() : 4096);
        config.setTemperature(dto.getTemperature() != null ? dto.getTemperature() : 0.7);
        config.setTimeout(60);
        config.setIsEnabled(dto.getEnabled() != null ? dto.getEnabled() : true);
        // If the UI didn't provide a value, fall back to the regex heuristic so historic
        // rows and forms-without-the-field still get sensible auto-detection.
        config.setThinkingMode(dto.getThinkingMode() != null
                ? dto.getThinkingMode()
                : matchesThinkingPattern(dto.getModelKey() != null ? dto.getModelKey() : dto.getName()));
        return config;
    }

    private String buildFullApiUrl(AiModelConfig config) {
        String endpoint = config.getEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("API endpoint cannot be empty");
        }

        String provider = config.getProvider() != null ? config.getProvider().toLowerCase() : "openai";
        String baseUrl = endpoint.trim();

        // 确保URL以http开头
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "https://" + baseUrl;
        }

        // 移除末尾的斜杠
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // 根据提供商添加API路径
        switch (provider) {
            case "openai":
                if (!baseUrl.contains("/v1")) {
                    return baseUrl + "/v1/chat/completions";
                } else if (baseUrl.endsWith("/v1")) {
                    return baseUrl + "/chat/completions";
                } else {
                    return baseUrl;
                }
            case "anthropic":
                if (!baseUrl.contains("/v1/messages")) {
                    return baseUrl + "/v1/messages";
                } else {
                    return baseUrl;
                }
            case "moonshot":
                // Moonshot
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
            case "alibaba":
                // 阿里通义千问API
                if (!baseUrl.contains("/api/v1/services/aigc/text-generation/generation")) {
                    return baseUrl;
                } else {
                    return baseUrl;
                }
            default:
                // 对于自定义或其他第三方供应商：用户只需输入到 ".../v1"，
                // 我们自动补全为 OpenAI-兼容的 /chat/completions 路径。
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
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
