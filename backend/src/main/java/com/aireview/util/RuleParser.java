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
        if (ruleContents == null || ruleContents.isEmpty()) {
            return "你是一名专业的文档审查员，请严格按照检查标准审查提供的文档内容，使用中文回复。";
        }

        StringBuilder sp = new StringBuilder();
        sp.append("你是一名专业的文档审查员，负责对文档内容进行严格审查。\n");
        sp.append("请严格按照以下审查规则和检查标准，逐条审查用户提供的文档内容。\n\n");

        sp.append("【输出要求】\n");
        sp.append("请以JSON格式返回审查结果，字段说明如下：\n");
        sp.append("{\n");
        sp.append("  \"overall_score\": 0-100的整体评分,\n");
        sp.append("  \"summary\": \"本章节审查总结（中文）\",\n");
        sp.append("  \"issues\": [\n");
        sp.append("    {\n");
        sp.append("      \"severity\": \"high/medium/low\",\n");
        sp.append("      \"location\": \"问题所在位置（引用原文或表格位置）\",\n");
        sp.append("      \"description\": \"问题描述\",\n");
        sp.append("      \"suggestion\": \"修改建议\",\n");
        sp.append("      \"rule\": \"对应的审查规则名称\"\n");
        sp.append("    }\n");
        sp.append("  ],\n");
        sp.append("  \"passed_items\": [\"通过的检查项列表\"]\n");
        sp.append("}\n\n");

        sp.append("【注意事项】\n");
        sp.append("1. 仅输出JSON，不要添加任何markdown代码块标记或其他文字\n");
        sp.append("2. 所有审查结论和描述使用中文\n");
        sp.append("3. 如果文档中包含HTML表格，请仔细审查表格内容的准确性和完整性\n");
        sp.append("4. 对每条审查规则都给出明确结论（通过或不通过）\n\n");

        sp.append("=== 审查规则 ===\n\n");

        for (int i = 0; i < ruleContents.size(); i++) {
            sp.append("--- 规则 ").append(i + 1).append(" ---\n");
            sp.append(ruleContents.get(i)).append("\n\n");
        }

        return sp.toString();
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
