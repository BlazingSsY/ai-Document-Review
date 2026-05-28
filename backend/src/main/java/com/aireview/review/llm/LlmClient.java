package com.aireview.review.llm;

/**
 * Single entry point for all structured-output LLM calls in the v2 review
 * pipeline. Implementations must:
 *
 * <ol>
 *   <li>Force the model to emit JSON conforming to {@code request.responseSchema}
 *       (typically via {@code response_format: {type: "json_schema"}}).</li>
 *   <li>Set {@code temperature = 0} (unless the model is a thinking-mode model
 *       that fixes temperature server-side, in which case omit the parameter).</li>
 *   <li>Retry up to {@code request.schemaRetries} times if the response either
 *       fails to parse as JSON or fails schema validation.</li>
 *   <li>Retry on transient HTTP failures (5xx / 429 / network) with backoff.
 *       4xx (excluding 429) is permanent — fail fast.</li>
 *   <li>Return per-attempt audit data in the response (caller persists it).</li>
 * </ol>
 *
 * <p>This is a deliberately narrow surface — the pipeline orchestrator does not
 * see any provider-specific concerns (headers, body shapes, error formats).
 */
public interface LlmClient {

    LlmCallResponse call(LlmCallRequest request) throws LlmCallException;
}
