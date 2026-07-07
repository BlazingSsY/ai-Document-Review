package com.aireview.rule.dto;

import lombok.Data;

@Data
public class RuleCheckDTO {

    private Long id;
    private Long ruleId;
    private String checkCode;
    private String checkType;
    private String question;
    private String passCriteria;
    private String category;
    private Boolean evidenceRequired;
    private Integer displayOrder;
    private Boolean isActive;
}
