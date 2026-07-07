package com.aireview.review.llm;

import com.aireview.modelconfig.entity.AiModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Single structured-output LLM invocation requested by the v2 review pipeline.
 *
 * <p>Strict JSON Schema is mandatory — every call carries a {@link #responseSchema}
 * that the model output must conform to. If the model returns text that doesn't
 * parse / doesn't validate, {@link LlmClient} retries up to {@link #schemaRetries}
 * times before throwing {@link LlmCallException}. This is what makes the v2
 * pipeline reproducible across models: the contract is enforced at the wire.
 *
 * <p>{@link #auditTags} flows through to {@code ai_call_logs} so we can later
 * answer "which call produced this finding" without having to grep logs.
 */
@Data
@Builder
public class LlmCallRequest {

    /** The provider config (endpoint, api key, model key, etc.). Required. */
    private final AiModelConfig model;

    /** System prompt. Should describe the role + the exact judgement criteria. */
    private final String systemPrompt;

    /** User prompt. Contains the chunk text + any context windows. */
    private final String userPrompt;

    /**
     * JSON Schema the model output must validate against (a Jackson tree).
     * Required; passing null is a programmer error.
     */
    private final JsonNode responseSchema;

    /** Schema name (some providers require it). Defaults to "structured_output". */
    @Builder.Default
    private final String schemaName = "structured_output";

    /**
     * How many times to retry on JSON parse / schema validation failure (this is
     * separate from HTTP-level retries handled inside the client).
     */
    @Builder.Default
    private final int schemaRetries = 2;

    /** Override max_tokens for this call. Null = use model config default. */
    private final Integer maxOutputTokensOverride;

    /** Seed for reproducibility. Null = don't send (some providers reject unknown seed). */
    private final Long seed;

    /**
     * Free-form tags persisted into the audit log. Caller is expected to pass
     * pipelineId, stage, chunkIndex, ruleId, checkId, etc.
     */
    private final Map<String, Object> auditTags;
}
