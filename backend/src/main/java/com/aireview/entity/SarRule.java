package com.aireview.entity;

import com.aireview.config.PgJsonbStringListTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "sar_rules", autoResultMap = true)
public class SarRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ruleName;

    private String fileType;

    private String content;

    private Long creatorId;

    private Long libraryId;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Boolean isValid;

    private String ruleCode;
    private String ruleType;
    private String documentType;

    @TableField(typeHandler = PgJsonbStringListTypeHandler.class)
    private List<String> sections;

    @TableField(typeHandler = PgJsonbStringListTypeHandler.class)
    private List<String> keywords;

    private String description;
    private String sourceFile;
}
