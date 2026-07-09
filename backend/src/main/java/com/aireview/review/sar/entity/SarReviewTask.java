package com.aireview.review.sar.entity;

import com.aireview.common.persistence.SimpleJsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "sar_review_tasks", autoResultMap = true)
public class SarReviewTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private Long userId;

    private String fileName;

    private String filePath;

    private Long scenarioId;

    private String selectedModel;

    private String status;

    @TableField(typeHandler = SimpleJsonbTypeHandler.class)
    private Map<String, Object> aiResult;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private String failReason;

    /** Scalar problem count cached on completion/manual-review so the task list never reads ai_result. */
    private Integer problemCount;

    /** 是否对本次审查启用文字质量审查主线（全文扫描、结构化索引、术语/跨章一致性）。默认 true。 */
    private Boolean qualityCheckEnabled;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
