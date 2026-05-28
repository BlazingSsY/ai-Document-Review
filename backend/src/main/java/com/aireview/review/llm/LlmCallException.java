package com.aireview.review.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

import java.util.List;

/**
 * Thrown by {@link LlmClient} after all retries are exhausted. The pipeline
 * catches this, marks the corresponding (chunk, rule) as INCONCLUSIVE, and
 * persists {@link #attemptHistory} into {@code ai_call_logs} so the failure
 * is reproducible.
 */
@Getter
public class LlmCallException extends RuntimeException {

    /** "http_4xx" / "http_5xx" / "network" / "json_parse" / "schema_invalid" / "empty_content" */
    private final String failureKind;

    /** Last HTTP status seen, or 0 if no response was ever received. */
    private final int lastHttpStatus;

    /** Last raw response body (may be JSON or error text), for diagnosis. */
    private final String lastResponseBody;

    /** Request body that was sent on the final attempt. */
    private final JsonNode lastRequestBody;

    /** Per-attempt trail; oldest first. */
    private final List<LlmCallResponse.AttemptRecord> attemptHistory;

    public LlmCallException(String message,
                            String failureKind,
                            int lastHttpStatus,
                            String lastResponseBody,
                            JsonNode lastRequestBody,
                            List<LlmCallResponse.AttemptRecord> attemptHistory,
                            Throwable cause) {
        super(message, cause);
        this.failureKind = failureKind;
        this.lastHttpStatus = lastHttpStatus;
        this.lastResponseBody = lastResponseBody;
        this.lastRequestBody = lastRequestBody;
        this.attemptHistory = attemptHistory;
    }
}
