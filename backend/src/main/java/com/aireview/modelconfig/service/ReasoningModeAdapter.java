package com.aireview.modelconfig.service;

import com.aireview.modelconfig.entity.AiModelConfig;
import com.alibaba.fastjson2.JSONObject;

import java.util.Locale;
import java.util.Set;

/**
 * 把模型配置里声明的「思考控制参数」翻译成请求体字段。
 *
 * <p>审查要的是严格按 schema 吐结构化 JSON，思维链会和最终 JSON 抢同一份输出预算，
 * 经常把 JSON 挤没。所以系统对所有模型一律请求关闭思考——但**用哪个参数关**因供应商而异，
 * 这一点由 {@code ai_model_config.reasoning_control} 显式声明，不再按 provider / endpoint /
 * 模型 id 猜测。
 *
 * <p>取值与产出：
 * <ul>
 *   <li>{@link #NONE} —— 不下发任何参数。适用于两类模型：本来就不思考的，
 *       以及关不掉的（R1 / Reasoner / o 系列）。后者应同时配上
 *       {@code omit_temperature} 与足够大的 {@code output_token_budget}。</li>
 *   <li>{@link #ENABLE_THINKING} —— {@code "enable_thinking": false}。硅基流动、百炼等。</li>
 *   <li>{@link #THINKING} —— {@code "thinking": {"type":"disabled"}}。DeepSeek 官方。</li>
 *   <li>{@link #REASONING_EFFORT} —— {@code "reasoning_effort": "none"}。Gemini OpenAI 兼容端点。</li>
 * </ul>
 *
 * <p>配错了会被供应商以 400 拒绝，且**不做兼容回退**。这是有意的：配置即真相，
 * 错误应当立刻暴露并由人改配置，而不是被一个进程内缓存静默吞掉——那正是重构前
 * 「四份自学习状态、没人说得清系统对某个模型到底学到了什么」的来源。
 */
final class ReasoningModeAdapter {

    static final String NONE = "none";
    static final String THINKING = "thinking";
    static final String ENABLE_THINKING = "enable_thinking";
    static final String REASONING_EFFORT = "reasoning_effort";

    static final Set<String> SUPPORTED = Set.of(NONE, THINKING, ENABLE_THINKING, REASONING_EFFORT);

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
        switch (normalize(config.getReasoningControl())) {
            case ENABLE_THINKING -> {
                body.put(ENABLE_THINKING, false);
                return new AppliedControl(ENABLE_THINKING, "false");
            }
            case THINKING -> {
                JSONObject thinking = new JSONObject();
                thinking.put("type", "disabled");
                body.put(THINKING, thinking);
                return new AppliedControl(THINKING, "disabled");
            }
            case REASONING_EFFORT -> {
                body.put(REASONING_EFFORT, NONE);
                return new AppliedControl(REASONING_EFFORT, NONE);
            }
            default -> {
                return AppliedControl.none();
            }
        }
    }

    /** 归一化并校验配置值；空值按 {@link #NONE} 处理，非法值直接拒绝而不是静默回落。 */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(value)) {
            throw new IllegalArgumentException("不支持的思考控制参数：" + raw
                    + "（可选：" + String.join(" / ", SUPPORTED) + "）");
        }
        return value;
    }
}
