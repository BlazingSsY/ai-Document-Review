package com.aireview.service;

/**
 * Carries the HTTP status code (and body) returned by an upstream AI provider so the
 * retry layer can decide whether the failure is transient (network blip / 5xx / 429
 * rate-limit) or permanent (4xx — bad request, auth, not-found, etc.). Permanent
 * failures should bubble up immediately instead of burning N × backoff seconds.
 */
public class AiApiException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public AiApiException(int statusCode, String responseBody, String message) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
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
