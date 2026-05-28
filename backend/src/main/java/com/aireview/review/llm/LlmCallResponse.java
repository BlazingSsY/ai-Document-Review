package com.aireview.review.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of a successful structured-output LLM call. The pipeline reads
 * {@link #parsedOutput} for business logic and writes the rest into
 * {@code ai_call_logs} for audit.
 *
 * <p>Failed calls do not produce a response — the client throws
 * {@link LlmCallException} instead, which carries the same audit fields.
 */
@Data
@Builder
public class LlmCallResponse {

    /** Parsed + schema-validated JSON the model returned. */
    private final JsonNode parsedOutput;

    /** Raw text content from the model (pre-parse, for forensics). */
    private final String rawContent;

    /** Final HTTP status (200 by definition for a success). */
    private final int httpStatus;

    /** Wall-clock duration of the last attempt in ms. */
    private final long durationMs;

    /** Number of attempts spent (1 = succeeded on first try). */
    private final int attempts;

    /** Provider request body, as actually sent. */
    private final JsonNode requestBody;

    /** Provider response body, as actually received (parsed JSON). */
    private final JsonNode responseBody;

    /** Per-attempt audit trail; oldest first. Useful when retries happened. */
    private final List<AttemptRecord> attemptHistory;

    @Data
    @Builder
    public static class AttemptRecord {
        private final int attemptNumber;
        private final int httpStatus;
        private final long durationMs;
        /** "ok" / "http_error" / "json_parse_error" / "schema_invalid" / "empty_content" */
        private final String outcome;
        private final String errorMessage;
        private final String rawContent;
    }
}
