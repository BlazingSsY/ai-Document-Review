package com.aireview.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ReviewTaskDTO {

    private String id;
    private Long userId;
    private String fileName;
    private Long scenarioId;
    private String selectedModel;
    private String status;
    private Map<String, Object> aiResult;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String failReason;
}
