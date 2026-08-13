package com.aireview.modelconfig.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_model_config")
public class AiModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String modelName;

    private String provider;

    private String modelType;

    private String modelKey;

    private String apiKey;

    private String endpoint;

    private Integer contextWindow;

    private Integer maxTokens;

    private Integer embeddingDimension;

    private Double temperature;

    private Integer timeout;

    private Boolean isEnabled;

    /**
     * Structured-output capability used by document review:
     * auto / json_schema / json_object / prompt_only.
     */
    private String responseFormatMode;

    /**
     * 关闭思考时下发哪个请求参数：none / enable_thinking / thinking / reasoning_effort。
     * 见 {@link com.aireview.modelconfig.service.ReasoningModeAdapter}。
     */
    private String reasoningControl;

    /** 是否不下发 temperature（服务端锁定自身取值的推理模型勾选）。 */
    private Boolean omitTemperature;

    /** 审查请求的输出预算下限（tokens）。为空时按检查项数量动态计算。 */
    private Integer outputTokenBudget;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
