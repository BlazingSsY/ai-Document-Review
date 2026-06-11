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
    /**
     * "CHUNK"（全文逐章审查）或 "RAG"（智能召回审查）。
     * 由列表/详情接口在序列化时填入，让前端按管线分流后续调用。
     */
    private String reviewMode;
}
