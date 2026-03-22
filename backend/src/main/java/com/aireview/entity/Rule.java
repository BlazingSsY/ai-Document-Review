package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rules")
public class Rule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ruleName;

    private String fileType;

    private String content;

    private Long creatorId;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Boolean isValid;
}
