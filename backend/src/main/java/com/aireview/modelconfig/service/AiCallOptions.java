package com.aireview.modelconfig.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.Builder;
import lombok.Data;

/**
 * 跨模型可对齐的 AI 调用参数包。统一温度、top_p、seed 和结构化输出约束，
 * 让同一段输入在不同 provider 下尽可能收敛到相同的输出。
 *
 * <p>调用者按场景填充：
 * <ul>
 *   <li>{@link #temperature} / {@link #topP}：审查走 0 / 1.0；连接性 probe 不设。</li>
 *   <li>{@link #seed}：审查阶段必填（taskId+chunkIdx+sampleIdx 哈希），让支持
 *       seed 的 provider 在重试和双采样时保持可复现。</li>
 *   <li>{@link #structuredSchema}：JSON Schema 定义，让 provider 在解码阶段强制
 *       结构化输出（OpenAI 兼容→{@code response_format=json_schema}；Anthropic→
 *       {@code tool_use+tool_choice}；不支持的回退到 {@code response_format=json_object}
 *       或仅 prompt 约束）。</li>
 * </ul>
 */
@Data
@Builder
public class AiCallOptions {

    /** 强制温度。null 表示沿用模型默认（思维模型通常 omit）。 */
    private final Double temperature;

    /** 强制 top_p。null 表示不传该参数。 */
    private final Double topP;

    /** 复现种子。null 表示不传该参数。 */
    private final Long seed;

    /** 覆盖 max_tokens。null 表示沿用配置默认。 */
    private final Integer maxTokensOverride;

    /** 结构化输出的 JSON Schema；null 表示不强制结构化。 */
    private final JSONObject structuredSchema;

    /** structuredSchema 对应的 schema 名称（OpenAI/Anthropic 都需要）。 */
    @Builder.Default
    private final String structuredSchemaName = "review_result";

    /** 不带 structuredSchema 时是否仍然要求 JSON 对象输出（OpenAI {@code json_object} 模式）。 */
    @Builder.Default
    private final boolean forceJsonObjectFallback = false;

    /**
     * 是否对 system prompt 启用 provider 级缓存。
     * <ul>
     *   <li>Anthropic：在 system 内容块加 {@code cache_control={type:ephemeral}}，5 分钟 TTL；</li>
     *   <li>OpenAI 兼容协议（含 Moonshot/DeepSeek）：自动缓存 ≥ 1024 token 前缀，
     *       本字段不影响请求体，但作为意图标记可供日志和 audit；</li>
     *   <li>其他不支持的 provider：静默忽略。</li>
     * </ul>
     */
    @Builder.Default
    private final boolean enablePromptCache = false;

    /** 默认 options，沿用所有模型配置默认值，不做参数强制。 */
    public static AiCallOptions defaults() {
        return AiCallOptions.builder().build();
    }
}
