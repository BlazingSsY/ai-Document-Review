package com.aireview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiModelConfigDTO {

    private Long id;

    @NotBlank(message = "Model name is required")
    private String modelName;

    @NotBlank(message = "API key is required")
    private String apiKey;

    @NotBlank(message = "Endpoint is required")
    private String endpoint;

    @NotNull(message = "Context window is required")
    private Integer contextWindow;

    private Integer timeout = 60;

    private Boolean isEnabled = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
