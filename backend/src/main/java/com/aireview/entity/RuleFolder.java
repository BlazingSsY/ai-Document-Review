package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** CHUNK 侧规则二级文件夹（规则库下、规则之上的一层分组，可整组启用/停用）。 */
@Data
@TableName("rule_folders")
public class RuleFolder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long libraryId;

    private String name;

    private Boolean enabled;

    private Long creatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
