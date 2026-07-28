package com.aireview.review.core;

import com.aireview.document.WordParser;
import com.aireview.rule.engine.RuleDispatcher;
import com.aireview.rule.engine.RuleParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Document-level evidence selection and result-coverage guarantees for the CHUNK pipeline.
 */
public final class DocumentRuleReviewSupport {

    public static final int DEFAULT_MAX_EVIDENCE_CHARS = 18_000;
    private static final int MAX_CANDIDATES_PER_RULE = 2;
    private static final int MIN_EXCERPT_CHARS = 600;
    private static final int MAX_EXCERPT_CHARS = 6_000;

    private DocumentRuleReviewSupport() {
    }

    public record EvidenceBundle(String content, List<Integer> chapterIndexes) {
    }

    /**
     * Always carries the complete outline, then adds bounded original-text evidence selected
     * by every document rule's keywords. This lets presence rules detect a missing chapter and
     * lets scope/order rules compare multiple chapters without resending the entire document.
     */
    public static EvidenceBundle buildEvidence(List<WordParser.Chapter> chapters,
                                               List<RuleDispatcher.PreparedRule> rules,
                                               int maxChars) {
        List<WordParser.Chapter> safeChapters = chapters == null ? List.of() : chapters;
        List<RuleDispatcher.PreparedRule> safeRules = rules == null ? List.of() : rules;
        int limit = Math.max(8_000, maxChars);

        StringBuilder prefix = new StringBuilder();
        prefix.append("【审查对象】完整试验大纲（已完成结构化解析）\n")
                .append("以下目录覆盖解析得到的全部文档区域；目录标题仅用于定位，候选区域原文才是内容证据。\n")
                .append("每条文档级规则必须独立返回一条 check_results；未找到要求内容时不得省略规则。\n\n")
                .append("【完整文档区域/章节目录】\n");
        for (int i = 0; i < safeChapters.size(); i++) {
            prefix.append(i + 1).append(". ").append(titleOf(safeChapters.get(i), i)).append("\n");
        }

        Map<String, List<Integer>> candidatesByRule = new LinkedHashMap<>();
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        for (RuleDispatcher.PreparedRule rule : safeRules) {
            List<Integer> candidates = topCandidates(safeChapters, rule, MAX_CANDIDATES_PER_RULE);
            candidatesByRule.put(ruleCode(rule), candidates);
            selected.addAll(candidates);
        }
        // Front matter, purpose, scope and references normally occur in the first regions.
        for (int i = 0; i < Math.min(4, safeChapters.size()); i++) selected.add(i);

        prefix.append("\n【各规则候选区域】\n");
        for (Map.Entry<String, List<Integer>> entry : candidatesByRule.entrySet()) {
            prefix.append("- [").append(entry.getKey()).append("] ");
            if (entry.getValue().isEmpty()) {
                prefix.append("未通过标题/关键词定位到候选区域；须结合完整目录判断是否缺失");
            } else {
                for (int j = 0; j < entry.getValue().size(); j++) {
                    if (j > 0) prefix.append("；");
                    int idx = entry.getValue().get(j);
                    prefix.append(titleOf(safeChapters.get(idx), idx));
                }
            }
            prefix.append("\n");
        }

        List<Integer> ordered = selected.stream().sorted().toList();
        int available = Math.max(0, limit - prefix.length() - 300);
        int perChapter = ordered.isEmpty() ? 0
                : Math.max(MIN_EXCERPT_CHARS,
                Math.min(MAX_EXCERPT_CHARS, available / ordered.size()));

        StringBuilder body = new StringBuilder(prefix).append("\n【候选区域原文】\n");
        List<Integer> included = new ArrayList<>();
        for (Integer idx : ordered) {
            if (idx == null || idx < 0 || idx >= safeChapters.size()) continue;
            int remaining = limit - body.length();
            if (remaining < 240) break;
            WordParser.Chapter chapter = safeChapters.get(idx);
            body.append("\n=== 区域 ").append(idx + 1).append("：")
                    .append(titleOf(chapter, idx)).append(" ===\n")
                    .append(excerpt(chapter.getFullText(), Math.min(perChapter, remaining - 120)))
                    .append("\n");
            included.add(idx);
        }

        // Spend residual budget on otherwise-unselected chapters in document order.
        Set<Integer> alreadyIncluded = new LinkedHashSet<>(included);
        for (int i = 0; i < safeChapters.size(); i++) {
            if (alreadyIncluded.contains(i)) continue;
            int remaining = limit - body.length();
            if (remaining < 500) break;
            WordParser.Chapter chapter = safeChapters.get(i);
            body.append("\n=== 补充区域 ").append(i + 1).append("：")
                    .append(titleOf(chapter, i)).append(" ===\n")
                    .append(excerpt(chapter.getFullText(), Math.min(1_200, remaining - 120)))
                    .append("\n");
            included.add(i);
        }

        body.append("\n请基于完整目录和上述原文逐条执行本批次全部文档级规则。")
                .append("目录中不存在目标且原文没有等效内容时，按规则要求判不通过；")
                .append("只有解析不完整、图像不可读或条件适用性无法确认时才判待复核。");
        return new EvidenceBundle(body.toString(), List.copyOf(included));
    }

