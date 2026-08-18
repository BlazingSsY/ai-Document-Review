package com.aireview.rule.engine;

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
                .contains("## 一、公共审查约束")
                .contains("## 三、本章规则清单")
                .contains("## 四、本章规则明细")
                .contains("## 五、输出Schema（必须严格遵守）")
                .contains("## 六、编号自检")
                .contains("Pass")
                .contains("Fail")
                .contains("Review")
                .contains("不得输出 `Partial`、`N/A`")
                .contains("evidence")
                // 从常量派生：内置质量规则编号改过一次（R-BASIC-QUALITY → R-Q），
                // 写死字面量会在下一次改名时静默失配。
                .contains(RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C001")
                .contains("check_results恰好1条")
                .contains("只能输出一个合法JSON对象")
                .doesNotContain("【判定锚点 / Few-shot】")
                .doesNotContain("示例 1（正例，识别为问题）");
    }

    /** Rules without direct evidence still need an explicit, auditable Review path. */
    @Test
    void structuredPromptGivesALegalPathForRulesWithNoMatchingContent() {
        String prompt = RuleParser.buildStructuredSystemPrompt(List.of(
                new RuleParser.RuleEntry("QTP-GEN-01", "试验人员与承试单位", "规则正文")));

        assertThat(prompt)
                .as("规则未直接命中时必须保留可审计的 Review 路径")
                .contains("若输入标题或正文明显不属于当前规则适用章节，受影响规则判Review")
                .contains("缺失或不符合时，`reason`写明实际检索范围")
                .contains("即使某项不存在或无法核验，也必须输出对应check_results");
    }

    @Test
    void structuredPromptUsesOnlyInjectedRuleAndCheckCodes() {
        String prompt = RuleParser.buildStructuredSystemPrompt(List.of(
                new RuleParser.RuleEntry("QTP-GEN-01", "试验人员与承试单位", "规则正文")));

        assertThat(prompt)
                .contains("QTP-GEN-01")
                .contains("QTP-GEN-01-C001")
                .contains("本次注入的rule_code清单：QTP-GEN-01")
                .contains("本次必须输出的check_code清单：QTP-GEN-01-C001")
                .doesNotContain("R-001")
                .doesNotContain("R-003-C001");
    }

    @Test
    void structuredPromptKeepsMultipleChecksInOneRuleDetail() {
        RuleParser.RuleEntry rule = new RuleParser.RuleEntry(
                "QTP-GEN-03",
                "通用要求",
                "规则说明\n审查内容\n审查步骤",
                List.of(
                        new RuleParser.CheckEntry("QTP-GEN-03-C001", "环境条件是否完整", "温度、湿度、压力均有要求", "完整性", true),
                        new RuleParser.CheckEntry("QTP-GEN-03-C002", "允差是否明确", "给出适用参数的允许误差", "标准符合性", true)));

        String prompt = RuleParser.buildStructuredSystemPrompt(List.of(rule));

        assertThat(prompt)
                .contains("| QTP-GEN-03 | 通用要求 | QTP-GEN-03-C001、QTP-GEN-03-C002 |")
                .contains("QTP-GEN-03-C001")
                .contains("QTP-GEN-03-C002")
                .contains("check_results恰好2条");
    }
}
