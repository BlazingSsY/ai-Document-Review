package com.aireview.review.chunk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 思考模型输出里挑最终 JSON 的回归。
 *
 * <p>针对的场景：网关把 reasoning 以**无标签**纯文本混进 content（没有 &lt;think&gt; 可剥），
 * 而 R1 这类模型常在思考里先试写一份 JSON。取「第一个」配平对象会锁定草稿，
 * 表现为审查条目莫名变少且不报错。
 */
class ReasoningJsonSelectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void prefersTheFinalResultOverADraftWrittenInsideUntaggedReasoning() {
        String response = """
                我先梳理一下这一章要查什么。初步草拟：
                {"summary":"初步","issues":[{"rule_code":"QTP-GEN-01"}],"check_results":[]}
                再检查一遍，发现还漏了两条，最终结果如下：
                {"summary":"最终","issues":[{"rule_code":"QTP-GEN-01"},{"rule_code":"QTP-GEN-02"},{"rule_code":"QTP-GEN-03"}],"check_results":[],"passed_items":[]}
                """;

        Map<String, Object> result = ReviewService.extractBestJsonObject(response, MAPPER);

        assertThat(result).isNotNull();
        assertThat(result.get("summary")).isEqualTo("最终");
        assertThat((List<?>) result.get("issues"))
                .as("必须取最终结果，而不是思考里条目更少的草稿")
                .hasSize(3);
    }

    @Test
    void prefersTheObjectCarryingResultMarkersOverAnEarlierUnrelatedObject() {
        String response = """
                先看一下配置对象：{"model":"deepseek-reasoner","temperature":0.2}
                审查结果：
                {"summary":"本章缺少允差","issues":[{"rule_code":"QTP-GEN-02"}],"check_results":[],"passed_items":[]}
                """;

        Map<String, Object> result = ReviewService.extractBestJsonObject(response, MAPPER);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("issues");
        assertThat(result).doesNotContainKey("model");
    }

    @Test
    void keepsTheOnlyObjectWhenThereIsNoReasoningNoise() {
        String response = "```json\n{\"summary\":\"ok\",\"issues\":[],\"check_results\":[]}\n```";

        Map<String, Object> result = ReviewService.extractBestJsonObject(response, MAPPER);

        assertThat(result).isNotNull();
        assertThat(result.get("summary")).isEqualTo("ok");
    }

    @Test
    void fallsBackToAParseableObjectWhenNoneCarriesResultMarkers() {
        String response = "模型只回了个状态：{\"status\":\"no_findings\"}";

        Map<String, Object> result = ReviewService.extractBestJsonObject(response, MAPPER);

        assertThat(result).isNotNull();
        assertThat(result.get("status")).isEqualTo("no_findings");
    }

    @Test
    void returnsNullWhenNothingParses() {
        assertThat(ReviewService.extractBestJsonObject("完全没有 JSON 的一段说明文字。", MAPPER)).isNull();
        assertThat(ReviewService.extractBestJsonObject("{未闭合的对象", MAPPER)).isNull();
    }

    // ---------- 顶层配平扫描 ----------

    @Test
    void bracesInsideStringLiteralsDoNotBreakScanning() {
        String text = "{\"summary\":\"这里有个 { 和 } 以及转义引号 \\\" 结尾\",\"issues\":[]}";

        List<String> objects = ReviewService.balancedJsonObjects(text);

        assertThat(objects).hasSize(1);
        assertThat(ReviewService.extractBestJsonObject(text, MAPPER)).containsKey("issues");
    }

    @Test
    void nestedObjectsCountAsOneTopLevelObject() {
        String text = "{\"a\":{\"b\":{\"c\":1}}} 中间说明 {\"d\":2}";

        assertThat(ReviewService.balancedJsonObjects(text))
                .containsExactly("{\"a\":{\"b\":{\"c\":1}}}", "{\"d\":2}");
    }

    @Test
    void ignoresStrayClosingBraces() {
        assertThat(ReviewService.balancedJsonObjects("}} 前面是多余的右括号 {\"a\":1}"))
                .containsExactly("{\"a\":1}");
    }
}
