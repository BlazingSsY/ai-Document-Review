package com.aireview.modelconfig.service;

import com.aireview.modelconfig.entity.AiModelConfig;
import com.aireview.review.llm.ThinkingModeDetector;
import com.alibaba.fastjson2.JSONObject;

import java.util.Locale;

/**
 * 把「统一关闭思考」这一策略翻译成各家 OpenAI 兼容接口的具体请求字段。
 *
 * <p>本系统对所有模型一律请求关闭思考，不再有用户可配的开关。原因见
 * {@link com.aireview.review.llm.ThinkingModeDetector}：审查要的是严格按 schema 吐
 * 结构化 JSON，思维链会和最终 JSON 抢同一份输出预算，经常把 JSON 挤没。
 *
 * <p><b>为什么必须显式发「关闭」，而不是什么都不发</b>：混合推理模型（DeepSeek V3/V4、
 * Qwen3、GLM-4.5+、Kimi K2.x）默认是开着思考的。不发参数等于沿用它的默认值，照样烧预算。
 * 这正是这个适配器存在的意义。
 *
 * <p>只对能叫得出名字的网关发参数，未知的聚合网关一律不动——发一个它不认识的字段会直接
 * 400。若仍被拒，调用方有一次性兼容回退（{@link #isCompatibilityError}）会去掉参数重试
 * 并缓存结果。
 *
 * <p>固定推理模型（R1 / Reasoner / *-thinking / o 系列）没有关闭档，对它们发 disable 是
 * 白发甚至报错，因此直接跳过；这类模型由 {@code ThinkingModeDetector} 识别后走
 * 「省略 temperature + 抬高 max_tokens」的兼容路径。
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
        String provider = normalize(config.getProvider());
        String endpoint = normalize(config.getEndpoint());
        String model = normalize((config.getModelKey() == null ? "" : config.getModelKey())
                + " " + (config.getModelName() == null ? "" : config.getModelName()));

        // 关不掉的模型不发 disable：DeepSeek reasoner / R1 / *-thinking / o 系列都没有
        // 关闭档，发过去要么被忽略要么直接 400。
        if (ThinkingModeDetector.reasonsUnconditionally(model)) {
            return AppliedControl.none();
        }

        // DeepSeek 官方 V4 接口支持显式的思考开关。
        if (endpoint.contains("api.deepseek.com") || "deepseek".equals(provider)) {
            JSONObject thinking = new JSONObject();
            thinking.put("type", "disabled");
            body.put(CONTROL_THINKING, thinking);
            return new AppliedControl(CONTROL_THINKING, "disabled");
        }

        // 硅基流动对 Qwen/DeepSeek/GLM/Kimi 等混合模型暴露 enable_thinking。
        if (isSiliconFlow(provider, endpoint) && supportsEnableThinking(model)) {
            body.put(CONTROL_ENABLE_THINKING, false);
            return new AppliedControl(CONTROL_ENABLE_THINKING, "false");
        }

        // 阿里百炼/DashScope 对同系列混合模型用同一个顶层扩展字段。
        if (isDashScope(provider, endpoint) && supportsEnableThinking(model)) {
            body.put(CONTROL_ENABLE_THINKING, false);
            return new AppliedControl(CONTROL_ENABLE_THINKING, "false");
        }

        // Gemini 的 OpenAI 兼容端点用 reasoning_effort=none 表示零思考预算。
        if (isGeminiOpenAiEndpoint(provider, endpoint) && isConfigurableGemini(model)) {
            body.put(CONTROL_REASONING_EFFORT, "none");
            return new AppliedControl(CONTROL_REASONING_EFFORT, "none");
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


    private static boolean supportsEnableThinking(String model) {
        if (model.contains("minimax") || ThinkingModeDetector.reasonsUnconditionally(model)) {
            return false;
        }
        return model.contains("qwen3") || model.contains("deepseek-v3") || model.contains("deepseek-v4")
                || model.contains("glm-4.5") || model.contains("glm-4.6")
                || model.contains("glm-4.7") || model.contains("glm-5")
                || model.contains("kimi-k2");
    }


    private static boolean isConfigurableGemini(String model) {
        return model.contains("gemini-2.5") || model.contains("gemini-3");
    }


    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
