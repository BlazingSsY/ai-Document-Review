package com.aireview.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Structured metadata extracted from a rule's content.
 *
 * Supports two sources:
 *   1. YAML-style frontmatter at the top of a Markdown rule file:
 *
 *      ---
 *      scenario: 试验大纲智能审查
 *      rule_code: DO160G-13-001
 *      rule_type: section_specific
 *      applies_to:
 *        standard: DO-160G
 *        sections: ["13"]
 *        keywords: ["霉菌", "Fungus"]
 *      severity: high
 *      ---
 *
 *   2. Top-level fields on a JSON rule file (rule_code / rule_type / severity / ...).
 *
 * Metadata is optional. A rule without metadata is treated as a {@code global} rule
 * and will apply to every chunk, matching the existing behaviour.
 */
@Slf4j
@Data
public class RuleMetadata {

    public static final String TYPE_GLOBAL = "global";
    public static final String TYPE_SECTION_SPECIFIC = "section_specific";
    public static final String TYPE_DOCUMENT_SPECIFIC = "document_specific";
    public static final String TYPE_OUTPUT = "output";

    private String scenario;
    private String ruleCode;
    /** {@code global} (default), {@code section_specific}, {@code document_specific}, {@code output}. */
    private String ruleType = TYPE_GLOBAL;
    private String documentType;
    private String standard;
    /** Standard chapter numbers / document section numbers this rule targets (e.g. ["13", "15"]). */
    private List<String> sections = new ArrayList<>();
    /** Free-form keywords used to match against chunk title / body for dispatch. */
    private List<String> keywords = new ArrayList<>();
    /** {@code high} / {@code medium} / {@code low}. */
    private String severity;
    /** True when the rule_type explicitly disables this rule. */
    private boolean enabled = true;

    public boolean isGlobal() {
        return ruleType == null || TYPE_GLOBAL.equalsIgnoreCase(ruleType) || TYPE_OUTPUT.equalsIgnoreCase(ruleType);
    }

    public boolean isSectionSpecific() {
        return TYPE_SECTION_SPECIFIC.equalsIgnoreCase(ruleType);
    }

    public boolean isDocumentSpecific() {
        return TYPE_DOCUMENT_SPECIFIC.equalsIgnoreCase(ruleType);
    }

    /**
     * Parse metadata from raw rule content. Falls back gracefully if no metadata is found.
     *
     * @param content  raw rule file content
     * @param fileType "md" or "json"
     */
    public static RuleMetadata parse(String content, String fileType) {
        RuleMetadata meta = new RuleMetadata();
        if (content == null || content.isBlank()) {
            return meta;
        }
        try {
            if ("json".equalsIgnoreCase(fileType)) {
                fillFromJson(meta, content);
            } else {
                fillFromMarkdownFrontmatter(meta, content);
            }
        } catch (Exception e) {
            log.warn("Failed to parse rule metadata: {}", e.getMessage());
        }
        return meta;
    }

    /**
     * Return the rule body with the YAML frontmatter stripped, suitable for inclusion in a
     * system prompt. For JSON content, returns the original.
     */
    public static String stripFrontmatter(String content, String fileType) {
        if (content == null) return "";
        if (!"md".equalsIgnoreCase(fileType)) return content;
        String trimmed = content.replaceAll("^\\uFEFF", "").stripLeading();
        if (!trimmed.startsWith("---")) {
            return content;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) return content;
        int endIdx = trimmed.indexOf("\n---", firstNewline);
        if (endIdx < 0) return content;
        int after = trimmed.indexOf('\n', endIdx + 1);
        return after >= 0 ? trimmed.substring(after + 1) : "";
    }

