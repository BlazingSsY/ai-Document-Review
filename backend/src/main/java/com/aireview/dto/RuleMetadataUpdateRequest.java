package com.aireview.dto;

import lombok.Data;

import java.util.List;

/**
 * Payload for {@code PUT /api/v1/rules/{id}/metadata}.
 * All fields optional — null means "leave the current value alone".
 */
@Data
public class RuleMetadataUpdateRequest {
    private String ruleName;
    private String ruleCode;
    private String ruleType;
    private String documentType;
    private List<String> sections;
    private List<String> keywords;
    private String severity;
    private String description;
}
