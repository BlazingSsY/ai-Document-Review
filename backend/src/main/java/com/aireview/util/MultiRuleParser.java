package com.aireview.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Split a single uploaded rule file into one or more individual rules.
 *
 * Why this exists: real rule packs (see {@code prompts/prompts.json} for an example)
 * contain dozens of rules under one JSON file like
 *
 * <pre>
 * {
 *   "_metadata": {...},
 *   "section_prompts": {
 *     "G-1-test_unit":      { "system_prompt": [...], "user_prompt": [...] },
 *     "G-2-Project_omissions": { ... },
 *     ...
 *   }
 * }
 * </pre>
 *
 * Storing the whole file as a single {@code rules} row means the UI can't list / edit
 * individual rules. This parser walks recognised container shapes and yields a list of
 * {@link ParsedRule} entries, each of which gets its own database row.
 *
 * For unrecognised shapes (e.g. a plain Markdown rule, or a JSON with a single rule)
 * the parser still returns a one-element list so existing single-rule uploads work
 * exactly as before.
 */
@Slf4j
public class MultiRuleParser {

    private MultiRuleParser() { }

    /** Output of the parser. {@code metadata} is auto-detected; the user may override it later. */
    @Data
    public static class ParsedRule {
        private final String name;
        private final String fileType;   // "md" or "json"
        private final String content;    // exact body that will be persisted into rules.content
        private final RuleMetadata metadata;
        private final String description;
    }

    /**
     * Parse an uploaded file into individual rules.
     *
     * @param originalFilename  e.g. "prompts.json" (used to set sourceFile on each rule)
     * @param fileType          "md" or "json"
     * @param content           raw file content
     */
    public static List<ParsedRule> parse(String originalFilename, String fileType, String content) {
        if ("json".equalsIgnoreCase(fileType)) {
            return parseJson(originalFilename, content);
        }
        // Markdown rules: 1 file = 1 rule (keep existing behaviour).
        ParsedRule single = parseSingle(originalFilename, "md", content);
        List<ParsedRule> out = new ArrayList<>();
        out.add(single);
        return out;
    }

    private static List<ParsedRule> parseJson(String originalFilename, String content) {
        List<ParsedRule> out = new ArrayList<>();
        try {
            JSONObject root = JSON.parseObject(content);
            if (root == null) {
                out.add(parseSingle(originalFilename, "json", content));
                return out;
            }

            // Multi-rule container: dict of rules under "section_prompts" (試驗大綱 format),
            // or "rules" / "prompts" as alternates.
            JSONObject container = firstNonNull(root.getJSONObject("section_prompts"),
                    root.getJSONObject("rules"),
                    root.getJSONObject("prompts"));
            if (container != null && !container.isEmpty()) {
                for (Map.Entry<String, Object> entry : container.entrySet()) {
                    if (!(entry.getValue() instanceof JSONObject body)) continue;
                    ParsedRule pr = buildSubRule(originalFilename, entry.getKey(), body);
                    if (pr != null) out.add(pr);
                }
                if (!out.isEmpty()) return out;
            }

            // Top-level is itself a dict-of-dicts → treat each entry as a rule.
            boolean allObjects = !root.isEmpty();
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                if (entry.getKey().startsWith("_")) continue; // metadata key
                if (!(entry.getValue() instanceof JSONObject)) { allObjects = false; break; }
            }
            if (allObjects) {
                for (Map.Entry<String, Object> entry : root.entrySet()) {
                    if (entry.getKey().startsWith("_")) continue;
                    if (!(entry.getValue() instanceof JSONObject body)) continue;
                    ParsedRule pr = buildSubRule(originalFilename, entry.getKey(), body);
                    if (pr != null) out.add(pr);
                }
                if (!out.isEmpty()) return out;
            }
        } catch (Exception e) {
            log.warn("Multi-rule JSON parse failed, falling back to single-rule: {}", e.getMessage());
        }

