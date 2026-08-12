package com.aireview.modelconfig.service;

import com.aireview.modelconfig.entity.AiModelConfig;
import com.alibaba.fastjson2.JSONObject;

import java.util.Locale;

/**
 * Translates the model-level {@code thinkingMode} switch into provider-specific
 * OpenAI-compatible request fields, in <em>both</em> directions.
 *
 * <p>Semantics of {@code thinkingMode}:
 * <ul>
 *   <li>{@code true} — force thinking on ({@code thinking={type:enabled}} /
 *       {@code enable_thinking=true} / {@code reasoning_effort=medium}).</li>
 *   <li>{@code false} — force thinking <em>off</em> ({@code thinking={type:disabled}} /
 *       {@code enable_thinking=false} / {@code reasoning_effort=none}). Leaving the
 *       parameter out here is not equivalent: a hybrid model that reasons by default
 *       would keep burning the output budget on chain-of-thought and truncate the
 *       final JSON, which is exactly what the switch is meant to prevent.</li>
 *   <li>{@code null} — no stated intent, preserve the provider default and send nothing.
 *       Only reachable for in-memory probe configs; the persisted column is NOT NULL.</li>
 * </ul>
 *
 * <p>Both directions are gated on the same allow-list: only providers whose reasoning
 * switch we can name with confidence receive a parameter. Unknown/aggregation gateways
 * are left untouched. Two asymmetries are deliberate:
 * <ul>
 *   <li>Fixed-reasoning models (R1 / Reasoner / *-thinking) cannot be switched off, so a
 *       disable intent is dropped rather than sent and rejected.</li>
 *   <li>OpenAI o-series only accepts {@code low|medium|high} — there is no "off" — so a
 *       disable intent is a no-op there.</li>
 * </ul>
 *
 * <p>If a provider still rejects the parameter, the caller's one-time compatibility
 * fallback ({@link #isCompatibilityError}) retries without it and caches the result.
 */
final class ReasoningModeAdapter {

    static final String CONTROL_THINKING = "thinking";
    static final String CONTROL_ENABLE_THINKING = "enable_thinking";
    static final String CONTROL_REASONING_EFFORT = "reasoning_effort";

    record AppliedControl(String parameter, String value) {
        static AppliedControl none() {
            return new AppliedControl("", "");
        }

        boolean applied() {
            return parameter != null && !parameter.isBlank();
        }
    }

    private ReasoningModeAdapter() {
    }

    static AppliedControl apply(AiModelConfig config, JSONObject body) {
        if (config == null || body == null) {
            return AppliedControl.none();
        }
        Boolean thinkingMode = config.getThinkingMode();
        if (thinkingMode == null) {
            return AppliedControl.none();
        }
        boolean enable = thinkingMode;

        String provider = normalize(config.getProvider());
        String endpoint = normalize(config.getEndpoint());
        String model = normalize((config.getModelKey() == null ? "" : config.getModelKey())
                + " " + (config.getModelName() == null ? "" : config.getModelName()));

        // DeepSeek's official V4 API supports an explicit thinking switch in both
        // directions. Fixed-reasoning models (deepseek-reasoner / R1) have no "off",
        // so drop a disable intent instead of sending a doomed parameter.
        if (endpoint.contains("api.deepseek.com") || "deepseek".equals(provider)) {
            if (!enable && isFixedReasoningModel(model)) {
                return AppliedControl.none();
            }
            String type = enable ? "enabled" : "disabled";
            JSONObject thinking = new JSONObject();
            thinking.put("type", type);
            body.put(CONTROL_THINKING, thinking);
            return new AppliedControl(CONTROL_THINKING, type);
        }

        // SiliconFlow exposes enable_thinking for hybrid Qwen/DeepSeek/GLM models.
        // Do not add it to fixed-reasoning models (R1/Reasoner/Thinking) or MiniMax,
        // whose endpoint contracts differ — supportsEnableThinking already excludes
        // both, so the same gate serves the enable and disable directions. A
        // provider-side 400 is handled by the caller's one-time compatibility
        // fallback and cached for later requests.
        if (isSiliconFlow(provider, endpoint) && supportsEnableThinking(model)) {
            body.put(CONTROL_ENABLE_THINKING, enable);
            return new AppliedControl(CONTROL_ENABLE_THINKING, Boolean.toString(enable));
        }

        // DashScope/Bailian uses the same top-level extension for its hybrid model
        // families. MiniMax direct models deliberately remain untouched.
        if (isDashScope(provider, endpoint) && supportsEnableThinking(model)) {
            body.put(CONTROL_ENABLE_THINKING, enable);
            return new AppliedControl(CONTROL_ENABLE_THINKING, Boolean.toString(enable));
        }

        // OpenAI-compatible Google endpoints map reasoning_effort=none to a zero
        // thinking budget where the selected model supports switching it off.
        if (isGeminiOpenAiEndpoint(provider, endpoint) && isConfigurableGemini(model)) {
            String effort = enable ? "medium" : "none";
            body.put(CONTROL_REASONING_EFFORT, effort);
            return new AppliedControl(CONTROL_REASONING_EFFORT, effort);
        }

        // Only attach reasoning_effort on official OpenAI endpoints and known
        // reasoning-capable families; ordinary GPT-4.x models should not receive it.
        // The o-series accepts only low/medium/high — there is no "off" — so a
        // disable intent is intentionally a no-op rather than a guaranteed 400.
        if (enable && isOpenAi(provider, endpoint) && isOpenAiReasoningModel(model)) {
            String effort = "medium";
            body.put(CONTROL_REASONING_EFFORT, effort);
            return new AppliedControl(CONTROL_REASONING_EFFORT, effort);
        }

        return AppliedControl.none();
    }

