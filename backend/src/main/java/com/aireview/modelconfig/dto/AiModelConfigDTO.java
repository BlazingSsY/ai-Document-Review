package com.aireview.modelconfig.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiModelConfigDTO {

    private Long id;
    private String name;
    private String provider;
    /**
     * Model purpose: chat = document review LLM, embedding = vector embedding,
     * reranker = retrieval reranking model.
     */
    private String modelType;
    private String modelKey;
    private String apiEndpoint;
    private String apiKey;
    private Integer maxTokens;
    private Integer embeddingDimension;
    private Double temperature;
    private Integer timeout;
    private Boolean enabled;
    /**
     * Structured-output mode for chat models:
     * auto / json_schema / json_object / prompt_only.
     */
    private String responseFormatMode;
    /**
     * 是否可参与"跨模型对比"。固定推理模型（R1/Reasoner/*-thinking/o 系列）温度由服务器
     * 锁定、不支持 seed、参数对齐不完整，因此结果不可比，前端应给出"仅单模型"角标。
     * 由后端按模型 id 自动派生，前端不需要写。
     */
    private Boolean crossModelEligible;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
