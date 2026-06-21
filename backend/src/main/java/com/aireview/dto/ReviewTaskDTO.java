package com.aireview.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ReviewTaskDTO {

    private String id;
    private Long userId;
    private String fileName;
    private Long scenarioId;
    private String selectedModel;
    private String status;
    private Map<String, Object> aiResult;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String failReason;
    /** Cached scalar problem count for the task list (avoids shipping the full aiResult). */
    private Integer problemCount;
    /**
     * 进行中任务的最近进度（0~100），来自后端内存进度表（{@code WebSocketService}）。
     * 仅在任务进行中时非空；用于前端整页硬刷新后立即显示进度条，无需等下一条 WS 帧。
     */
    private Integer progress;
    /**
     * "CHUNK"（全文逐章审查）或 "RAG"（智能召回审查）。
     * 由列表/详情接口在序列化时填入，让前端按管线分流后续调用。
     */
    private String reviewMode;
}
