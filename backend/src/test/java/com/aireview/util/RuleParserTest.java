package com.aireview.util;

import com.aireview.review.ReviewResultSchema;
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
                .contains("R-Q 基础文字质量检查始终适用")
                .contains("仅在发现文字质量、图号/表号问题或需人工复核时输出")
                .contains("不含 R-Q 内置质量检查")
                .doesNotContain("本切片为目录页，所有规则不适用");
    }

    @Test
    void builtInQualityChecksDoNotCountTowardMandatoryCoverage() {
        RuleParser.RuleEntry quality = new RuleParser.RuleEntry(
                RuleDispatcher.BASIC_QUALITY_RULE_CODE,
                RuleDispatcher.BASIC_QUALITY_RULE_NAME,
                "基础文字质量检查",
                List.of(new RuleParser.CheckEntry(
                        RuleDispatcher.BASIC_QUALITY_RULE_CODE + "-C001",
                        "是否存在错别字",
                        "未发现错别字",
                        "其他",
                        true)));
        RuleParser.RuleEntry uploaded = new RuleParser.RuleEntry(
                "13-01",
                "上传规则",
                "检查试验目的");
        RuleParser.RuleEntry uploadedWithChecks = new RuleParser.RuleEntry(
                "13-02",
                "上传规则-原子项",
                "检查试验程序",
                List.of(
                        new RuleParser.CheckEntry("13-02-C001", "是否说明程序", "说明程序", "完整性", true),
                        new RuleParser.CheckEntry("13-02-C002", "是否说明判据", "说明判据", "完整性", true)));

        assertThat(RuleParser.expectedCheckCount(List.of(quality, uploaded, uploadedWithChecks)))
                .isEqualTo(3);

        String prompt = RuleParser.buildStructuredSystemPrompt(List.of(quality, uploaded, uploadedWithChecks));
        assertThat(prompt)
                .contains("本次共注入 3 个必须返回的业务待判定项")
                .contains("R-Q 质量检查只在 Fail/Review 时额外输出");
    }

    @Test
    void structuredPromptAndSchemaRequireTermObservations() {
        String prompt = RuleParser.buildStructuredSystemPrompt(List.of());

        assertThat(prompt)
                .contains("term_observations")
                .contains("术语抽取")
                .contains("每个切片最多提取 30 条");
        assertThat(ReviewResultSchema.schema().getJSONObject("properties"))
                .containsKey("term_observations");
        assertThat(ReviewResultSchema.schema().getJSONArray("required").toJavaList(String.class))
                .contains("term_observations");
    }
}
