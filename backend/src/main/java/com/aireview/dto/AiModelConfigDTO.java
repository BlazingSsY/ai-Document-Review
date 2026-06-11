package com.aireview.dto;

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
     * When true the backend treats this model as a thinking/reasoning model: temperature
     * is omitted from the API call (server enforces its own default, e.g. Kimi K2.6
     * locks it to 1.0) and max_tokens is bumped to ≥ 16 000 so the chain-of-thought
     * has room to finish.
     */
    private Boolean thinkingMode;
    /**
     * 是否可参与"跨模型对比"。思维模型温度由服务器锁定、不支持 seed、参数对齐不完整，
     * 因此跨模型对比时结果不可比，前端应在选择列表里给出"仅单模型"角标。
     * 由后端基于 thinkingMode 自动派生，前端不需要写。
     */
    private Boolean crossModelEligible;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
