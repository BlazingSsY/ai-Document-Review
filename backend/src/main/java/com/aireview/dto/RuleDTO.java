package com.aireview.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RuleDTO {

    private Long id;
    private String ruleName;
    private String fileType;
    private String content;
    private Long creatorId;
    private LocalDateTime updatedAt;
    private Boolean isValid;
}
