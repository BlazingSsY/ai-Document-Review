package com.aireview.review.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Best-effort extractor for a single JSON object embedded in noisy LLM output.
 * Mirrors the behaviour of {@code ReviewService.tryParseAiJson} so the v2
 * pipeline sees the same parse leniency the legacy pipeline already relied on.
 *
 * <p>Order of attempts:
 * <ol>
 *   <li>Strip an outer ```json ... ``` (or bare ```) fence if present, parse the inside.</li>
 *   <li>Parse the trimmed text directly.</li>
 *   <li>Scan for the first balanced {...} object and parse that.</li>
 *   <li>Look inside any embedded ```json fence and repeat (1)+(3).</li>
 * </ol>
 */
public final class JsonExtractor {

    private JsonExtractor() {}

    public static JsonNode extract(String raw, ObjectMapper mapper) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;

        // 1) Outer fenced block: ```json ... ``` or ``` ... ```
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            String inner = firstNewline > 0 ? text.substring(firstNewline + 1) : text.substring(3);
            int closingFence = inner.lastIndexOf("```");
            if (closingFence >= 0) inner = inner.substring(0, closingFence);
            text = inner.trim();
        }

        // 2) Direct parse.
        JsonNode direct = parseSilently(text, mapper);
        if (direct != null && direct.isObject()) return direct;

        // 3) First balanced {...} extracted from the text.
        String extracted = extractFirstJsonObject(text);
        if (extracted != null) {
            JsonNode node = parseSilently(extracted, mapper);
            if (node != null && node.isObject()) return node;
        }

        // 4) Embedded ```json fence inside a longer message.
        int fenceStart = text.indexOf("```json");
        if (fenceStart >= 0) {
            int contentStart = text.indexOf('\n', fenceStart);
            int fenceEnd = text.indexOf("```", contentStart > 0 ? contentStart : fenceStart + 7);
            if (contentStart > 0 && fenceEnd > contentStart) {
                String inner = text.substring(contentStart + 1, fenceEnd).trim();
                JsonNode node = parseSilently(inner, mapper);
                if (node != null && node.isObject()) return node;
                String innerExtract = extractFirstJsonObject(inner);
                if (innerExtract != null) {
                    JsonNode node2 = parseSilently(innerExtract, mapper);
                    if (node2 != null && node2.isObject()) return node2;
                }
            }
        }
        return null;
    }

    private static JsonNode parseSilently(String s, ObjectMapper mapper) {
        try {
            return mapper.readTree(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * Walk the text once, tracking brace depth while respecting string literals
     * (including escaped quotes), and return the first balanced {...} segment
     * we encounter. Returns null if no balanced object is found.
     */
    private static String extractFirstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
