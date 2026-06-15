package com.aireview.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleParserTest {

    @Test
    void structuredPromptRestrictsNotApplicableToExplicitPreconditions() {
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
                .contains("N/A 仅用于规则已经适配到当前章节")
                .contains("存在可核验豁免依据")
                .contains("基础文字质量检查始终适用，禁止判 N/A")
                .doesNotContain("本切片为目录页，所有规则不适用");
    }
}
