package com.aireview.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RuleLibraryDTO {
    private Long id;
    private String name;
    private String description;
    private Long creatorId;
    private int ruleCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
