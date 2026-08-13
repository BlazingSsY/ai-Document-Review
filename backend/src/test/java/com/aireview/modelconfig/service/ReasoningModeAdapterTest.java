package com.aireview.modelconfig.service;

import com.aireview.modelconfig.entity.AiModelConfig;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 思考控制参数的翻译回归。
 *
 * <p>系统对所有模型一律请求关闭思考，但**用哪个参数关**由配置显式声明，不再按
 * provider / endpoint / 模型 id 推断。这里要防的是翻译本身出错，以及非法配置被静默吞掉。
 */
class ReasoningModeAdapterTest {

    @Test
    void translatesEnableThinkingToExplicitFalse() {
        JSONObject body = new JSONObject();
        ReasoningModeAdapter.AppliedControl control =
                ReasoningModeAdapter.apply(config("enable_thinking"), body);

        assertThat(control.parameter()).isEqualTo("enable_thinking");
        assertThat(body.getBoolean("enable_thinking"))
                .as("必须显式发 false；不发等于沿用供应商默认（混合模型默认开着思考）")
                .isFalse();
    }

    @Test
    void translatesThinkingToDisabledObject() {
        JSONObject body = new JSONObject();
        ReasoningModeAdapter.AppliedControl control =
                ReasoningModeAdapter.apply(config("thinking"), body);

        assertThat(control.parameter()).isEqualTo("thinking");
        assertThat(control.value()).isEqualTo("disabled");
        assertThat(body.getJSONObject("thinking").getString("type")).isEqualTo("disabled");
    }

    @Test
    void translatesReasoningEffortToNone() {
        JSONObject body = new JSONObject();
        ReasoningModeAdapter.AppliedControl control =
                ReasoningModeAdapter.apply(config("reasoning_effort"), body);

        assertThat(control.parameter()).isEqualTo("reasoning_effort");
        assertThat(body.getString("reasoning_effort")).isEqualTo("none");
    }

    @Test
    void sendsNothingWhenControlIsNoneOrUnset() {
        for (String value : new String[]{"none", null, "", "  "}) {
            JSONObject body = new JSONObject();
            ReasoningModeAdapter.AppliedControl control =
                    ReasoningModeAdapter.apply(config(value), body);

            assertThat(control.applied())
                    .as("reasoning_control=%s 应当什么都不发", value)
                    .isFalse();
            assertThat(body).isEmpty();
        }
    }

    @Test
    void rejectsUnknownControlInsteadOfSilentlyFallingBack() {
        assertThatThrownBy(() -> ReasoningModeAdapter.normalize("enable-thinking-please"))
                .as("配置写错必须立刻报错；静默回落成 none 会让人以为思考已关掉")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enable-thinking-please");
    }

    @Test
    void normalisesCaseAndSurroundingWhitespace() {
        assertThat(ReasoningModeAdapter.normalize("  Enable_Thinking ")).isEqualTo("enable_thinking");
        assertThat(ReasoningModeAdapter.normalize(null)).isEqualTo("none");
    }

    private AiModelConfig config(String reasoningControl) {
        AiModelConfig config = new AiModelConfig();
        config.setProvider("siliconflow");
        config.setEndpoint("https://api.siliconflow.cn/v1/chat/completions");
        config.setModelKey("deepseek-ai/DeepSeek-V4");
        config.setModelName("deepseek-ai/DeepSeek-V4");
        config.setReasoningControl(reasoningControl);
        return config;
    }
}
