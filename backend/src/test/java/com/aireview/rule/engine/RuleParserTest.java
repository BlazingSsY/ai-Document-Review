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
                .contains("status 只能是 Pass、Fail、Review 三选一")
                .contains("不得使用 N/A 或 Partial")
                .contains("基础文字质量检查始终适用")
                .contains("evidence 必须是可在输入原文中逐字搜索到的最小片段")
                .contains("reason 必须用中文引号“”引用同一片段")
                .contains("禁止输出任何解释、前后缀、markdown 围栏")
                .contains("原文“在常温下进行”未给出明确温度上下限")
                .contains("\"evidence\":\"在常温下进行\"")
                .doesNotContain("\"evidence\":\"原文仅写")
                .doesNotContain("本切片为目录页，所有规则不适用");
    }

    /**
     * 「与本章无关的规则判什么」曾经三处指令互相堵死：禁止因无关判 Review、没证据判不了 Pass、
     * 又必须为每条规则输出一行。模型只能硬选，多半选 Review，直接制造待复核噪声。
     * 现在给出四档顺序，其中第 4 档是合法出口，但要求先真检索、并在 reason 写明范围。
     */
    @Test
    void structuredPromptGivesALegalPathForRulesWithNoMatchingContent() {
        String prompt = RuleParser.buildStructuredSystemPrompt(List.of(
                new RuleParser.RuleEntry("QTP-GEN-01", "试验人员与承试单位", "规则正文")));

        assertThat(prompt)
                .as("四档判定顺序必须完整给出，否则无关规则无档可落")
                .contains("有逐字证据且满足通过标准 → Pass")
                .contains("该写而未写 → Fail")
                .contains("原文明示前置条件不成立")
                .contains("确实未出现 → Review")
                .contains("reason 必须写明检索范围");

        assertThat(prompt)
                .as("第 4 档不能退化成免判通道")
                .contains("不得仅凭「本章标题与规则名不像」就判 Review");
    }

    /**
     * Few-shot 里的 R-001 只是格式占位。若模型照抄，ensureRuleCoverage 的编号匹配会全部落空——
     * 模型真答的那条被丢弃，同时补一条「模型未返回→待复核」，一次错配污染两条结果。
     */
    @Test
    void structuredPromptMarksExampleRuleCodesAsPlaceholders() {
        String prompt = RuleParser.buildStructuredSystemPrompt(List.of(
                new RuleParser.RuleEntry("QTP-GEN-01", "试验人员与承试单位", "规则正文")));

        assertThat(prompt)
                .contains("是占位符")
                .contains("不要套用下方示例里的编号样式")
                .contains("QTP-GEN-01-C001")
                .as("旧文案把占位编号写成了格式规范，会诱导模型照抄")
                .doesNotContain("中给出的 [R-XXX] 编号");
    }
}
