package com.aireview.modelconfig.service;

/**
 * Carries the HTTP status code (and body) returned by an upstream AI provider so the
 * retry layer can decide whether the failure is transient (network blip / 5xx / 429
 * rate-limit) or permanent (4xx — bad request, auth, not-found, etc.). Permanent
 * failures should bubble up immediately instead of burning N × backoff seconds.
 */
public class AiApiException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;
    /**
     * Seconds the provider asked us to wait before retrying, parsed from the
     * {@code Retry-After} response header. {@code -1} when the header is absent or
     * unparseable. The retry layer honours this on 429 so we don't hammer the
     * provider inside its rate-limit window.
     */
    private final long retryAfterSeconds;

    public AiApiException(int statusCode, String responseBody, String message) {
        this(statusCode, responseBody, message, -1L);
    }

    public AiApiException(int statusCode, String responseBody, String message, long retryAfterSeconds) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * 429 (rate limited) and 5xx (server errors) are worth retrying with backoff.
     * Everything else (400 bad params, 401 auth, 403 forbidden, 404 not found) is a
     * permanent client-side problem: retrying just wastes wall-clock time.
     */
    public boolean isRetryable() {
        return statusCode == 429 || statusCode >= 500;
    }
}
