package com.aireview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ScenarioCreateRequest {

    @NotBlank(message = "Scenario name is required")
    private String name;

    private String description;

    @NotEmpty(message = "At least one rule library must be selected")
    private List<Long> libraryIds;
}
