package com.aireview.review.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Best-effort extractor for a single JSON object embedded in noisy LLM output.
 * Used by the SAR (structured review) pipeline.
 *
 * <p>Order of attempts:
 * <ol>
 *   <li>Strip an outer ```json ... ``` (or bare ```) fence if present, parse the inside.</li>
 *   <li>Parse the trimmed text directly.</li>
 *   <li>Scan for the first balanced {...} object and parse that.</li>
 *   <li>Look inside any embedded ```json fence and repeat (1)+(3).</li>
 * </ol>
 *
 * <p><b>这里比逐章管线的 {@code ReviewService.tryParseAiJson} 弱两处</b>，改动前请留意：
 * 一是不剥离 {@code <think>/<thinking>/<analysis>} 思考块，二是不做截断 JSON 的补全抢救；
 * 且第 3 步取的是「第一个」配平对象，而逐章管线已改为在全部顶层对象里挑带
 * {@code issues}/{@code check_results} 标志的那个，以免命中模型在思考里试写的草稿。
 * 若 SAR 管线重新启用并接入思考模型，这三点需要同步过来。
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
