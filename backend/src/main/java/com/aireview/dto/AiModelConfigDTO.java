package com.aireview.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiModelConfigDTO {

    private Long id;
    private String name;
    private String provider;
    private String modelKey;
    private String apiEndpoint;
    private String apiKey;
    private Integer maxTokens;
    private Double temperature;
    private Boolean enabled;
    /**
     * When true the backend treats this model as a thinking/reasoning model: temperature
     * is omitted from the API call (server enforces its own default, e.g. Kimi K2.6
     * locks it to 1.0) and max_tokens is bumped to ≥ 16 000 so the chain-of-thought
     * has room to finish.
     */
    private Boolean thinkingMode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
