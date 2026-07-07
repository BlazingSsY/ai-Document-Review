package com.aireview.rule.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sar_rule_checks")
public class SarRuleCheck {

    @TableId(type = IdType.AUTO)
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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
