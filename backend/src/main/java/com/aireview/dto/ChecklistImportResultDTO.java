package com.aireview.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChecklistImportResultDTO {

    private String sourceFile;
    private String generatedRuleFile;
    private String ruleCode;
    private Integer ruleCount;
    private Integer checkCount;
    private String canonicalJson;
    private List<RuleDTO> importedRules;
}
