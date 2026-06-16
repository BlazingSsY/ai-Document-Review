package com.aireview.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleParserTest {

    @Test
    void structuredPromptRestrictsStatusesToThreeLevels() {
        RuleParser.RuleEntry rule = new RuleParser.RuleEntry(
                RuleDispatcher.BASIC_QUALITY_RULE_CODE,
                RuleDispatcher.BASIC_QUALITY_RULE_NAME,
                "基础文字质量检查",
                List.of(new RuleParser.CheckEntry(
                        RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C001",
                        "是否存在错别字",
                        "未发现错别字",
                        "其他",
                        true)));

        String prompt = RuleParser.buildStructuredSystemPrompt(List.of(rule));

        assertThat(prompt)
                .contains("status 只能是 Pass、Fail、Review 三选一")
                .contains("不得使用 N/A 或 Partial")
                .contains("一律判 Review 交人工复核")
                .contains("基础文字质量检查始终适用")
                .doesNotContain("本切片为目录页，所有规则不适用");
    }
}
