package com.aireview.review.sar.entity;

import com.aireview.common.persistence.SimpleJsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "sar_review_audit_logs", autoResultMap = true)
public class SarReviewAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private Long userId;

    private String action;

    private String targetType;

    private String targetId;

    @TableField(typeHandler = SimpleJsonbTypeHandler.class)
    private Map<String, Object> beforeValue;

    @TableField(typeHandler = SimpleJsonbTypeHandler.class)
    private Map<String, Object> afterValue;

    private String comment;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
