package com.aireview.modelconfig.service;

import com.aireview.modelconfig.entity.AiModelConfig;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「统一关闭思考」策略的翻译回归。
 *
 * <p>系统已移除用户可配的「思考模式」开关，对所有模型一律请求关闭思考。要防的回归有三类：
 * 该发关闭参数的没发（混合模型会按默认继续思考、把最终 JSON 挤没）、不该发的乱发
 * （未知网关会 400）、以及对关不掉的固定推理模型发了注定无效的参数。
 */
class ReasoningModeAdapterTest {

    // ---------- 能关的：必须显式发关闭参数 ----------

    @Test
    void disablesThinkingOnOfficialDeepSeek() {
        JSONObject body = new JSONObject();
        ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(
                config("deepseek", "https://api.deepseek.com/v1", "deepseek-v4-flash"), body);

        assertThat(control.parameter()).isEqualTo("thinking");
        assertThat(control.value()).isEqualTo("disabled");
        assertThat(body.getJSONObject("thinking").getString("type")).isEqualTo("disabled");
    }

    @Test
    void disablesThinkingOnSiliconFlowHybridModels() {
        JSONObject body = new JSONObject();
        ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(
                config("硅基流动", "https://api.siliconflow.cn/v1",
                        "deepseek-ai/DeepSeek-V4-Flash"), body);

        assertThat(control.parameter()).isEqualTo("enable_thinking");
        assertThat(body.getBoolean("enable_thinking"))
                .as("必须显式发 false；不发等于沿用供应商默认（开着思考）")
                .isFalse();
    }

    @Test
    void disablesThinkingOnDashScopeHybridModels() {
        JSONObject body = new JSONObject();
        ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(
                config("阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3-max"), body);

        assertThat(control.parameter()).isEqualTo("enable_thinking");
        assertThat(body.getBoolean("enable_thinking")).isFalse();
    }

    @Test
    void mapsGeminiToZeroReasoningEffort() {
        JSONObject body = new JSONObject();
        ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(
                config("google", "https://generativelanguage.googleapis.com/v1beta/openai",
                        "gemini-2.5-pro"), body);

        assertThat(control.parameter()).isEqualTo("reasoning_effort");
        assertThat(body.getString("reasoning_effort")).isEqualTo("none");
    }

    // ---------- 关不掉的：不发注定无效的参数 ----------

    @Test
    void sendsNothingForFixedReasoningModels() {
        for (String modelKey : new String[]{
                "deepseek-reasoner", "deepseek-ai/DeepSeek-R1", "kimi-k2-thinking", "o3-mini"}) {
            JSONObject body = new JSONObject();
            ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(
                    config("deepseek", "https://api.deepseek.com/v1", modelKey), body);

            assertThat(control.applied())
                    .as("%s 没有关闭档，发 disable 会被忽略甚至报 400", modelKey)
                    .isFalse();
            assertThat(body).isEmpty();
        }
    }

    // ---------- 不认识的网关：一律不动 ----------

    @Test
    void sendsNothingToUnknownGateways() {
        JSONObject body = new JSONObject();
        ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(
                config("自建网关", "https://llm.internal.example.com/v1", "deepseek-v4-flash"), body);

        assertThat(control.applied())
                .as("未知网关不认识的字段会直接 400，宁可不发")
                .isFalse();
        assertThat(body).isEmpty();
    }

    @Test
    void sendsNothingForMinimaxOnSiliconFlow() {
        JSONObject body = new JSONObject();
        ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(
                config("硅基流动", "https://api.siliconflow.cn/v1", "MiniMaxAI/MiniMax-M2.5"), body);

        assertThat(control.applied()).isFalse();
        assertThat(body).isEmpty();
    }

    // ---------- 供应商拒绝参数时的一次性兼容回退 ----------

    @Test
    void detectsParameterRejectionAsCompatibilityError() {
        ReasoningModeAdapter.AppliedControl control =
                new ReasoningModeAdapter.AppliedControl("enable_thinking", "false");

        assertThat(ReasoningModeAdapter.isCompatibilityError(
                400, "{\"error\":\"enable_thinking is not supported\"}", control)).isTrue();
        assertThat(ReasoningModeAdapter.isCompatibilityError(
                400, "{\"error\":\"rate limited\"}", control))
                .as("与参数无关的 400 不能当成兼容问题，否则会误关掉正确的参数")
                .isFalse();
        assertThat(ReasoningModeAdapter.isCompatibilityError(
                429, "{\"error\":\"enable_thinking is not supported\"}", control))
                .as("只有 400 才是参数问题")
                .isFalse();
    }

    private AiModelConfig config(String provider, String endpoint, String modelKey) {
        AiModelConfig config = new AiModelConfig();
        config.setProvider(provider);
        config.setEndpoint(endpoint);
        config.setModelKey(modelKey);
        config.setModelName(modelKey);
        return config;
    }
}
