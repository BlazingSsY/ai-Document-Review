package com.aireview.rule.engine;

import com.aireview.rule.entity.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDispatcherTest {

    @Test
    void basicChaptersOnlyUseBuiltInQualityProfile() {
        RuleDispatcher.PreparedRule globalRule = preparedRule(
                "G-10", RuleMetadata.TYPE_GLOBAL, List.of());

        RuleDispatcher.DispatchResult result = RuleDispatcher.dispatchForChunk(
                "2 试验目的", "本章只有一行文字。", List.of(globalRule), 6);

        assertThat(result.getAppliedRules()).isEmpty();
        assertThat(result.getAppliedRuleNames())
                .containsExactly(RuleDispatcher.BASIC_QUALITY_RULE_NAME);
        assertThat(result.getMatchTraces())
                .singleElement()
                .satisfies(trace -> assertThat(trace.get("reason"))
                        .isEqualTo("basic_chapter_profile"));
    }

    @Test
    void chaptersAfterBasicPrefixKeepNormalDispatch() {
        RuleDispatcher.PreparedRule globalRule = preparedRule(
                "G-10", RuleMetadata.TYPE_GLOBAL, List.of());

        RuleDispatcher.DispatchResult result = RuleDispatcher.dispatchForChunk(
                "7 试验设备描述", "设备描述正文", List.of(globalRule), 6);

        assertThat(result.getAppliedRuleNames()).containsExactly("G-10");
        assertThat(result.getAppliedRules()).containsExactly(globalRule);
    }

    @Test
    void sectionSpecificRulesStillMatchBusinessChapters() {
        RuleDispatcher.PreparedRule globalRule = preparedRule(
                "G-10", RuleMetadata.TYPE_GLOBAL, List.of());
        RuleDispatcher.PreparedRule fungusRule = preparedRule(
                "DO160G-13", RuleMetadata.TYPE_SECTION_SPECIFIC, List.of("霉菌"));

        RuleDispatcher.DispatchResult result = RuleDispatcher.dispatchForChunk(
                "34 霉菌试验", "霉菌试验正文", List.of(globalRule, fungusRule), 6);

        assertThat(result.getAppliedRuleNames()).containsExactly("G-10", "DO160G-13");
    }

    @Test
    void appendicesAreNotMistakenForBasicNumberedChapters() {
        assertThat(RuleDispatcher.isBasicReviewOnlyChapter("附录A", 6)).isFalse();
        assertThat(RuleDispatcher.isBasicReviewOnlyChapter("第6章 相关验证条款", 6)).isTrue();
        assertThat(RuleDispatcher.isBasicReviewOnlyChapter("7 试验设备描述", 6)).isFalse();
    }

    @Test
    void basicPrefixDoesNotHideExplicitTestSubjectChapters() {
        assertThat(RuleDispatcher.isBasicReviewOnlyChapter("1 磁效应试验", 6)).isFalse();
        assertThat(RuleDispatcher.isBasicReviewOnlyChapter("2 磁影响试验", 6)).isFalse();
        assertThat(RuleDispatcher.isBasicReviewOnlyChapter("3 霉菌试验", 6)).isFalse();
        assertThat(RuleDispatcher.isBasicReviewOnlyChapter("4 振动试验", 6)).isFalse();
        assertThat(RuleDispatcher.isBasicReviewOnlyChapter("5 电源输入试验", 6)).isFalse();
    }

    @Test
    void explicitTestSubjectInBasicPrefixStillReceivesMatchingRules() {
        RuleDispatcher.PreparedRule magneticRule = preparedRule(
                "DO160G-15", RuleMetadata.TYPE_SECTION_SPECIFIC, List.of("磁效应"));

        RuleDispatcher.DispatchResult result = RuleDispatcher.dispatchForChunk(
                "1 磁效应试验", "磁效应试验正文", List.of(magneticRule), 6);

        assertThat(result.getAppliedRuleNames()).containsExactly("DO160G-15");
        assertThat(result.getMatchTraces())
                .singleElement()
                .satisfies(trace -> assertThat(trace.get("reason"))
                        .isEqualTo("section_specific"));
    }

    private RuleDispatcher.PreparedRule preparedRule(
            String code,
            String type,
            List<String> keywords) {
        Rule rule = new Rule();
        rule.setId((long) Math.abs(code.hashCode()));
        rule.setRuleName(code);
        rule.setRuleCode(code);
        rule.setRuleType(type);

        RuleMetadata metadata = new RuleMetadata();
        metadata.setRuleCode(code);
        metadata.setRuleType(type);
        metadata.setKeywords(keywords);
        return new RuleDispatcher.PreparedRule(rule, metadata, code + " body");
    }
}
