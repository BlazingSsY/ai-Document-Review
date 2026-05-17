package com.aireview.util;

import com.aireview.entity.Rule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decide which rules apply to a given chunk based on each rule's optional metadata.
 *
 * Dispatch policy (matches Section 五.4 of 试验大纲智能审查处理流程.md):
 *   - global / output rules → applied to every chunk;
 *   - section_specific rules → applied only when the chunk's title or body matches
 *     one of the rule's target_sections (standard chapter numbers) or keywords;
 *   - document_specific rules → bundled into a final "全文汇总" pseudo-chunk after
 *     per-chapter review, so they can reason over chapter summaries together.
 *
 * Rules without metadata are treated as global so existing rule libraries keep working
 * unchanged.
 */
@Slf4j
public class RuleDispatcher {

    private RuleDispatcher() {
    }

    @Data
    public static class PreparedRule {
        private final Rule rule;
        private final RuleMetadata metadata;
        private final String body; // content with frontmatter stripped

        public boolean isGlobal() { return metadata == null || metadata.isGlobal(); }
        public boolean isSectionSpecific() { return metadata != null && metadata.isSectionSpecific(); }
        public boolean isDocumentSpecific() { return metadata != null && metadata.isDocumentSpecific(); }
    }

    /**
     * Per-chunk dispatch result: which rules were chosen, plus reasons used for debug output.
     */
    @Data
    public static class DispatchResult {
        private final List<PreparedRule> appliedRules;
        private final List<String> appliedRuleNames;
        /** Matched keywords / sections per rule, for diagnostics. */
        private final List<Map<String, Object>> matchTraces;
    }

    /**
     * One-time preparation: build a {@link RuleMetadata} for each rule and strip frontmatter
     * from its content. Persisted DB columns (set on upload + editable via the UI) take
     * precedence over content frontmatter, so user edits are honoured by the dispatcher.
     */
    public static List<PreparedRule> prepare(List<Rule> rules) {
        List<PreparedRule> prepared = new ArrayList<>();
        for (Rule rule : rules) {
            RuleMetadata fromContent = RuleMetadata.parse(rule.getContent(), rule.getFileType());
            RuleMetadata meta = mergeFromDb(rule, fromContent);
            String body = RuleMetadata.stripFrontmatter(rule.getContent(), rule.getFileType());
            String parsed = RuleParser.parseContent(body, rule.getFileType());
            prepared.add(new PreparedRule(rule, meta, parsed));
        }
        return prepared;
    }

    /**
     * Merge persisted DB metadata over the content-parsed metadata.
     * DB columns win wherever they are non-null / non-empty; this is how manual edits
     * via the rule edit modal take effect at dispatch time.
     */
    private static RuleMetadata mergeFromDb(Rule rule, RuleMetadata base) {
        RuleMetadata meta = base == null ? new RuleMetadata() : base;
        if (rule.getRuleCode() != null && !rule.getRuleCode().isBlank()) meta.setRuleCode(rule.getRuleCode());
        if (rule.getRuleType() != null && !rule.getRuleType().isBlank()) meta.setRuleType(rule.getRuleType());
        if (rule.getDocumentType() != null && !rule.getDocumentType().isBlank()) meta.setDocumentType(rule.getDocumentType());
        if (rule.getSections() != null && !rule.getSections().isEmpty()) meta.setSections(rule.getSections());
        if (rule.getKeywords() != null && !rule.getKeywords().isEmpty()) meta.setKeywords(rule.getKeywords());
        return meta;
    }

