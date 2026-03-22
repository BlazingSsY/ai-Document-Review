package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "review_tasks", autoResultMap = true)
public class ReviewTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private Long userId;

    private String fileName;

    private String filePath;

    private Long scenarioId;

    private String selectedModel;

    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> aiResult;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private String failReason;

    // Status constants
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
}
