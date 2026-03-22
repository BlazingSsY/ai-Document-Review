package com.aireview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewExecuteRequest {

    @NotNull(message = "Scenario ID is required")
    private Long scenarioId;

    @NotBlank(message = "Model name is required")
    private String selectedModel;
}
