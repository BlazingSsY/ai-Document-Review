package com.aireview.modelconfig.service;

import com.aireview.modelconfig.entity.AiModelConfig;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningModeAdapterTest {

    // ---- enable direction ----

    @Test
    void explicitlyEnablesThinkingOnOfficialDeepSeekV4() {
        AiModelConfig config = config("deepseek", "https://api.deepseek.com/v1",
                "deepseek-v4-flash", true);
        JSONObject body = new JSONObject();

        ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(config, body);

        assertThat(control.parameter()).isEqualTo("thinking");
        assertThat(control.value()).isEqualTo("enabled");
        assertThat(body.getJSONObject("thinking").getString("type")).isEqualTo("enabled");
    }

    @Test
    void mapsHybridSiliconFlowModelsToEnableThinking() {
        AiModelConfig config = config("硅基流动", "https://api.siliconflow.cn/v1",
                "deepseek-ai/DeepSeek-V4-Flash", true);
        JSONObject body = new JSONObject();

        ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(config, body);

        assertThat(control.parameter()).isEqualTo("enable_thinking");
        assertThat(body.getBoolean("enable_thinking")).isTrue();
    }

    // ---- disable direction ----

    @Test
    void explicitlyDisablesThinkingOnOfficialDeepSeekV4() {
        AiModelConfig config = config("deepseek", "https://api.deepseek.com/v1",
                "deepseek-v4-flash", false);
        JSONObject body = new JSONObject();

        ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(config, body);

        assertThat(control.parameter()).isEqualTo("thinking");
        assertThat(control.value()).isEqualTo("disabled");
        assertThat(body.getJSONObject("thinking").getString("type")).isEqualTo("disabled");
    }

    @Test
    void mapsHybridGatewayModelsToEnableThinkingFalse() {
        AiModelConfig siliconFlow = config("硅基流动", "https://api.siliconflow.cn/v1",
                "deepseek-ai/DeepSeek-V4-Flash", false);
        AiModelConfig dashScope = config("阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3-max", false);

        JSONObject siliconFlowBody = new JSONObject();
        JSONObject dashScopeBody = new JSONObject();

        assertThat(ReasoningModeAdapter.apply(siliconFlow, siliconFlowBody).value()).isEqualTo("false");
        assertThat(ReasoningModeAdapter.apply(dashScope, dashScopeBody).value()).isEqualTo("false");
        assertThat(siliconFlowBody.getBoolean("enable_thinking")).isFalse();
        assertThat(dashScopeBody.getBoolean("enable_thinking")).isFalse();
    }

    @Test
    void mapsGeminiDisableIntentToZeroReasoningEffort() {
        AiModelConfig config = config("google", "https://generativelanguage.googleapis.com/v1beta/openai",
                "gemini-2.5-pro", false);
        JSONObject body = new JSONObject();

        ReasoningModeAdapter.AppliedControl control = ReasoningModeAdapter.apply(config, body);

        assertThat(control.parameter()).isEqualTo("reasoning_effort");
        assertThat(body.getString("reasoning_effort")).isEqualTo("none");
    }

    // ---- models that cannot be switched off ----

    @Test
    void neverSendsDisableToFixedReasoningModels() {
        // deepseek-reasoner / R1 always reason; a disable parameter would just 400.
        AiModelConfig reasoner = config("deepseek", "https://api.deepseek.com/v1",
                "deepseek-reasoner", false);
        AiModelConfig r1 = config("硅基流动", "https://api.siliconflow.cn/v1",
                "deepseek-ai/DeepSeek-R1", false);

        JSONObject reasonerBody = new JSONObject();
        JSONObject r1Body = new JSONObject();

        assertThat(ReasoningModeAdapter.apply(reasoner, reasonerBody).applied()).isFalse();
        assertThat(ReasoningModeAdapter.apply(r1, r1Body).applied()).isFalse();
        assertThat(reasonerBody).isEmpty();
        assertThat(r1Body).isEmpty();
    }

    @Test
    void openAiReasoningModelsAcceptEnableButNotDisable() {
        // OpenAI reasoning models expose only low/medium/high — there is no "off".
        AiModelConfig on = config("openai", "https://api.openai.com/v1", "gpt-5", true);
        AiModelConfig off = config("openai", "https://api.openai.com/v1", "gpt-5", false);

        JSONObject onBody = new JSONObject();
        JSONObject offBody = new JSONObject();

        assertThat(ReasoningModeAdapter.apply(on, onBody).value()).isEqualTo("medium");
        assertThat(ReasoningModeAdapter.apply(off, offBody).applied()).isFalse();
        assertThat(offBody).isEmpty();
    }

    // ---- allow-list boundaries ----

    @Test
    void unknownGatewaysAndOptedOutFamiliesStayUntouchedInBothDirections() {
        // An aggregation gateway is not recognised even when the model id says DeepSeek:
        // we cannot know which switch (if any) it forwards.
        AiModelConfig aggregatorOn = config("青云聚合", "https://api.qingyuntop.top/v1",
                "deepseek-v4-flash", true);
        AiModelConfig aggregatorOff = config("青云聚合", "https://api.qingyuntop.top/v1",
                "deepseek-v4-flash", false);
        AiModelConfig minimaxOff = config("硅基流动", "https://api.siliconflow.cn/v1",
                "MiniMaxAI/MiniMax-M2.5", false);

        JSONObject onBody = new JSONObject();
        JSONObject offBody = new JSONObject();
        JSONObject minimaxBody = new JSONObject();

        assertThat(ReasoningModeAdapter.apply(aggregatorOn, onBody).applied()).isFalse();
        assertThat(ReasoningModeAdapter.apply(aggregatorOff, offBody).applied()).isFalse();
        assertThat(ReasoningModeAdapter.apply(minimaxOff, minimaxBody).applied()).isFalse();
        assertThat(onBody).isEmpty();
        assertThat(offBody).isEmpty();
        assertThat(minimaxBody).isEmpty();
    }

    @Test
    void nullThinkingModePreservesProviderDefault() {
        // No stated intent (in-memory probe configs only — the persisted column is NOT NULL).
        AiModelConfig config = config("deepseek", "https://api.deepseek.com/v1",
                "deepseek-v4-flash", null);
        JSONObject body = new JSONObject();

        assertThat(ReasoningModeAdapter.apply(config, body).applied()).isFalse();
        assertThat(body).isEmpty();
    }

    // ---- compatibility fallback ----

    @Test
    void detectsProviderRejectionOfReasoningControl() {
        ReasoningModeAdapter.AppliedControl control =
                new ReasoningModeAdapter.AppliedControl("enable_thinking", "false");

        assertThat(ReasoningModeAdapter.isCompatibilityError(
                400, "The model does not support enable_thinking", control)).isTrue();
        assertThat(ReasoningModeAdapter.isCompatibilityError(
                401, "The model does not support enable_thinking", control)).isFalse();
        assertThat(ReasoningModeAdapter.isCompatibilityError(
                400, "invalid response_format", control)).isFalse();
    }

    @Test
    void detectsRejectionOfTheDeepSeekThinkingObject() {
        ReasoningModeAdapter.AppliedControl control =
                new ReasoningModeAdapter.AppliedControl("thinking", "disabled");

        assertThat(ReasoningModeAdapter.isCompatibilityError(
                400, "thinking mode is not supported for this model", control)).isTrue();
        assertThat(ReasoningModeAdapter.isCompatibilityError(
                400, "Extra inputs are not permitted: thinking [type=extra_forbidden]", control)).isTrue();
    }

    private AiModelConfig config(String provider, String endpoint, String modelKey, Boolean thinkingMode) {
        AiModelConfig config = new AiModelConfig();
        config.setProvider(provider);
        config.setEndpoint(endpoint);
        config.setModelKey(modelKey);
        config.setModelName(modelKey);
        config.setThinkingMode(thinkingMode);
        return config;
    }
}
