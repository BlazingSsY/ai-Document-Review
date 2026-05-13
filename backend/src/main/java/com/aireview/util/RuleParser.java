package com.aireview.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses rule files in .md or .json format and converts them into structured system prompts
 * suitable for AI model consumption.
 */
@Slf4j
public class RuleParser {

    private RuleParser() {
    }

    /**
     * Parse a rule file and return its content as a structured string.
     *
     * @param filePath path to the rule file (.md or .json)
     * @return parsed rule content
     * @throws IOException if file cannot be read
     */
    public static String parseFile(String filePath) throws IOException {
        Path path = Path.of(filePath);
        String fileName = path.getFileName().toString().toLowerCase();
        String content = Files.readString(path, StandardCharsets.UTF_8);

        if (fileName.endsWith(".json")) {
            return parseJsonRule(content);
        } else if (fileName.endsWith(".md")) {
            return parseMarkdownRule(content);
        } else {
            throw new IllegalArgumentException("Unsupported rule file format: " + fileName
                    + ". Only .md and .json are supported.");
        }
    }

    /**
     * Parse raw rule content based on its type.
     *
     * @param content  rule content string
     * @param fileType "md" or "json"
     * @return parsed rule content formatted as a system prompt
     */
    public static String parseContent(String content, String fileType) {
        if ("json".equalsIgnoreCase(fileType)) {
            return parseJsonRule(content);
        } else if ("md".equalsIgnoreCase(fileType)) {
            return parseMarkdownRule(content);
        }
        return content;
    }

    /**
     * Parse JSON rule content.
     * Expected format:
     * {
     *   "name": "Rule name",
     *   "description": "Rule description",
     *   "criteria": ["criterion 1", "criterion 2"],
     *   "scoring": { "pass": "description", "fail": "description" }
     * }
     */
    private static String parseJsonRule(String jsonContent) {
        try {
            JSONObject rule = JSON.parseObject(jsonContent);
            StringBuilder prompt = new StringBuilder();

            // Try well-known English field names
            String name = rule.getString("name");
            if (name != null) prompt.append("## Rule: ").append(name).append("\n\n");

            String description = rule.getString("description");
            if (description != null) prompt.append("### Description\n").append(description).append("\n\n");

            JSONArray criteria = rule.getJSONArray("criteria");
            if (criteria != null && !criteria.isEmpty()) {
                prompt.append("### Review Criteria\n");
                for (int i = 0; i < criteria.size(); i++) {
                    prompt.append(i + 1).append(". ").append(criteria.getString(i)).append("\n");
                }
                prompt.append("\n");
            }

            JSONObject scoring = rule.getJSONObject("scoring");
            if (scoring != null) {
                prompt.append("### Scoring Guidelines\n");
                for (String key : scoring.keySet()) {
                    prompt.append("- **").append(key).append("**: ").append(scoring.getString(key)).append("\n");
                }
                prompt.append("\n");
            }

            JSONArray checkpoints = rule.getJSONArray("checkpoints");
            if (checkpoints != null && !checkpoints.isEmpty()) {
                prompt.append("### Checkpoints\n");
                for (int i = 0; i < checkpoints.size(); i++) {
                    prompt.append("- ").append(checkpoints.getString(i)).append("\n");
                }
                prompt.append("\n");
            }

            // If none of the expected fields were found, fall back to rendering all fields
            // (handles JSON files with Chinese or custom field names)
            if (prompt.length() == 0) {
                for (String key : rule.keySet()) {
                    Object value = rule.get(key);
                    prompt.append("**").append(key).append("**：");
                    if (value instanceof JSONArray arr) {
                        prompt.append("\n");
                        for (int i = 0; i < arr.size(); i++) {
                            prompt.append("  - ").append(arr.getString(i)).append("\n");
                        }
                    } else if (value instanceof JSONObject obj) {
                        prompt.append("\n");
                        for (String k : obj.keySet()) {
                            prompt.append("  - ").append(k).append(": ").append(obj.getString(k)).append("\n");
                        }
                    } else {
                        prompt.append(value).append("\n");
                    }
                    prompt.append("\n");
                }
            }

            String result = prompt.toString().trim();
            return result.isEmpty() ? jsonContent : result;
        } catch (Exception e) {
            log.warn("Failed to parse JSON rule, using raw content: {}", e.getMessage());
            return jsonContent;
        }
    }

    /**
     * Parse Markdown rule content. Markdown rules are used as-is since they are already
     * in a structured, human-readable format suitable for AI models.
     */
    private static String parseMarkdownRule(String mdContent) {
        // Markdown is already well-structured for AI consumption.
        // Clean up and normalize whitespace.
        return mdContent.trim().replaceAll("\\r\\n", "\n").replaceAll("\\n{3,}", "\n\n");
    }