    private static void fillFromMarkdownFrontmatter(RuleMetadata meta, String content) {
        String trimmed = content.replaceAll("^\\uFEFF", "").stripLeading();
        if (!trimmed.startsWith("---")) return;
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) return;
        int endIdx = trimmed.indexOf("\n---", firstNewline);
        if (endIdx < 0) return;
        String yaml = trimmed.substring(firstNewline + 1, endIdx);
        parseSimpleYaml(meta, yaml);
    }

    /**
     * Minimal YAML reader for the small dialect used by rule frontmatter.
     * Supports: top-level "key: value" lines, nested two-space indented blocks
     * (e.g. {@code applies_to:}), and inline JSON-style arrays {@code ["a", "b"]}.
     */
    private static void parseSimpleYaml(RuleMetadata meta, String yaml) {
        String[] lines = yaml.split("\\r?\\n");
        String currentBlock = null;
        for (String raw : lines) {
            String line = raw.replaceAll("\\s+$", "");
            if (line.isBlank() || line.trim().startsWith("#")) continue;

            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            String trimmed = line.trim();

            if (indent == 0) {
                int colon = trimmed.indexOf(':');
                if (colon < 0) continue;
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                if (value.isEmpty()) {
                    currentBlock = key.toLowerCase(Locale.ROOT);
                    continue;
                }
                currentBlock = null;
                applyTopLevel(meta, key, value);
            } else {
                if (currentBlock == null) continue;
                int colon = trimmed.indexOf(':');
                if (colon < 0) continue;
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                if ("applies_to".equals(currentBlock)) {
                    applyAppliesTo(meta, key, value);
                }
            }
        }
    }

    private static void applyTopLevel(RuleMetadata meta, String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);
        String v = unquote(value);
        switch (k) {
            case "scenario" -> meta.scenario = v;
            case "rule_code", "rulecode", "code" -> meta.ruleCode = v;
            case "rule_type", "ruletype", "type" -> meta.ruleType = v.toLowerCase(Locale.ROOT);
            case "document_type", "documenttype" -> meta.documentType = v;
            case "standard" -> meta.standard = v;
            case "severity" -> meta.severity = v.toLowerCase(Locale.ROOT);
            case "enabled" -> meta.enabled = !("false".equalsIgnoreCase(v) || "0".equals(v));
            case "sections", "target_sections" -> meta.sections = parseArray(v);
            case "keywords" -> meta.keywords = parseArray(v);
            default -> { /* ignore unknown keys */ }
        }
    }

    private static void applyAppliesTo(RuleMetadata meta, String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);
        String v = unquote(value);
        switch (k) {
            case "standard" -> {
                if (meta.standard == null || meta.standard.isBlank()) meta.standard = v;
            }
            case "document_type", "documenttype" -> {
                if (meta.documentType == null || meta.documentType.isBlank()) meta.documentType = v;
            }
            case "sections", "target_sections" -> meta.sections = mergeArray(meta.sections, parseArray(v));
            case "keywords" -> meta.keywords = mergeArray(meta.keywords, parseArray(v));
            default -> { /* ignore */ }
        }
    }

    private static List<String> parseArray(String raw) {
        if (raw == null || raw.isBlank()) return new ArrayList<>();
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            String inner = s.substring(1, s.length() - 1);
            if (inner.isBlank()) return new ArrayList<>();
            return Arrays.stream(inner.split(","))
                    .map(RuleMetadata::unquote)
                    .map(String::trim)
                    .filter(x -> !x.isEmpty())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        // Single scalar treated as a one-element list
        ArrayList<String> list = new ArrayList<>();
        list.add(unquote(s));
        return list;
    }

    private static List<String> mergeArray(List<String> existing, List<String> incoming) {
        List<String> merged = new ArrayList<>(existing == null ? List.of() : existing);
        for (String item : incoming) {
            if (item != null && !item.isBlank() && !merged.contains(item)) merged.add(item);
        }
        return merged;
    }

    private static String unquote(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                        || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static void fillFromJson(RuleMetadata meta, String content) {
        try {
            JSONObject obj = JSON.parseObject(content);
            if (obj == null) return;
            applyJsonField(meta, obj, "scenario");
            applyJsonField(meta, obj, "rule_code");
            applyJsonField(meta, obj, "rule_type");
            applyJsonField(meta, obj, "document_type");
            applyJsonField(meta, obj, "standard");
            applyJsonField(meta, obj, "severity");
            if (obj.containsKey("enabled")) {
                meta.enabled = obj.getBooleanValue("enabled");
            }
            JSONArray sections = obj.getJSONArray("sections");
            if (sections == null) sections = obj.getJSONArray("target_sections");
            if (sections != null) {
                List<String> list = new ArrayList<>();
                for (int i = 0; i < sections.size(); i++) list.add(String.valueOf(sections.get(i)));
                meta.sections = list;
            }
            JSONArray keywords = obj.getJSONArray("keywords");
            if (keywords != null) {
                List<String> list = new ArrayList<>();
                for (int i = 0; i < keywords.size(); i++) list.add(String.valueOf(keywords.get(i)));
                meta.keywords = list;
            }
            JSONObject appliesTo = obj.getJSONObject("applies_to");
            if (appliesTo != null) {
                if (meta.standard == null || meta.standard.isBlank()) {
                    meta.standard = appliesTo.getString("standard");
                }
                if (meta.documentType == null || meta.documentType.isBlank()) {
                    meta.documentType = appliesTo.getString("document_type");
                }
                JSONArray apSections = appliesTo.getJSONArray("sections");
                if (apSections == null) apSections = appliesTo.getJSONArray("target_sections");
                if (apSections != null) {
                    List<String> list = new ArrayList<>(meta.sections);
                    for (int i = 0; i < apSections.size(); i++) {
                        String item = String.valueOf(apSections.get(i));
                        if (!list.contains(item)) list.add(item);
                    }
                    meta.sections = list;
                }
                JSONArray apKeywords = appliesTo.getJSONArray("keywords");
                if (apKeywords != null) {
                    List<String> list = new ArrayList<>(meta.keywords);
                    for (int i = 0; i < apKeywords.size(); i++) {
                        String item = String.valueOf(apKeywords.get(i));
                        if (!list.contains(item)) list.add(item);
                    }
                    meta.keywords = list;
                }
            }
        } catch (Exception e) {
            log.debug("JSON rule has no parseable metadata: {}", e.getMessage());
        }
    }

    private static void applyJsonField(RuleMetadata meta, JSONObject obj, String key) {
        if (!obj.containsKey(key)) return;
        String v = obj.getString(key);
        if (v == null) return;
        switch (key) {
            case "scenario" -> meta.scenario = v;
            case "rule_code" -> meta.ruleCode = v;
            case "rule_type" -> meta.ruleType = v.toLowerCase(Locale.ROOT);
            case "document_type" -> meta.documentType = v;
            case "standard" -> meta.standard = v;
            case "severity" -> meta.severity = v.toLowerCase(Locale.ROOT);
            default -> { }
        }
    }
}
