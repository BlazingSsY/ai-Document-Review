package com.aireview.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiRuleParserTest {

    @Test
    void parsesStepTemplateMarkdownWithBoldMetadata() {
        String content = """
                # DO160G-15章 磁效应试验QTP精细化审查规则

                ## 1. 磁效应试验-QTP基本信息

                - **规则编号**：15-01-qtp_basic_info_qtp
                - **规则类型**：section_specific
                - **检查项**：QTP基本信息
                - **关键词**：磁效应试验、DO160G-15、QTP

                ### 审查内容

                核查QTP基本信息。

                ### 审查步骤

                **1. 核查完整性**

                判定依据。

                ---

                ## 2. 磁效应试验-EUT设备名称、件号

                - **规则编号**：15-02-device_identification_eut
                - **规则类型**：section_specific
                - **检查项**：EUT设备名称、件号
                - **关键词**：磁效应试验、EUT设备名称

                ### 审查内容

                核查EUT设备名称、件号。

                ### 审查步骤

                **1. 核查完整性**

                判定依据。
                """;

        List<MultiRuleParser.ParsedRule> rules = MultiRuleParser.parse(
                "DO160G-15章 磁效应试验章节规则.md", "md", content);

        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).getName()).isEqualTo("磁效应试验-QTP基本信息");
        assertThat(rules.get(0).getMetadata().getRuleCode()).isEqualTo("15-01-qtp_basic_info_qtp");
        assertThat(rules.get(0).getMetadata().getRuleType()).isEqualTo(RuleMetadata.TYPE_SECTION_SPECIFIC);
        assertThat(rules.get(0).getMetadata().getKeywords()).contains("磁效应试验", "DO160G-15", "QTP");
        assertThat(rules.get(1).getMetadata().getRuleCode()).isEqualTo("15-02-device_identification_eut");
    }
}