    /**
     * Appends a Review row for every omitted rule/check. Matching rows are retained and a
     * single rule-level result with an imprecise check code is normalized to RULE-C001;
     * unrelated or duplicate rows are discarded so output cardinality stays deterministic.
     */
    public static Map<String, Object> ensureRuleCoverage(Map<String, Object> rawResult,
                                                         List<RuleParser.RuleEntry> expectedRules,
                                                         String location,
                                                         String fallbackReason) {
        Map<String, Object> result = rawResult == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(rawResult);
        result.putIfAbsent("summary", "");
        if (!(result.get("issues") instanceof List<?>)) result.put("issues", new ArrayList<>());
        if (!(result.get("passed_items") instanceof List<?>)) result.put("passed_items", new ArrayList<>());

        List<Map<String, Object>> checks = mutableChecks(result.get("check_results"));
        Set<Integer> consumed = new LinkedHashSet<>();
        List<Map<String, Object>> coveredChecks = new ArrayList<>();
        for (ExpectedCheck expected : expectedChecks(expectedRules)) {
            int match = findExact(checks, consumed, expected.checkCode());
            if (match < 0 && expected.singleCheckForRule()) {
                match = findByRule(checks, consumed, expected.ruleCode());
            }
            if (match >= 0) {
                Map<String, Object> row = checks.get(match);
                row.put("check_code", expected.checkCode());
                row.put("rule_code", expected.ruleCode());
                if (Objects.toString(row.get("check_question"), "").isBlank()) {
                    row.put("check_question", expected.question());
                }
                consumed.add(match);
                coveredChecks.add(row);
                continue;
            }

            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("check_code", expected.checkCode());
            missing.put("rule_code", expected.ruleCode());
            missing.put("check_question", expected.question());
            missing.put("status", "Review");
            missing.put("reason", fallbackReason == null || fallbackReason.isBlank()
                    ? "模型未返回该文档级规则的审查结果，系统已自动补为待复核。"
                    : fallbackReason);
            missing.put("evidence", "");
            missing.put("missing_items", new ArrayList<>());
            missing.put("suggestion", "请人工复核该文档级规则，或重新执行审查。");
            missing.put("confidence", "needs_review");
            missing.put("location", location == null ? "全文综合审查" : location);
            checks.add(missing);
            consumed.add(checks.size() - 1);
            coveredChecks.add(missing);
        }
        result.put("check_results", coveredChecks);
        return result;
    }