        if (out.isEmpty()) out.add(parseSingle(originalFilename, "json", content));
        return out;
    }

    private static ParsedRule buildSubRule(String originalFilename, String key, JSONObject body) {
        // Render a readable Markdown body for the AI prompt. system/user prompt sections from
        // 試驗大綱 packs are joined by lines; arbitrary other shapes fall back to pretty JSON.
        StringBuilder md = new StringBuilder();
        md.append("# ").append(key).append("\n\n");
        boolean rendered = false;

        if (body.containsKey("system_prompt")) {
            md.append("## System Prompt\n\n");
            md.append(joinPromptLines(body.get("system_prompt"))).append("\n\n");
            rendered = true;
        }
        if (body.containsKey("user_prompt")) {
            md.append("## User Prompt\n\n");
            md.append(joinPromptLines(body.get("user_prompt"))).append("\n\n");
            rendered = true;
        }
        if (!rendered) {
            // Fall back to pretty-printed JSON so the rule body is still meaningful.
            md.append(JSON.toJSONString(body)).append("\n");
        }

        String content = md.toString().trim();
        if (content.isEmpty()) return null;

        // Authoritative metadata comes from the rule file's explicit `type` and
        // `Scope` fields (case-insensitive). When present these completely
        // override the heuristic detection — auto-detection runs only when the
        // file does not declare them, preserving compatibility with older packs.
        RuleMetadata meta = readDeclaredMetadata(key, body);
        if (meta == null) {
            meta = autoDetectMetadata(key, content, body);
        }
        String description = extractDescription(key, body);

        return new ParsedRule(key, "md", content, meta, description);
    }

    /**
     * Read explicit {@code type} and {@code scope} fields from a sub-rule body.
     * Returns {@code null} when neither field is present so the caller can fall
     * back to heuristic detection.
     *
     * <p>Recognised values:
     * <ul>
     *   <li>{@code type}: "通用" / "global" → {@link RuleMetadata#TYPE_GLOBAL};
     *       "专项" / "section" / "section_specific" → {@link RuleMetadata#TYPE_SECTION_SPECIFIC};
     *       "文档级" / "document" / "document_specific" → {@link RuleMetadata#TYPE_DOCUMENT_SPECIFIC};
     *       "输出" / "output" → {@link RuleMetadata#TYPE_OUTPUT}.</li>
     *   <li>{@code scope}: a single string (split on Chinese/English commas / 顿号 / spaces)
     *       or a JSON array. Each element becomes a keyword used to match against the document's
     *       first-level headings during dispatch.</li>
     * </ul>
     */
    private static RuleMetadata readDeclaredMetadata(String key, JSONObject body) {
        Object typeObj = firstPresent(body, "type", "Type", "TYPE", "rule_type", "ruleType");
        Object scopeObj = firstPresent(body, "scope", "Scope", "SCOPE", "applies_to_scope");
        if (typeObj == null && scopeObj == null) return null;

        RuleMetadata meta = new RuleMetadata();
        if (key != null) meta.setRuleCode(key);

        if (typeObj != null) {
            String normalized = normalizeRuleType(String.valueOf(typeObj));
            if (normalized != null) meta.setRuleType(normalized);
        }

        List<String> scopeList = parseScopeValue(scopeObj);
        if (!scopeList.isEmpty()) {
            meta.setKeywords(scopeList);
        }

        return meta;
    }

    private static Object firstPresent(JSONObject obj, String... keys) {
        for (String k : keys) {
            if (obj.containsKey(k)) {
                Object v = obj.get(k);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static String normalizeRuleType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.contains("通用") || s.equals("global") || s.equals("general")) {
            return RuleMetadata.TYPE_GLOBAL;
        }
        if (s.contains("专项") || s.contains("section") || s.contains("specific")) {
            return RuleMetadata.TYPE_SECTION_SPECIFIC;
        }
        if (s.contains("文档") || s.equals("document") || s.equals("document_specific")) {
            return RuleMetadata.TYPE_DOCUMENT_SPECIFIC;
        }
        if (s.contains("输出") || s.equals("output")) {
            return RuleMetadata.TYPE_OUTPUT;
        }
        return s;
    }

    private static List<String> parseScopeValue(Object value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        if (value instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                String s = arr.getString(i);
                if (s != null && !s.isBlank() && !out.contains(s.trim())) {
                    out.add(s.trim());
                }
            }
            return out;
        }
        // String form: split on commas, 中文逗号, 顿号, semicolons, slashes, or whitespace runs.
        for (String part : String.valueOf(value).split("[,，、;；/\\s]+")) {
            String t = part.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String joinPromptLines(Object value) {
        if (value instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                sb.append(arr.getString(i)).append("\n");
            }
            return sb.toString().trim();
        }
        return String.valueOf(value);
    }

    private static ParsedRule parseSingle(String originalFilename, String fileType, String content) {
        RuleMetadata meta = RuleMetadata.parse(content, fileType);
        if (meta.getRuleType() == null || meta.getRuleType().isBlank()) {
            // Fallback to content-based auto-detection
            RuleMetadata auto = autoDetectMetadata(originalFilename, content, null);
            if (meta.getRuleCode() == null) meta.setRuleCode(auto.getRuleCode());
            if (meta.getSections() == null || meta.getSections().isEmpty()) meta.setSections(auto.getSections());
            if (meta.getKeywords() == null || meta.getKeywords().isEmpty()) meta.setKeywords(auto.getKeywords());
            if (meta.getRuleType() == null || meta.getRuleType().isBlank()) meta.setRuleType(auto.getRuleType());
        }
        String name = stripExtension(originalFilename);
        return new ParsedRule(name, fileType, content, meta, null);
    }

    /**
     * Heuristic: scan {@code content} for DO-160G section references and turn them into
     * structured metadata. Triggers a {@code section_specific} classification if any
     * standard-chapter reference is found; otherwise leaves the rule as {@code global}.
     *
     * Patterns recognised (anchored to "DO-160", "DO－160" or bare "X.Y条" within ~120 chars
     * of a "DO-160" mention so we don't accidentally tag arbitrary numerics):
     *   - "DO-160G 第13章" / "DO－160G第13章" → section "13"
     *   - "Section 13 of DO-160" → section "13"
     *   - "4.5.1条", "8.5条" (within DO-160 context) → section "4" / "8"
     */
    static RuleMetadata autoDetectMetadata(String key, String content, JSONObject body) {
        RuleMetadata meta = new RuleMetadata();
        if (key != null) meta.setRuleCode(key);

        if (content == null) {
            meta.setRuleType(RuleMetadata.TYPE_GLOBAL);
            return meta;
        }

        // Find every "DO-160 第N章" or "X.Y条" near a "DO-160" mention.
        Set<String> sections = new LinkedHashSet<>();

        Matcher m1 = Pattern.compile("(?i)DO[\\s\\-－]?160[A-Z]?\\s*第\\s*(\\d+)\\s*章").matcher(content);
        while (m1.find()) sections.add(m1.group(1));

        Matcher m1b = Pattern.compile("(?i)DO[\\s\\-－]?160[A-Z]?\\s*第\\s*(\\d+)\\s*节").matcher(content);
        while (m1b.find()) sections.add(m1b.group(1));

        // English section references are only trusted when the content actually
        // mentions DO-160 — otherwise "section 4" could refer to anything.
        if (contentMentionsDo160(content)) {
            Matcher m2 = Pattern.compile("(?i)\\b(?:section|chapter|clause)\\s+(\\d+)\\s*(?:of)?\\s*(?:do[\\s\\-－]?160)?").matcher(content);
            while (m2.find()) sections.add(m2.group(1));

            // 子条目 "4.5.1条" / "8.5条"
            Matcher m3 = Pattern.compile("(\\d+)\\.\\d+(?:\\.\\d+)?\\s*条").matcher(content);
            while (m3.find()) sections.add(m3.group(1));
        }

        if (!sections.isEmpty()) {
            meta.setRuleType(RuleMetadata.TYPE_SECTION_SPECIFIC);
            meta.setSections(new ArrayList<>(sections));
        } else {
            meta.setRuleType(RuleMetadata.TYPE_GLOBAL);
        }

        // Suggest keywords from the body's recognisable Chinese topic terms. We look for
        // a small whitelist matched to DO-160G environmental categories so the dispatcher
        // can fall back to keyword hits when the document does not name the standard.
        List<String> keywords = autoKeywords(content);
        if (!keywords.isEmpty()) meta.setKeywords(keywords);

        return meta;
    }

    private static boolean contentMentionsDo160(String content) {
        return Pattern.compile("(?i)do[\\s\\-－]?160").matcher(content).find()
                || content.contains("RTCA");
    }

    /** Match common DO-160G category names so dispatcher keyword hits give the same result
     *  as a real章节 reference. The list is small on purpose — better miss than mis-trigger. */
    private static final String[][] KEYWORD_HINTS = {
            {"温度变化", "Temperature Variation"},
            {"温度", "Temperature"},
            {"湿热", "Humidity"},
            {"冲击", "Shock"},
            {"坠撞安全", "Crash Safety"},
            {"振动", "Vibration"},
            {"防水", "Waterproofness"},
            {"流体敏感性", "Fluid Susceptibility"},
            {"霉菌", "Fungus"},
            {"盐雾", "Salt Fog"},
            {"防火", "Fire"},
            {"可燃性", "Flammability"},
            {"加速度", "Acceleration"},
            {"高度", "Altitude"},
            {"减压", "Decompression"},
            {"过压", "Overpressure"},
    };

    private static List<String> autoKeywords(String content) {
        List<String> hits = new ArrayList<>();
        String lower = content.toLowerCase(Locale.ROOT);
        for (String[] pair : KEYWORD_HINTS) {
            for (String token : pair) {
                if (token == null || token.isBlank()) continue;
                if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                    if (!hits.contains(token)) hits.add(token);
                    break;
                }
            }
        }
        return hits;
    }

    private static String extractDescription(String key, JSONObject body) {
        // Pull the first meaningful sentence from the system_prompt to use as the rule's
        // human description on the listing page.
        Object sysObj = body.get("system_prompt");
        String text;
        if (sysObj instanceof JSONArray arr && !arr.isEmpty()) {
            text = arr.getString(0);
        } else if (sysObj instanceof String s) {
            text = s;
        } else {
            return null;
        }
        if (text == null) return null;
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 140) cleaned = cleaned.substring(0, 137) + "...";
        return cleaned;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) return "rule";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
