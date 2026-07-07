package com.aireview.rule.engine;

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
        private final List<ParsedCheck> checks;

        public ParsedRule(String name, String fileType, String content,
                          RuleMetadata metadata, String description) {
            this(name, fileType, content, metadata, description, List.of());
        }

        public ParsedRule(String name, String fileType, String content,
                          RuleMetadata metadata, String description,
                          List<ParsedCheck> checks) {
            this.name = name;
            this.fileType = fileType;
            this.content = content;
            this.metadata = metadata;
            this.description = description;
            this.checks = checks == null ? List.of() : checks;
        }
    }

    /** Atomic check rows persisted into rule_checks for the evidence-based pipeline. */
    @Data
    public static class ParsedCheck {
        private final String checkCode;
        private final String checkType;
        private final String question;
        private final String passCriteria;
        private final String category;
        private final Boolean evidenceRequired;
        private final Integer displayOrder;
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
        // 新版"审查内容/审查步骤/字段定义"模板：1 个 md 文件含多条规则，按 ## 标题逐条拆分，
        // 且不生成原子检查项——规则正文原封不动整条上传给大模型，由模型对整条规则给单一结论。
        if (isStepTemplateMarkdown(content)) {
            List<ParsedRule> multi = parseStepTemplateMarkdown(originalFilename, content);
            if (!multi.isEmpty()) {
                return multi;
            }
        }
        // 其余 Markdown 规则：1 file = 1 rule（保持原行为）。
        ParsedRule single = parseSingle(originalFilename, "md", content);
        List<ParsedRule> out = new ArrayList<>();
        out.add(single);
        return out;
    }

    private static final Pattern STEP_TEMPLATE_H2 = Pattern.compile("^##\\s+(.+?)\\s*$");
    private static final Pattern STEP_TEMPLATE_META =
            Pattern.compile("^\\s*-\\s*([^：:]+)[：:]\\s*(.+?)\\s*$");

    /**
     * 识别"新版规则模板"：每条规则用二级标题分隔，块内含 {@code - 规则类型：} 元数据行，
     * 并带 {@code ### 审查步骤} 小节。两个结构特征同时命中才触发，避免误伤旧版单规则 md
     * （如基础文字质量审查文档，其正文也可能出现"规则编号："字样但不是本模板）。
     */
    static boolean isStepTemplateMarkdown(String content) {
        if (content == null || content.isBlank()) return false;
        return content.contains("### 审查步骤")
                && Pattern.compile("(?m)^\\s*-\\s*规则类型[：:]").matcher(content).find();
    }

    /**
     * 把"新版规则模板"markdown 按二级标题拆成多条规则。每个块：
     * <ul>
     *   <li>标题去掉前导序号（如 "1. "）作为规则名称；</li>
     *   <li>从 {@code - 规则编号/规则类型/文档类型/规则说明/关键词} 行提取元数据；</li>
     *   <li>规则正文为该块原文（去掉标题行与 {@code ---} 分隔线），原封不动用于审查 prompt；</li>
     *   <li>不生成原子检查项（checks 为空）→ 审查时整条规则上传、由模型给单一结论。</li>
     * </ul>
     * 只有声明了"规则编号"的块才会被当作规则。
     */
    private static List<ParsedRule> parseStepTemplateMarkdown(String originalFilename, String content) {
        List<ParsedRule> out = new ArrayList<>();
        String[] lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);

        List<Integer> heads = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (STEP_TEMPLATE_H2.matcher(lines[i]).matches()) heads.add(i);
        }

        for (int h = 0; h < heads.size(); h++) {
            int start = heads.get(h);
            int end = (h + 1 < heads.size()) ? heads.get(h + 1) : lines.length;

            Matcher hm = STEP_TEMPLATE_H2.matcher(lines[start]);
            if (!hm.matches()) continue;
            String heading = hm.group(1).trim();
            String name = heading.replaceFirst("^\\d+\\s*[.、)）]\\s*", "").trim();
            if (name.isEmpty()) name = heading;

            StringBuilder bodyBuf = new StringBuilder();
            String ruleCode = null, ruleType = null, docType = null, description = null;
            List<String> keywords = List.of();
            for (int i = start + 1; i < end; i++) {
                String line = lines[i];
                if (line.trim().equals("---")) continue;
                bodyBuf.append(line).append("\n");
                Matcher mm = STEP_TEMPLATE_META.matcher(line);
                if (mm.matches()) {
                    String key = mm.group(1).trim();
                    String val = mm.group(2).trim();
                    switch (key) {
                        case "规则编号" -> ruleCode = val;
                        case "规则类型" -> ruleType = val;
                        case "文档类型" -> docType = val;
                        case "规则说明" -> description = val;
                        case "关键词" -> keywords = parseStringArray(val);
                        default -> { }
                    }
                }
            }

            // 没有规则编号的块不视为规则（前导说明、空块等）。
            if (ruleCode == null || ruleCode.isBlank()) continue;
            String body = bodyBuf.toString().trim();
            if (body.isEmpty()) continue;

            RuleMetadata meta = new RuleMetadata();
            meta.setRuleCode(ruleCode);
            String normType = normalizeRuleType(ruleType);
            meta.setRuleType(normType == null ? RuleMetadata.TYPE_GLOBAL : normType);
            if (docType != null && !docType.isBlank()) meta.setDocumentType(docType);
            if (!keywords.isEmpty()) meta.setKeywords(keywords);

            // checks 为空：整条规则原文上传，不做原子化拆分。
            out.add(new ParsedRule(name, "md", body, meta, description, List.of()));
        }
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

            List<ParsedRule> canonical = parseCanonicalRulePack(originalFilename, root);
            if (!canonical.isEmpty()) {
                return canonical;
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

    private static List<ParsedRule> parseCanonicalRulePack(String originalFilename, JSONObject root) {
        List<ParsedRule> out = new ArrayList<>();

        Object rulesObj = root.get("rules");
        if (rulesObj instanceof JSONArray rules) {
            for (int i = 0; i < rules.size(); i++) {
                Object item = rules.get(i);
                if (item instanceof JSONObject obj) {
                    ParsedRule pr = buildCanonicalRule(originalFilename, obj, i + 1);
                    if (pr != null) out.add(pr);
                }
            }
            return out;
        }

        if (root.get("checks") instanceof JSONArray) {
            ParsedRule pr = buildCanonicalRule(originalFilename, root, 1);
            if (pr != null) out.add(pr);
        }
        return out;
    }

    private static ParsedRule buildCanonicalRule(String originalFilename, JSONObject obj, int order) {
        String code = firstString(obj, "rule_code", "ruleCode", "code");
        String name = firstNonBlank(
                firstString(obj, "name", "rule_name", "ruleName", "title"),
                code,
                stripExtension(originalFilename) + "-" + order);
        String description = firstString(obj, "description", "desc", "confirm_target", "target");

        RuleMetadata meta = new RuleMetadata();
        meta.setRuleCode(code);
        String type = normalizeRuleType(firstString(obj, "rule_type", "ruleType", "type"));
        meta.setRuleType(type == null ? RuleMetadata.TYPE_GLOBAL : type);
        meta.setDocumentType(firstString(obj, "document_type", "documentType"));
        meta.setSections(parseStringArray(firstPresent(obj, "sections", "target_sections")));
        meta.setKeywords(parseStringArray(firstPresent(obj, "keywords", "scope", "Scope")));

        JSONObject appliesTo = obj.getJSONObject("applies_to");
        if (appliesTo != null) {
            if (meta.getDocumentType() == null || meta.getDocumentType().isBlank()) {
                meta.setDocumentType(firstString(appliesTo, "document_type", "documentType"));
            }
            meta.setSections(mergeStrings(meta.getSections(),
                    parseStringArray(firstPresent(appliesTo, "sections", "target_sections"))));
            meta.setKeywords(mergeStrings(meta.getKeywords(),
                    parseStringArray(firstPresent(appliesTo, "keywords", "scope", "Scope"))));
        }

        List<ParsedCheck> checks = parseCanonicalChecks(obj, code, name, description);
        String body = renderCanonicalRuleBody(name, code, description, checks, obj);
        return new ParsedRule(name, "md", body, meta, description, checks);
    }

    private static List<ParsedCheck> parseCanonicalChecks(JSONObject obj, String ruleCode,
                                                          String ruleName, String ruleDescription) {
        List<ParsedCheck> checks = new ArrayList<>();
        JSONArray arr = obj.getJSONArray("checks");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                Object item = arr.get(i);
                if (item instanceof JSONObject checkObj) {
                    checks.add(toParsedCheck(checkObj, ruleCode, i + 1));
                }
            }
        }
        if (!checks.isEmpty()) return checks;

        String fallbackCode = ruleCode == null || ruleCode.isBlank()
                ? "CHECK-001"
                : ruleCode + "._default";
        checks.add(new ParsedCheck(
                fallbackCode,
                defaultString(firstString(obj, "check_type", "checkType"), "presence"),
                firstNonBlank(firstString(obj, "question", "check_item", "checkItem"), ruleName),
                firstNonBlank(firstString(obj, "pass_criteria", "passCriteria", "criteria", "requirement"),
                        ruleDescription, "文档中存在能够满足该检查项的明确证据"),
                firstString(obj, "category"),
                firstBoolean(obj, "evidence_required", "evidenceRequired", true),
                1));
        return checks;
    }

    private static ParsedCheck toParsedCheck(JSONObject obj, String ruleCode, int order) {
        String checkCode = firstString(obj, "check_code", "checkCode", "code");
        if (checkCode == null || checkCode.isBlank()) {
            String prefix = ruleCode == null || ruleCode.isBlank() ? "CHECK" : ruleCode;
            checkCode = prefix + "-" + String.format("%03d", order);
        }
        String question = firstNonBlank(
                firstString(obj, "question", "check_item", "checkItem", "name", "title"),
                "检查项 " + order);
        String passCriteria = firstNonBlank(
                firstString(obj, "pass_criteria", "passCriteria", "criteria", "requirement", "confirm_target", "target"),
                question);
        return new ParsedCheck(
                checkCode,
                defaultString(firstString(obj, "check_type", "checkType", "type"), "presence"),
                question,
                passCriteria,
                firstString(obj, "category"),
                firstBoolean(obj, "evidence_required", "evidenceRequired", true),
                firstInteger(obj, "display_order", "displayOrder", order));
    }

    private static String renderCanonicalRuleBody(String name, String code, String description,
                                                  List<ParsedCheck> checks, JSONObject original) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(name).append("\n\n");
        if (code != null && !code.isBlank()) {
            md.append("- 规则编号：").append(code).append("\n");
        }
        if (description != null && !description.isBlank()) {
            md.append("- 规则说明：").append(description).append("\n");
        }
        md.append("\n## 原子检查项\n\n");
        for (ParsedCheck c : checks) {
            md.append("### ").append(c.getCheckCode()).append("\n\n");
            md.append("- 检查问题：").append(c.getQuestion()).append("\n");
            md.append("- 通过准则：").append(c.getPassCriteria()).append("\n");
            md.append("- 检查类型：").append(c.getCheckType()).append("\n");
            if (c.getCategory() != null && !c.getCategory().isBlank()) {
                md.append("- 分类：").append(c.getCategory()).append("\n");
            }
            md.append("- 需要原文证据：")
                    .append(Boolean.FALSE.equals(c.getEvidenceRequired()) ? "否" : "是")
                    .append("\n\n");
        }
        if (checks.isEmpty()) {
            md.append(JSON.toJSONString(original)).append("\n");
        }
        return md.toString().trim();
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
        if (s.contains("试验项目") || s.equals("test_item_chapter") || s.equals("test_item") || s.equals("testitem")) {
            return RuleMetadata.TYPE_TEST_ITEM;
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

    private static String firstString(JSONObject obj, String... keys) {
        Object value = firstPresent(obj, keys);
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Boolean firstBoolean(JSONObject obj, String key1, String key2, boolean fallback) {
        Object value = firstPresent(obj, key1, key2);
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        String s = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s)) return false;
        return fallback;
    }

    private static Integer firstInteger(JSONObject obj, String key1, String key2, int fallback) {
        Object value = firstPresent(obj, key1, key2);
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<String> parseStringArray(Object value) {
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
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) continue;
                String s = String.valueOf(item).trim();
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
            return out;
        }
        for (String part : String.valueOf(value).split("[,，、;；\\s]+")) {
            String s = part.trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private static List<String> mergeStrings(List<String> left, List<String> right) {
        List<String> out = new ArrayList<>(left == null ? List.of() : left);
        if (right != null) {
            for (String item : right) {
                if (item != null && !item.isBlank() && !out.contains(item)) out.add(item);
            }
        }
        return out;
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