    /**
     * Combine multiple rules into a single system prompt for AI review.
     *
     * @param ruleContents list of parsed rule content strings
     * @return combined system prompt
     */
    public static String buildSystemPrompt(List<String> ruleContents) {
        return buildSystemPrompt(ruleContents, null);
    }

    /**
     * Build a system prompt with per-rule metadata (rule_code, severity, ...) inlined
     * before each rule body. {@code ruleHeaders.size()} must equal {@code ruleContents.size()};
     * each header is rendered as a small front-matter block above its rule body.
     *
     * Passing {@code null} headers falls back to the legacy unannotated layout.
     */
    public static String buildSystemPrompt(List<String> ruleContents, List<String> ruleHeaders) {
        if (ruleContents == null || ruleContents.isEmpty()) {
            return "你是一名专业的文档审查员，请严格按照检查标准审查提供的文档内容，使用中文回复。";
        }

        StringBuilder sp = new StringBuilder();
        sp.append("你是一名专业的文档审查员，负责对文档内容进行严格审查。\n");
        sp.append("请严格按照以下审查规则和检查标准，逐条审查用户提供的文档内容。\n\n");

        sp.append("【输出要求】\n");
        sp.append("请以JSON格式返回审查结果，字段说明如下：\n");
        sp.append("{\n");
        sp.append("  \"summary\": \"本章节审查总结（中文）\",\n");
        sp.append("  \"issues\": [\n");
        sp.append("    {\n");
        sp.append("      \"location\": \"问题所在的章节路径（按下面的章节定位规则填写）\",\n");
        sp.append("      \"description\": \"问题描述\",\n");
        sp.append("      \"suggestion\": \"修改建议\",\n");
        sp.append("      \"rule\": \"对应的审查规则名称（必填）\",\n");
        sp.append("      \"rule_code\": \"命中的规则编号，若规则未提供则留空\",\n");
        sp.append("      \"severity\": \"high | medium | low，若规则未声明默认 medium\",\n");
        sp.append("      \"category\": \"问题分类，例如 格式、完整性、标准符合性、逻辑一致性\",\n");
        sp.append("      \"evidence\": \"判定依据：摘录支持该结论的原文片段或表格行\"\n");
        sp.append("    }\n");
        sp.append("  ],\n");
        sp.append("  \"passed_items\": [\"通过或不适用的检查项\"]\n");
        sp.append("}\n\n");

        sp.append("【章节定位规则（location 字段必须遵守）】\n");
        sp.append("用户消息开头会以 \"章节: <一级标题>\" 的形式给出当前片段所属的一级章节标题；正文中可能还包含 Markdown 形式的二级标题（## 标题）和三级标题（### 标题）。\n");
        sp.append("location 字段必须明确指出问题所在的章节，按以下优先级填写：\n");
        sp.append("  1. 若问题位于某个三级标题（### 开头）所在小节内，location 必须写成 \"<一级标题> > <二级标题> > <三级标题>\"；\n");
        sp.append("  2. 若该位置没有三级标题，但存在二级标题（## 开头），location 必须写成 \"<一级标题> > <二级标题>\"；\n");
        sp.append("  3. 若既没有二级标题也没有三级标题，location 必须直接填写一级标题（即 \"章节:\" 后给出的标题原文）。\n");
        sp.append("注意事项：\n");
        sp.append("  - 标题文本必须与原文逐字一致，包含原有的编号（如 \"1 温度变化试验\"、\"## 试验要求\" 中的 \"试验要求\"），不得自行编造、缩写或翻译；\n");
        sp.append("  - 各级标题之间统一使用 \" > \"（空格-大于号-空格）分隔；\n");
        sp.append("  - 严禁仅写 \"原文\"、\"表格中\"、\"上文\" 等模糊位置，也不要把表格名/图编号当作 location 单独填写——具体的表/图引用请放到 description 中；\n");
        sp.append("  - 如果某条问题贯穿多个小节，请选择问题首次出现的最深一级标题作为 location。\n\n");

        sp.append("【注意事项】\n");
        sp.append("1. 仅输出JSON，不要添加任何markdown代码块标记或其他文字\n");
        sp.append("2. 所有审查结论和描述使用中文\n");
        sp.append("3. 对每条审查规则都给出明确结论（通过或不通过）\n");
        sp.append("4. 如果用户消息中出现 \"=== 以下为本章节引用的其他章节内容 ===\" 这样的分隔块，那一段是被本章节正文引用的其他章节的原文。它仅用于帮助你理解上下文（例如核对引用是否一致、术语是否对应），不要把当前章节的审查规则直接套用到这些被引用章节上，也不要在审查结果里报告它们自身的格式或合规问题。\n");
        sp.append("5. 如果文档中包含HTML表格，请仔细审查表格内容的准确性和完整性，并遵循以下表格阅读规则：\n");
        sp.append("   - 单元格内容为单独的 \"/\"、\"-\"、\"—\"、\"无\"、\"N/A\" 时，表示该项不适用或不涉及，属于已规范填写，不应判定为内容缺失或信息遗漏；\n");
        sp.append("   - 表格使用 HTML 的 rowspan/colspan 属性表示合并单元格。带有 rowspan=\"N\" 的单元格的内容同时适用于其下方 N-1 行的对应位置；带有 colspan=\"N\" 的单元格内容同时适用于其右侧 N-1 列的对应位置。判断行内容是否完整时，必须将合并单元格的值视为已填写，不要因为某行视觉上少几个 <td> 就认为缺失数据；\n");
        sp.append("   - 如果一行只有一个 <td> 且包含\"注\"、\"备注\"、\"说明\"等开头，通常是横跨整行的注释行，属于补充说明，不应作为表格主体数据缺失依据；\n");
        sp.append("   - 序号列、编号列等含有自动编号的列已在解析阶段还原为可见文本，请直接按所见判断；如确实缺号，再据此报告问题。\n\n");

        sp.append("=== 审查规则 ===\n\n");

        for (int i = 0; i < ruleContents.size(); i++) {
            sp.append("--- 规则 ").append(i + 1).append(" ---\n");
            if (ruleHeaders != null && i < ruleHeaders.size()) {
                String header = ruleHeaders.get(i);
                if (header != null && !header.isBlank()) {
                    sp.append(header).append("\n");
                }
            }
            sp.append(ruleContents.get(i)).append("\n\n");
        }

        return sp.toString();
    }

