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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
