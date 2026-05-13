package com.aireview.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RuleDTO {

    private Long id;
    private String ruleName;
    private String fileType;
    private String content;
    private Long creatorId;
    private Long libraryId;
    private LocalDateTime updatedAt;
    private Boolean isValid;

    /** Rule metadata. Defaults are filled in on upload (frontmatter / auto-detect from content);
     *  the user can override them via the rule edit modal. */
    private String ruleCode;
    private String ruleType;
    private String documentType;
    private List<String> sections;
    private List<String> keywords;
    private String severity;
    private String description;
    private String sourceFile;
}
