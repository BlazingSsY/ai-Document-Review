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
     * 关闭思考时下发哪个参数：none / enable_thinking / thinking / reasoning_effort。
     * 系统不再按模型 id 猜测，由这里显式声明。填错会被供应商以 400 拒绝——这是有意的，
     * 让配置错误立刻暴露，而不是被静默吞掉。
     */
    private String reasoningControl;
    /** 是否不下发 temperature。服务端锁定自身取值的推理模型勾选。 */
    private Boolean omitTemperature;
    /** 输出预算下限（tokens）；留空按检查项数量动态计算。 */
    private Integer outputTokenBudget;
    /**
     * 是否可参与"跨模型对比"。温度被服务端锁定的模型（{@link #omitTemperature}）
     * 参数对齐不完整、结果不可比，前端应给出"仅单模型"角标。由后端派生，前端不需要写。
     */
    private Boolean crossModelEligible;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