    /**
     * Choose the rules to apply to a particular chunk.
     *
     * Policy (matches user requirement task #4):
     *   - 通用 (global) rules: applied to EVERY chunk regardless of Scope. They
     *     run as part of every per-chapter prompt so the model still sees them
     *     bundled with any matching specific rules.
     *   - 专项 (section_specific) rules: applied ONLY when the chunk's H1 title
     *     matches one of the rule's Scope keywords or section numbers. Specific
     *     rules with no matching chapter anywhere in the document never hit any
     *     chunk and are therefore not uploaded to the LLM at all.
     *   - 文档级 (document_specific) rules: handled by a separate pass.
     *
     * @param chapterTitle e.g. "13 霉菌试验"
     * @param chapterBody  parsed Markdown / HTML body of the chunk (currently
     *                     unused by the matcher — title-only matching prevents
     *                     false positives from figure captions / numeric values)
     */
    public static DispatchResult dispatchForChunk(String chapterTitle,
                                                  String chapterBody,
                                                  List<PreparedRule> prepared) {
        List<PreparedRule> applied = new ArrayList<>();
        List<String> appliedNames = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();

        // Match against the chapter's first-level heading only.
        String titleHay = Objects.toString(chapterTitle, "").toLowerCase(Locale.ROOT);

        for (PreparedRule p : prepared) {
            RuleMetadata meta = p.getMetadata();
            String reason;
            Set<String> matchedKeywords = new LinkedHashSet<>();
            Set<String> matchedSections = new LinkedHashSet<>();

            if (meta != null && meta.isDocumentSpecific()) {
                // Document-level rules are NOT applied per chunk; they get a separate pass.
                continue;
            }

            if (meta != null && meta.isSectionSpecific()) {
                // Specific rules require an H1 match to be included.
                matchedKeywords.addAll(findMatches(titleHay, meta.getKeywords()));
                matchedSections.addAll(findSectionMatches(chapterTitle, meta.getSections()));
                if (matchedKeywords.isEmpty() && matchedSections.isEmpty()) {
                    continue; // skip — not applicable to this chapter
                }
                reason = "section_specific";
            } else {
                // global / output / unknown types → applied to every chunk so the
                // model always sees common quality criteria alongside any matching
                // specific rule. This also reduces token use vs. issuing a
                // separate global-only AI call per chunk.
                reason = (meta == null || meta.isGlobal()) ? "global" : "fallback_global";
            }

            applied.add(p);
            appliedNames.add(p.getRule().getRuleName());
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("ruleName", p.getRule().getRuleName());
            trace.put("ruleCode", meta != null ? meta.getRuleCode() : null);
            trace.put("ruleType", meta != null ? meta.getRuleType() : RuleMetadata.TYPE_GLOBAL);
            trace.put("reason", reason);
            if (!matchedKeywords.isEmpty()) trace.put("matchedKeywords", new ArrayList<>(matchedKeywords));
            if (!matchedSections.isEmpty()) trace.put("matchedSections", new ArrayList<>(matchedSections));
            traces.add(trace);
        }

        return new DispatchResult(applied, appliedNames, traces);
    }

    /** All document-level rules — typically run once on the aggregated summary. */
    public static List<PreparedRule> documentLevelRules(List<PreparedRule> prepared) {
        List<PreparedRule> out = new ArrayList<>();
        for (PreparedRule p : prepared) {
            if (p.isDocumentSpecific()) out.add(p);
        }
        return out;
    }

    private static List<String> findMatches(String haystackLower, List<String> needles) {
        List<String> hits = new ArrayList<>();
        if (needles == null) return hits;
        for (String n : needles) {
            if (n == null || n.isBlank()) continue;
            if (haystackLower.contains(n.toLowerCase(Locale.ROOT))) hits.add(n);
        }
        return hits;
    }

    /**
     * Match standard chapter numbers against the chapter title (first-level heading) only.
     * Body content is intentionally excluded to avoid false positives from figure captions,
     * decimal fragments, or numeric values that happen to look like a section number.
     *
     * Accepted surface forms inside the title:
     *   - 中文："第{n}章" / "第 {n} 章" / "第{n}节" / "第 {n} 节"
     *   - 英文："section/chapter/clause/part {n}"
     *   - 子章节编号："{n}.x"、"{n}.x.y"
     *   - 标题首 token：例如 "13 霉菌试验"
     */
    private static List<String> findSectionMatches(String chapterTitle, List<String> sections) {
        List<String> hits = new ArrayList<>();
        if (sections == null || sections.isEmpty()) return hits;
        String title = Objects.toString(chapterTitle, "").trim();
        if (title.isEmpty()) return hits;
        String titleLower = title.toLowerCase(Locale.ROOT);

        for (String section : sections) {
            if (section == null || section.isBlank()) continue;
            String s = section.trim();
            String sLower = s.toLowerCase(Locale.ROOT);
            String q = java.util.regex.Pattern.quote(sLower);

            boolean matched =
                    titleLower.contains("第" + sLower + "章")
                    || titleLower.contains("第 " + sLower + " 章")
                    || titleLower.contains("第" + sLower + "节")
                    || titleLower.contains("第 " + sLower + " 节")
                    || titleLower.matches("(?s).*\\b(section|chapter|clause|part)\\s+" + q + "\\b.*")
                    || titleLower.matches("(?s).*\\b" + q + "\\.[0-9].*")
                    || titleLower.matches("^" + q + "([\\s\\.\\-、].*|$)");
            if (matched) hits.add(s);
        }
        return hits;
    }
}
