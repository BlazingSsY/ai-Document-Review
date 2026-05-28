package com.aireview.review.llm;

import com.aireview.entity.AiModelConfig;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Mirrors {@code AiModelService.isThinkingModel} so the v2 client can decide
 * whether to omit {@code temperature} and bump {@code max_tokens} without
 * coupling to the legacy service.
 *
 * <p>The detection is the same: explicit {@code thinking_mode} column wins;
 * otherwise we regex-match the model id against a list of known thinking-mode
 * model families.
 */
public final class ThinkingModeDetector {

    private static final Pattern PATTERN = Pattern.compile(
            "kimi-?k?2[.\\-]?(?:thinking|5|6|7|8|9)"
            + "|glm-?(?:4\\.5|4\\.6|4\\.7|5(?:\\.[0-9]+)?)"
            + "|thinking|reasoner|-r1\\b|deepseek-r1");

    private ThinkingModeDetector() {}

    public static boolean isThinking(AiModelConfig config) {
        if (config == null) return false;
        Boolean explicit = config.getThinkingMode();
        if (explicit != null) return explicit;
        String key = config.getModelKey();
        if (key == null || key.isBlank()) key = config.getModelName();
        if (key == null || key.isBlank()) return false;
        return PATTERN.matcher(key.toLowerCase(Locale.ROOT)).find();
    }
}