    static boolean isCompatibilityError(int statusCode, String responseBody, AppliedControl control) {
        if (statusCode != 400 || responseBody == null || control == null || !control.applied()) {
            return false;
        }
        String lower = responseBody.toLowerCase(Locale.ROOT);
        boolean mentionsParameter = lower.contains(control.parameter().toLowerCase(Locale.ROOT))
                || (CONTROL_THINKING.equals(control.parameter()) && lower.contains("thinking mode"));
        boolean rejectsParameter = lower.contains("not support")
                || lower.contains("unsupported")
                || lower.contains("unknown")
                || lower.contains("unrecognized")
                || lower.contains("invalid")
                || lower.contains("extra_forbidden")
                || lower.contains("not allowed")
                || lower.contains("restricted");
        return mentionsParameter && rejectsParameter;
    }

    private static boolean isSiliconFlow(String provider, String endpoint) {
        return provider.contains("siliconflow") || provider.contains("硅基")
                || endpoint.contains("siliconflow.cn") || endpoint.contains("siliconflow.com");
    }

    private static boolean isDashScope(String provider, String endpoint) {
        return provider.contains("dashscope") || provider.contains("百炼") || provider.contains("阿里")
                || endpoint.contains("dashscope.aliyuncs.com");
    }

    private static boolean isGeminiOpenAiEndpoint(String provider, String endpoint) {
        return provider.contains("gemini") || provider.contains("google")
                || endpoint.contains("generativelanguage.googleapis.com");
    }

    private static boolean isOpenAi(String provider, String endpoint) {
        return "openai".equals(provider) || endpoint.contains("api.openai.com");
    }

    private static boolean supportsEnableThinking(String model) {
        if (model.contains("minimax") || isFixedReasoningModel(model)) {
            return false;
        }
        return model.contains("qwen3") || model.contains("deepseek-v3") || model.contains("deepseek-v4")
                || model.contains("glm-4.5") || model.contains("glm-4.6")
                || model.contains("glm-4.7") || model.contains("glm-5")
                || model.contains("kimi-k2");
    }

    private static boolean isFixedReasoningModel(String model) {
        return model.contains("reasoner") || model.contains("thinking")
                || model.contains("deepseek-r1") || model.matches(".*(?:^|[/_-])r1(?:$|[_-]).*");
    }

    private static boolean isConfigurableGemini(String model) {
        return model.contains("gemini-2.5") || model.contains("gemini-3");
    }

    private static boolean isOpenAiReasoningModel(String model) {
        return model.contains("gpt-5") || model.contains("gpt-oss")
                || model.matches(".*(?:^|[/_-])o[134](?:$|[._-]).*");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
