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
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelService {

    private final AiModelConfigMapper aiModelConfigMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Create a new AI model configuration.
     */
    public AiModelConfigDTO createConfig(AiModelConfigDTO dto) {
        AiModelConfig config = toEntity(dto);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.insert(config);
        log.info("AI model config created: {}", config.getModelName());
        return toDTO(config);
    }

    /**
     * Update an existing AI model configuration.
     */
    public AiModelConfigDTO updateConfig(Long id, AiModelConfigDTO dto) {
        AiModelConfig config = aiModelConfigMapper.selectById(id);
        if (config == null) {
            throw new IllegalArgumentException("AI model config not found: " + id);
        }
        config.setModelName(dto.getModelName());
        config.setApiKey(dto.getApiKey());
        config.setEndpoint(dto.getEndpoint());
        config.setContextWindow(dto.getContextWindow());
        config.setTimeout(dto.getTimeout());
        config.setIsEnabled(dto.getIsEnabled());
        config.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.updateById(config);
        log.info("AI model config updated: {}", config.getModelName());
        return toDTO(config);
    }

    /**
     * Delete an AI model configuration.
     */
    public void deleteConfig(Long id) {
        aiModelConfigMapper.deleteById(id);
        log.info("AI model config deleted: {}", id);
    }

    /**
     * Get a single AI model config by ID.
     */
    public AiModelConfigDTO getConfigById(Long id) {
        AiModelConfig config = aiModelConfigMapper.selectById(id);
        if (config == null) {
            throw new IllegalArgumentException("AI model config not found: " + id);
        }
        return toDTO(config);
    }

    /**
     * List all AI model configurations with pagination.
     */
    public PageResponse<AiModelConfigDTO> listConfigs(int page, int size) {
        Page<AiModelConfig> pageParam = new Page<>(page, size);
        Page<AiModelConfig> result = aiModelConfigMapper.selectPage(pageParam, null);
        List<AiModelConfigDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    /**
     * Get an enabled AI model config by model name.
     */
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
     * Call the AI model API with the given system prompt and user content.
     * Supports OpenAI-compatible API format (works with GPT-4o, Claude via proxy, Qwen, etc.).
     *
     * @param config       the AI model config
     * @param systemPrompt the system prompt containing review rules
     * @param userContent  the document content to review
     * @return the AI model's response text
     */
    public String callAiModel(AiModelConfig config, String systemPrompt, String userContent) throws Exception {
        log.info("Calling AI model: {} at {}", config.getModelName(), config.getEndpoint());

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModelName());
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 4096);

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
                .uri(URI.create(config.getEndpoint()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(config.getTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toJSONString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("AI model API returned status {}: {}", response.statusCode(), response.body());
            throw new RuntimeException("AI model API error: HTTP " + response.statusCode());
        }

        JSONObject responseBody = JSON.parseObject(response.body());
        JSONArray choices = responseBody.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("AI model returned empty response");
        }

        String content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content");

        log.info("AI model response received, length: {}", content.length());
        return content;
    }

    private AiModelConfigDTO toDTO(AiModelConfig config) {
        AiModelConfigDTO dto = new AiModelConfigDTO();
        dto.setId(config.getId());
        dto.setModelName(config.getModelName());
        dto.setApiKey(maskApiKey(config.getApiKey()));
        dto.setEndpoint(config.getEndpoint());
        dto.setContextWindow(config.getContextWindow());
        dto.setTimeout(config.getTimeout());
        dto.setIsEnabled(config.getIsEnabled());
        dto.setCreatedAt(config.getCreatedAt());
        dto.setUpdatedAt(config.getUpdatedAt());
        return dto;
    }

    private AiModelConfig toEntity(AiModelConfigDTO dto) {
        AiModelConfig config = new AiModelConfig();
        config.setModelName(dto.getModelName());
        config.setApiKey(dto.getApiKey());
        config.setEndpoint(dto.getEndpoint());
        config.setContextWindow(dto.getContextWindow());
        config.setTimeout(dto.getTimeout() != null ? dto.getTimeout() : 60);
        config.setIsEnabled(dto.getIsEnabled() != null ? dto.getIsEnabled() : true);
        return config;
    }

    /**
     * Mask API key for display, showing only first 4 and last 4 characters.
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
