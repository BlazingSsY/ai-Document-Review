package com.aireview.review.core;

import com.aireview.document.WordParser;
import com.aireview.rule.engine.RuleDispatcher;
import com.aireview.rule.engine.RuleMetadata;
import com.aireview.rule.engine.RuleParser;
import com.aireview.rule.entity.Rule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRuleReviewSupportTest {

    @Test
    void evidenceKeepsCompleteOutlineAndSelectsRuleCandidates() {
        List<WordParser.Chapter> chapters = List.of(
                new WordParser.Chapter("", "封面信息"),
                new WordParser.Chapter("1 目的", "本大纲用于完成设备环境鉴定。"),
                new WordParser.Chapter("2 范围", "试验范围包括振动试验和湿热试验。"),
                new WordParser.Chapter("7 振动试验", "振动试验程序。"),
                new WordParser.Chapter("8 湿热试验", "湿热试验程序。"));

        DocumentRuleReviewSupport.EvidenceBundle evidence =
                DocumentRuleReviewSupport.buildEvidence(chapters, List.of(
                        prepared("G-01-purpose", "目的"),
                        prepared("G-02-scope", "范围")), 12_000);

        assertThat(evidence.content())
                .contains("完整文档区域/章节目录", "1 目的", "2 范围", "7 振动试验", "8 湿热试验")
                .contains("本大纲用于完成设备环境鉴定", "试验范围包括振动试验和湿热试验")
                .contains("[G-01-purpose]", "[G-02-scope]");
        assertThat(evidence.chapterIndexes()).contains(0, 1, 2);
    }

    @Test
    void coverageAppendsReviewForEveryMissingRule() {
        List<RuleParser.RuleEntry> expected = List.of(
                new RuleParser.RuleEntry("G-01-purpose", "目的", "规则正文"),
                new RuleParser.RuleEntry("G-02-scope", "范围", "规则正文"));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("summary", "只返回了一条");
        raw.put("issues", new ArrayList<>());
        raw.put("passed_items", new ArrayList<>());
        raw.put("check_results", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                "check_code", "arbitrary",
                "rule_code", "G-01-purpose",
                "check_question", "目的",
                "status", "Pass",
                "reason", "有目的描述",
                "evidence", "本大纲用于鉴定",
                "missing_items", List.of(),
                "suggestion", "",
                "confidence", "high")))));

        Map<String, Object> covered = DocumentRuleReviewSupport.ensureRuleCoverage(
                raw, expected, "全文综合审查", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) covered.get("check_results");
        assertThat(checks).hasSize(2);
        assertThat(checks).extracting(row -> row.get("check_code"))
                .containsExactly("G-01-purpose-C001", "G-02-scope-C001");
        assertThat(checks.get(0).get("status")).isEqualTo("Pass");
        assertThat(checks.get(1).get("status")).isEqualTo("Review");
        assertThat(checks.get(1).get("confidence")).isEqualTo("needs_review");
    }

    @Test
    void coverageCreatesRowsWhenDocumentModelCallFailsCompletely() {
        List<RuleParser.RuleEntry> expected = List.of(
                new RuleParser.RuleEntry("G-08-abnormalHandling", "异常处理", "规则正文"),
                new RuleParser.RuleEntry("G-09-testSuspensionRecovery", "中止恢复", "规则正文"),
                new RuleParser.RuleEntry("G-10-testReportContent", "报告内容", "规则正文"));

        Map<String, Object> covered = DocumentRuleReviewSupport.ensureRuleCoverage(
                null, expected, "全文综合审查", "模型调用失败，转为待复核。");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) covered.get("check_results");
        assertThat(checks).hasSize(3);
        assertThat(checks).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("Review");
            assertThat(row.get("reason")).isEqualTo("模型调用失败，转为待复核。");
        });
    }

    private static RuleDispatcher.PreparedRule prepared(String code, String keyword) {
        Rule rule = new Rule();
        rule.setId((long) Math.abs(code.hashCode()));
        rule.setRuleName(code);
        rule.setRuleCode(code);
        rule.setRuleType(RuleMetadata.TYPE_DOCUMENT_SPECIFIC);

        RuleMetadata metadata = new RuleMetadata();
        metadata.setRuleCode(code);
        metadata.setRuleType(RuleMetadata.TYPE_DOCUMENT_SPECIFIC);
        metadata.setKeywords(List.of(keyword));
        return new RuleDispatcher.PreparedRule(rule, metadata, code + " body");
    }
}