package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rule_libraries")
public class RuleLibrary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Long creatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