    /**
     * Render a small metadata header for a single rule, to be placed above its body inside
     * the system prompt. Returns an empty string when no metadata is present so the layout
     * stays clean for legacy rules.
     */
    public static String buildRuleHeader(String ruleName, RuleMetadata meta) {
        if (meta == null) {
            return ruleName != null && !ruleName.isBlank() ? "[规则名称] " + ruleName : "";
        }
        StringBuilder sb = new StringBuilder();
        if (ruleName != null && !ruleName.isBlank()) sb.append("[规则名称] ").append(ruleName).append("\n");
        if (meta.getRuleCode() != null && !meta.getRuleCode().isBlank())
            sb.append("[规则编号] ").append(meta.getRuleCode()).append("\n");
        if (meta.getRuleType() != null && !meta.getRuleType().isBlank())
            sb.append("[规则类型] ").append(meta.getRuleType()).append("\n");
        if (meta.getSections() != null && !meta.getSections().isEmpty())
            sb.append("[适用章节] ").append(String.join("、", meta.getSections())).append("\n");
        if (meta.getKeywords() != null && !meta.getKeywords().isEmpty())
            sb.append("[关键词] ").append(String.join("、", meta.getKeywords())).append("\n");
        if (meta.getSeverity() != null && !meta.getSeverity().isBlank())
            sb.append("[严重程度] ").append(meta.getSeverity()).append("\n");
        return sb.toString().trim();
    }

    /**
     * Determine file type from filename extension.
     */
    public static String detectFileType(String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".json")) {
            return "json";
        } else if (lower.endsWith(".md")) {
            return "md";
        }
        return "unknown";
    }

    /**
     * Validate that the content can be parsed as a valid rule.
     *
     * @param content  rule content
     * @param fileType file type (md or json)
     * @return list of validation errors; empty if valid
     */
    public static List<String> validate(String content, String fileType) {
        List<String> errors = new ArrayList<>();

        if (content == null || content.isBlank()) {
            errors.add("Rule content is empty");
            return errors;
        }

        if ("json".equalsIgnoreCase(fileType)) {
            try {
                JSONObject obj = JSON.parseObject(content);
                if (obj == null || obj.isEmpty()) {
                    errors.add("JSON rule content is empty");
                }
            } catch (Exception e) {
                errors.add("Invalid JSON format: " + e.getMessage());
            }
        } else if ("md".equalsIgnoreCase(fileType)) {
            if (content.trim().length() < 10) {
                errors.add("Markdown rule content is too short");
            }
        } else {
            errors.add("Unsupported rule file type: " + fileType);
        }

        return errors;
    }
}
