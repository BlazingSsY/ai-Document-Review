package com.aireview.entity;

import com.aireview.config.PgJsonbStringListTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "rules", autoResultMap = true)
public class Rule {

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

    // Editable metadata. Populated from content frontmatter / multi-rule auto-detection on
    // upload, then overridable by the user via PUT /api/v1/rules/{id}/metadata.

    private String ruleCode;
    private String ruleType;        // global / section_specific / document_specific / output
    private String documentType;

    @TableField(typeHandler = PgJsonbStringListTypeHandler.class)
    private List<String> sections;

    @TableField(typeHandler = PgJsonbStringListTypeHandler.class)
    private List<String> keywords;

    private String description;     // short human-readable summary
    /** Original uploaded filename — useful when one file expanded into many rules. */
    private String sourceFile;
}
