package com.aireview.review;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;

/**
 * 收敛性审查结果的统一 JSON Schema。
 *
 * <p>所有 provider 都用同一份 schema 强制结构化输出 —— OpenAI 兼容协议走
 * {@code response_format=json_schema}，Anthropic 走 {@code tool_use+tool_choice}。
 * Schema 字段、枚举锚点（severity / category）由本类集中维护，避免散落在 prompt
 * 字符串里造成版本漂移。
 *
 * <p>Schema 约定：
 * <ul>
 *   <li>{@code summary}：当前切片的中文总结。</li>
 *   <li>{@code issues[]}：每条问题必须填齐 rule_code / severity / category / location /
 *       description / suggestion / evidence；这些字段就是跨模型可对比的最小集。</li>
 *   <li>{@code passed_items[]}：通过或不适用的检查项（建议带上 [R-XXX] 编号）。</li>
 * </ul>
 *
 * <p>severity / category 用 enum 写死，模型不允许自由发挥；当无法判断时强制
 * 输出 {@code medium} / {@code 其他}。
 */
public final class ReviewResultSchema {

    public static final List<String> SEVERITY_ENUM = List.of("high", "medium", "low");
    public static final List<String> CATEGORY_ENUM = List.of(
            "格式", "完整性", "标准符合性", "逻辑一致性", "术语一致性", "其他");

    public static final String SCHEMA_NAME = "review_result";
    public static final String BATCH_SCHEMA_NAME = "batch_review_result";

    private ReviewResultSchema() {}

    /**
     * 返回一份新构造的 Schema 副本。每次新建是因为某些 provider 会修改入参
     * （例如 OpenAI 在 strict 模式下要求 additionalProperties=false 的副本）。
     */
    public static JSONObject schema() {
        JSONObject issueProps = new JSONObject();
        issueProps.put("location", strProp("问题所在章节路径，按 \"一级 > 二级 > 三级\" 写。"));
        issueProps.put("description", strProp("问题描述。"));
        issueProps.put("suggestion", strProp("修改建议。"));
        issueProps.put("rule", strProp("命中的规则名称。"));
        issueProps.put("rule_code", strProp("命中的规则编号，必须使用本次注入清单内的 [R-XXX] 编号。"));
        issueProps.put("severity", enumProp(SEVERITY_ENUM, "严重度。无法判断时填 medium。"));
        issueProps.put("category", enumProp(CATEGORY_ENUM, "问题分类。无法归类时填 其他。"));
        issueProps.put("evidence", strProp("判定依据：摘录支持该结论的原文片段或表格行。"));

        JSONObject issueSchema = new JSONObject();
        issueSchema.put("type", "object");
        issueSchema.put("required", JSON.parseArray(JSON.toJSONString(List.of(
                "location", "description", "suggestion", "rule_code", "severity", "category", "evidence"))));
        issueSchema.put("properties", issueProps);
        issueSchema.put("additionalProperties", false);

        JSONObject issuesArr = new JSONObject();
        issuesArr.put("type", "array");
        issuesArr.put("items", issueSchema);

        JSONObject passedArr = new JSONObject();
        passedArr.put("type", "array");
        passedArr.put("items", strProp(null));

        JSONObject rootProps = new JSONObject();
        rootProps.put("summary", strProp("本切片审查总结（中文）。"));
        rootProps.put("issues", issuesArr);
        rootProps.put("passed_items", passedArr);

        JSONObject root = new JSONObject();
        root.put("type", "object");
        root.put("required", JSON.parseArray(JSON.toJSONString(List.of("summary", "issues", "passed_items"))));
        root.put("properties", rootProps);
        root.put("additionalProperties", false);
        return root;
    }

    /**
     * 批量审查 schema：把单 chunk 的 {summary, issues, passed_items} 套到外层
     * {chunks: [{chunk_id, ...}]}。chunk_id 必须与 user message 中给出的标记一致，
     * 模型不得遗漏或新增 chunk_id。后端按 chunk_id 把每条结果回填到对应切片，
     * 缺失或重复触发"该批拆回单切片重发"的兜底。
     */
    public static JSONObject batchSchema() {
        JSONObject inner = schema(); // 复用单切片 schema 作为每个 chunk 元素

        JSONObject chunkItem = new JSONObject();
        chunkItem.put("type", "object");
        chunkItem.put("required", JSON.parseArray(JSON.toJSONString(
                List.of("chunk_id", "summary", "issues", "passed_items"))));
        JSONObject chunkProps = new JSONObject();
        chunkProps.put("chunk_id", strProp("批次中该切片的唯一标记，必须与 user message 中的 ===CHUNK <id>=== 对齐。"));
        // 把单切片 schema 的 properties 嫁接进来，避免重复定义
        JSONObject innerProps = inner.getJSONObject("properties");
        chunkProps.put("summary", innerProps.getJSONObject("summary"));
        chunkProps.put("issues", innerProps.getJSONObject("issues"));
        chunkProps.put("passed_items", innerProps.getJSONObject("passed_items"));
        chunkItem.put("properties", chunkProps);
        chunkItem.put("additionalProperties", false);

        JSONObject chunksArr = new JSONObject();
        chunksArr.put("type", "array");
        chunksArr.put("items", chunkItem);
        chunksArr.put("minItems", 1);

        JSONObject rootProps = new JSONObject();
        rootProps.put("chunks", chunksArr);

        JSONObject root = new JSONObject();
        root.put("type", "object");
        root.put("required", JSON.parseArray(JSON.toJSONString(List.of("chunks"))));
        root.put("properties", rootProps);
        root.put("additionalProperties", false);
        return root;
    }

    private static JSONObject strProp(String description) {
        JSONObject p = new JSONObject();
        p.put("type", "string");
        if (description != null) p.put("description", description);
        return p;
    }

    private static JSONObject enumProp(List<String> values, String description) {
        JSONObject p = new JSONObject();
        p.put("type", "string");
        p.put("enum", JSON.parseArray(JSON.toJSONString(values)));
        if (description != null) p.put("description", description);
        return p;
    }
}
