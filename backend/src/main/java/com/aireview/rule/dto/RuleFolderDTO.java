package com.aireview.rule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/** 规则二级文件夹 DTO（三条管线共用）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleFolderDTO {

    private Long id;
    private Long libraryId;
    private String name;
    private Boolean enabled;
    private Long creatorId;
    private Integer ruleCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
