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
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) config.setApiKey(dto.getApiKey());
        if (dto.getMaxTokens() != null) config.setMaxTokens(dto.getMaxTokens());
        if (dto.getTemperature() != null) config.setTemperature(dto.getTemperature());
        if (dto.getEnabled() != null) config.setIsEnabled(dto.getEnabled());
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

    public String callAiModel(AiModelConfig config, String systemPrompt, String userContent) throws Exception {
        log.info("Calling AI model: {} at {}", config.getModelName(), config.getEndpoint());

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModelKey() != null && !config.getModelKey().isBlank()
                ? config.getModelKey() : config.getModelName());
        requestBody.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.1);
        requestBody.put("max_tokens", config.getMaxTokens() != null ? config.getMaxTokens() : 4096);

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
        dto.setName(config.getModelName());
        dto.setProvider(config.getProvider() != null ? config.getProvider() : "openai");
        dto.setModelKey(config.getModelKey() != null ? config.getModelKey() : config.getModelName());
        dto.setApiEndpoint(config.getEndpoint());
        dto.setApiKey(maskApiKey(config.getApiKey()));
        dto.setMaxTokens(config.getMaxTokens() != null ? config.getMaxTokens() : 4096);
        dto.setTemperature(config.getTemperature() != null ? config.getTemperature() : 0.7);
        dto.setEnabled(config.getIsEnabled());
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
        return config;
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
