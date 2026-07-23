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

    /** See {@link com.aireview.modelconfig.dto.AiModelConfigDTO#thinkingMode}. */
    private Boolean thinkingMode;

    /**
     * Structured-output capability used by document review:
     * auto / json_schema / json_object / prompt_only.
     */
    private String responseFormatMode;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
