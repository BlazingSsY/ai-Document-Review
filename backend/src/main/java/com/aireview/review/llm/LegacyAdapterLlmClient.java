package com.aireview.review.llm;

import com.aireview.entity.AiModelConfig;
import com.aireview.service.AiApiException;
import com.aireview.service.AiModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link LlmClient} implementation. Rather than reimplementing the
 * provider-specific HTTP plumbing (URL resolution, thinking-mode handling,
 * masked-API-key guards, 4xx/5xx classification) the adapter reuses the legacy
 * {@link AiModelService#callAiModel} that has already been hardened across
 * Moonshot, GLM and OpenAI in production. On top of that base we layer the
 * three new contracts the v2 pipeline depends on:
 *
 * <ol>
 *   <li><b>Schema enforcement.</b> The caller-supplied JSON Schema is rendered
 *       into the system prompt as a hard formatting requirement, and the
 *       returned content is validated client-side via {@link SchemaValidator}.
 *       The wire-level {@code response_format: json_schema} mode (OpenAI-only)
 *       is intentionally skipped — empirically Moonshot/GLM reject it and the
 *       extra round-trip isn't worth the breakage.</li>
 *   <li><b>Two-tier retry.</b> HTTP transient failures (5xx / 429 / network)
 *       are retried inline with exponential backoff. JSON-parse and schema-
 *       validation failures are retried as fresh attempts (each one a brand-new
 *       chat completion) up to {@code request.schemaRetries}. HTTP 4xx
 *       (excluding 429) is permanent — fail fast.</li>
 *   <li><b>Per-attempt audit.</b> Every wire call produces an
 *       {@link LlmCallResponse.AttemptRecord} in chronological order. The
 *       caller persists the trail into {@code ai_call_logs}.</li>
 * </ol>
 */
@Slf4j
@Service
public class LegacyAdapterLlmClient implements LlmClient {

    private final AiModelService aiModelService;
    private final SchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;

    private final int httpMaxAttempts;
    private final long httpRetryIntervalMs;

    public LegacyAdapterLlmClient(AiModelService aiModelService,
                                   SchemaValidator schemaValidator,
                                   ObjectMapper objectMapper,
                                   @Value("${review.retry.max-attempts:4}") int httpMaxAttempts,
                                   @Value("${review.retry.interval-ms:1000}") long httpRetryIntervalMs) {
        this.aiModelService = aiModelService;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
        this.httpMaxAttempts = Math.max(1, httpMaxAttempts);
        this.httpRetryIntervalMs = Math.max(0, httpRetryIntervalMs);
    }

    @Override
    public LlmCallResponse call(LlmCallRequest request) throws LlmCallException {
        validate(request);

        AiModelConfig model = request.getModel();
        String effectiveSystemPrompt = buildEffectiveSystemPrompt(
                request.getSystemPrompt(), request.getResponseSchema());

        List<LlmCallResponse.AttemptRecord> trail = new ArrayList<>();
        int schemaAttemptsAllowed = Math.max(1, request.getSchemaRetries() + 1);
        int attemptNumber = 0;
        String lastRawContent = "";
        int lastHttpStatus = 0;
        Exception lastTransientException = null;

        for (int schemaAttempt = 1; schemaAttempt <= schemaAttemptsAllowed; schemaAttempt++) {

            // ---- HTTP-level retry loop ----
            String content = null;
            int httpStatus = 0;
            long durationMs = 0L;
            for (int httpAttempt = 1; httpAttempt <= httpMaxAttempts; httpAttempt++) {
                attemptNumber++;
                long started = System.currentTimeMillis();
                try {
                    content = aiModelService.callAiModel(model, effectiveSystemPrompt, request.getUserPrompt());
                    durationMs = System.currentTimeMillis() - started;
                    httpStatus = 200;
                    lastHttpStatus = 200;
                    lastRawContent = content == null ? "" : content;
                    break; // got a 200 response, exit HTTP loop and proceed to parse
                } catch (AiApiException ae) {
                    durationMs = System.currentTimeMillis() - started;
                    httpStatus = ae.getStatusCode();
                    lastHttpStatus = httpStatus;
                    lastRawContent = ae.getResponseBody() == null ? "" : ae.getResponseBody();
                    trail.add(LlmCallResponse.AttemptRecord.builder()
                            .attemptNumber(attemptNumber)
                            .httpStatus(httpStatus)
                            .durationMs(durationMs)
                            .outcome("http_error")
                            .errorMessage("HTTP " + httpStatus + ": " + truncate(ae.getResponseBody(), 500))
                            .rawContent(null)
                            .build());
                    if (!ae.isRetryable()) {
                        // 4xx — permanent. Abort everything.
                        throw new LlmCallException(
                                "AI HTTP " + httpStatus + " (permanent): " + truncate(ae.getResponseBody(), 200),
                                "http_4xx", httpStatus, lastRawContent, null, trail, ae);
                    }
                    lastTransientException = ae;
                    sleepBetweenAttempts(httpAttempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmCallException("Interrupted during LLM call", "network",
                            lastHttpStatus, lastRawContent, null, trail, ie);
                } catch (Exception e) {
                    durationMs = System.currentTimeMillis() - started;
                    lastTransientException = e;
                    trail.add(LlmCallResponse.AttemptRecord.builder()
                            .attemptNumber(attemptNumber)
                            .httpStatus(0)
                            .durationMs(durationMs)
                            .outcome("http_error")
                            .errorMessage("Network/IO: " + truncate(e.getMessage(), 500))
                            .rawContent(null)
                            .build());
                    sleepBetweenAttempts(httpAttempt);
                }
            } // end HTTP loop

            if (content == null) {
                // HTTP loop exhausted without a 200. Move on to next schema attempt;
                // if this was the last schema attempt, fall through to the exception below.
                continue;
            }

            // ---- Parse + validate ----
            long parseStarted = System.currentTimeMillis();
            JsonNode parsed = JsonExtractor.extract(content, objectMapper);
            if (parsed == null) {
                trail.add(LlmCallResponse.AttemptRecord.builder()
                        .attemptNumber(attemptNumber)
                        .httpStatus(200)
                        .durationMs(durationMs + (System.currentTimeMillis() - parseStarted))
                        .outcome("json_parse_error")
                        .errorMessage("Could not extract a JSON object from the response")
                        .rawContent(truncate(content, 2000))
                        .build());
                continue; // retry as next schema attempt
            }

            SchemaValidator.Result validation = schemaValidator.validate(request.getResponseSchema(), parsed);
            if (!validation.isValid()) {
                trail.add(LlmCallResponse.AttemptRecord.builder()
                        .attemptNumber(attemptNumber)
                        .httpStatus(200)
                        .durationMs(durationMs + (System.currentTimeMillis() - parseStarted))
                        .outcome("schema_invalid")
                        .errorMessage("Schema violation: " + validation.getErrorSummary())
                        .rawContent(truncate(content, 2000))
                        .build());
                continue;
            }

            // ---- Success ----
            trail.add(LlmCallResponse.AttemptRecord.builder()
                    .attemptNumber(attemptNumber)
                    .httpStatus(200)
                    .durationMs(durationMs)
                    .outcome("ok")
                    .errorMessage(null)
                    .rawContent(truncate(content, 2000))
                    .build());

            return LlmCallResponse.builder()
                    .parsedOutput(parsed)
                    .rawContent(content)
                    .httpStatus(200)
                    .durationMs(durationMs)
                    .attempts(attemptNumber)
                    .requestBody(buildRequestSnapshot(model, effectiveSystemPrompt, request.getUserPrompt()))
                    .responseBody(null) // adapter doesn't see the raw provider body
                    .attemptHistory(trail)
                    .build();
        } // end schema loop

        // All schema attempts exhausted.
        String lastOutcome = trail.isEmpty() ? "unknown" : trail.get(trail.size() - 1).getOutcome();
        String failureKind;
        switch (lastOutcome) {
            case "schema_invalid":  failureKind = "schema_invalid"; break;
            case "json_parse_error": failureKind = "json_parse"; break;
            case "http_error":      failureKind = lastHttpStatus >= 500 ? "http_5xx" : "network"; break;
            default:                failureKind = "unknown";
        }
        throw new LlmCallException(
                "LLM call failed after " + attemptNumber + " attempts (last outcome: " + lastOutcome + ")",
                failureKind, lastHttpStatus, lastRawContent,
                buildRequestSnapshot(model, effectiveSystemPrompt, request.getUserPrompt()),
                trail, lastTransientException);
    }

    // ---------- helpers ----------

    private void validate(LlmCallRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("LlmCallRequest is null");
        }
        if (request.getModel() == null) {
            throw new IllegalArgumentException("LlmCallRequest.model is null");
        }
        if (request.getResponseSchema() == null) {
            throw new IllegalArgumentException("LlmCallRequest.responseSchema is null (structured-output is mandatory)");
        }
        if (request.getUserPrompt() == null) {
            throw new IllegalArgumentException("LlmCallRequest.userPrompt is null");
        }
    }

    /**
     * Append a strict JSON-only instruction + the schema text to the caller's
     * system prompt. This works on every OpenAI-compatible provider we use
     * (Moonshot/GLM/OpenAI) because no provider-specific {@code response_format}
     * is required — the contract is enforced post-hoc by {@link SchemaValidator}.
     */
    private String buildEffectiveSystemPrompt(String original, JsonNode schema) {
        String schemaText;
        try {
            schemaText = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (Exception e) {
            schemaText = schema.toString();
        }
        StringBuilder sb = new StringBuilder();
        if (original != null && !original.isBlank()) {
            sb.append(original.trim()).append("\n\n");
        }
        sb.append("【输出格式 / Output format】\n");
        sb.append("你必须只输出一个 JSON 对象，且严格符合下面的 JSON Schema：\n");
        sb.append("```json\n").append(schemaText).append("\n```\n");
        sb.append("禁止输出任何解释、前后缀、markdown 围栏或其它文字 —— 只能是符合 schema 的 JSON 对象本身。");
        return sb.toString();
    }

    private ObjectNode buildRequestSnapshot(AiModelConfig model, String systemPrompt, String userPrompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model_key", model.getModelKey() != null ? model.getModelKey() : model.getModelName());
        root.put("provider", model.getProvider());
        root.put("system_prompt", systemPrompt);
        root.put("user_prompt", userPrompt);
        return root;
    }

    private void sleepBetweenAttempts(int httpAttempt) {
        if (httpAttempt >= httpMaxAttempts) return;
        long sleepMs = Math.min(httpRetryIntervalMs * (1L << (httpAttempt - 1)), 30_000L);
        if (sleepMs <= 0) return;
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…(+" + (s.length() - max) + ")";
    }
}