    private static List<Map<String, Object>> mutableChecks(Object rawChecks) {
        List<Map<String, Object>> checks = new ArrayList<>();
        if (!(rawChecks instanceof List<?> rows)) return checks;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) copy.put(entry.getKey().toString(), entry.getValue());
            }
            checks.add(copy);
        }
        return checks;
    }

    private static int findExact(List<Map<String, Object>> checks, Set<Integer> consumed, String checkCode) {
        for (int i = 0; i < checks.size(); i++) {
            if (!consumed.contains(i)
                    && checkCode.equals(Objects.toString(checks.get(i).get("check_code"), ""))) return i;
        }
        return -1;
    }

    private static int findByRule(List<Map<String, Object>> checks, Set<Integer> consumed, String ruleCode) {
        for (int i = 0; i < checks.size(); i++) {
            if (!consumed.contains(i)
                    && ruleCode.equals(Objects.toString(checks.get(i).get("rule_code"), ""))) return i;
        }
        return -1;
    }

    private static List<ExpectedCheck> expectedChecks(List<RuleParser.RuleEntry> rules) {
        List<ExpectedCheck> expected = new ArrayList<>();
        if (rules == null) return expected;
        for (RuleParser.RuleEntry rule : rules) {
            String ruleCode = Objects.toString(rule.code, "").trim();
            if (ruleCode.isEmpty()) continue;
            if (rule.checks == null || rule.checks.isEmpty()) {
                expected.add(new ExpectedCheck(ruleCode, ruleCode + "-C001",
                        Objects.toString(rule.name, ruleCode), true));
                continue;
            }
            boolean single = rule.checks.size() == 1;
            for (RuleParser.CheckEntry check : rule.checks) {
                String checkCode = Objects.toString(check.checkCode, "").trim();
                if (checkCode.isEmpty()) checkCode = ruleCode + "-C001";
                expected.add(new ExpectedCheck(ruleCode, checkCode,
                        Objects.toString(check.question, Objects.toString(rule.name, ruleCode)), single));
            }
        }
        return expected;
    }

    private static List<Integer> topCandidates(List<WordParser.Chapter> chapters,
                                               RuleDispatcher.PreparedRule rule,
                                               int maxCandidates) {
        List<ScoredChapter> scored = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            int score = scoreChapter(chapters.get(i), rule);
            if (score > 0) scored.add(new ScoredChapter(i, score));
        }
        scored.sort(Comparator.comparingInt(ScoredChapter::score).reversed()
                .thenComparingInt(ScoredChapter::index));
        return scored.stream().limit(Math.max(1, maxCandidates)).map(ScoredChapter::index).toList();
    }

    private static int scoreChapter(WordParser.Chapter chapter, RuleDispatcher.PreparedRule rule) {
        if (chapter == null || rule == null || rule.getMetadata() == null
                || rule.getMetadata().getKeywords() == null) return 0;
        String title = Objects.toString(chapter.getTitle(), "").toLowerCase(Locale.ROOT);
        String body = Objects.toString(chapter.getFullText(), "").toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : rule.getMetadata().getKeywords()) {
            if (keyword == null || keyword.isBlank()) continue;
            String needle = keyword.toLowerCase(Locale.ROOT).trim();
            if (title.contains(needle)) score += 100;
            if (body.contains(needle)) score += 10;
        }
        return score;
    }

    private static String excerpt(String text, int maxChars) {
        String value = Objects.toString(text, "").trim();
        if (maxChars <= 0 || value.isEmpty()) return "(无可读正文)";
        if (value.length() <= maxChars) return value;
        if (maxChars < 240) return value.substring(0, Math.min(value.length(), maxChars));
        int head = (int) (maxChars * 0.7);
        int tail = Math.max(1, maxChars - head - 24);
        return value.substring(0, head) + "\n...[中间内容省略]...\n"
                + value.substring(value.length() - tail);
    }

    private static String titleOf(WordParser.Chapter chapter, int index) {
        String title = chapter == null ? "" : Objects.toString(chapter.getTitle(), "").trim();
        return title.isEmpty() ? "前置区域/无标题区域 " + (index + 1) : title;
    }

    private static String ruleCode(RuleDispatcher.PreparedRule rule) {
        if (rule != null && rule.getMetadata() != null
                && rule.getMetadata().getRuleCode() != null
                && !rule.getMetadata().getRuleCode().isBlank()) {
            return rule.getMetadata().getRuleCode();
        }
        return rule != null && rule.getRule() != null
                ? Objects.toString(rule.getRule().getRuleName(), "未编号规则") : "未编号规则";
    }

    private record ScoredChapter(int index, int score) {
    }

    private record ExpectedCheck(String ruleCode, String checkCode, String question,
                                 boolean singleCheckForRule) {
    }
}