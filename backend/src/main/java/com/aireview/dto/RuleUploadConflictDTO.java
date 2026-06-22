package com.aireview.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleUploadConflictDTO {
    private Long id;
    private String ruleName;
    private String ruleCode;
    private String sourceFile;
    private Long libraryId;
    private Long folderId;
    private LocalDateTime updatedAt;
}
