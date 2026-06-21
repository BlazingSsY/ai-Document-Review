package com.aireview.review;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;

/**
 * 收敛性审查结果的统一 JSON Schema。
 *
 * <p>所有 provider 都用同一份 schema 强制结构化输出 —— OpenAI 兼容协议走
 * {@code response_format=json_schema}，Anthropic 走 {@code tool_use+tool_choice}。
 * Schema 字段、枚举锚点（category）由本类集中维护，避免散落在 prompt
 * 字符串里造成版本漂移。
 *
 * <p>Schema 约定：
 * <ul>
 *   <li>{@code summary}：当前切片的中文总结。</li>
 *   <li>{@code issues[]}：每条问题必须填齐 rule_code / category / location /
 *       description / suggestion / evidence；这些字段就是跨模型可对比的最小集。</li>
 *   <li>{@code passed_items[]}：通过的检查项（建议带上 [R-XXX] 编号）。</li>
 * </ul>
 *
 * <p>category 用 enum 写死，模型不允许自由发挥；当无法判断时强制输出 {@code 其他}。
 */
public final class ReviewResultSchema {

    public static final List<String> CATEGORY_ENUM = List.of(
            "格式", "完整性", "标准符合性", "逻辑一致性", "术语一致性", "其他");
    // 三级判定：Pass（通过）、Fail（不通过）、Review（待复核）。不再使用 Partial / N/A，
    // 模型遇到「部分通过」「不适用 / 前置条件不成立」一律判 Review，交人工复核。
    public static final List<String> CHECK_STATUS_ENUM = List.of("Pass", "Fail", "Review");

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
        issueProps.put("category", enumProp(CATEGORY_ENUM, "问题分类。无法归类时填 其他。"));
        issueProps.put("evidence", strProp("判定依据：摘录支持该结论的原文片段或表格行。"));

        JSONObject issueSchema = new JSONObject();
        issueSchema.put("type", "object");
        issueSchema.put("required", JSON.parseArray(JSON.toJSONString(List.of(
                "location", "description", "suggestion", "rule_code", "category", "evidence"))));
        issueSchema.put("properties", issueProps);
        issueSchema.put("additionalProperties", false);

        JSONObject issuesArr = new JSONObject();
        issuesArr.put("type", "array");
        issuesArr.put("items", issueSchema);

        JSONObject passedArr = new JSONObject();
        passedArr.put("type", "array");
        passedArr.put("items", strProp(null));

        JSONObject missingArr = new JSONObject();
        missingArr.put("type", "array");
        missingArr.put("items", strProp("缺失项或需补充的信息。"));

        JSONObject checkProps = new JSONObject();
        checkProps.put("check_code", strProp("原子检查项编号，必须使用本次注入清单内的编号。"));
        checkProps.put("rule_code", strProp("所属规则编号，必须使用本次注入清单内的规则编号。"));
        checkProps.put("check_question", strProp("检查项问题。"));
        checkProps.put("status", enumProp(CHECK_STATUS_ENUM,
                "三级判定：Pass（通过）、Fail（不通过）、Review（待复核）。部分通过、不适用一律判 Review。"));
        checkProps.put("reason", strProp("判定理由，说明为何给出该状态。"));
        checkProps.put("evidence", strProp("证据原文摘录；证据不足时写明未找到直接证据。"));
        checkProps.put("missing_items", missingArr);
        checkProps.put("suggestion", strProp("整改建议；Pass 可为空字符串。"));
        checkProps.put("confidence", enumProp(List.of("high", "medium", "low", "needs_review"),
                "置信度。证据不足、冲突或模型不确定时填 needs_review。"));

        JSONObject checkSchema = new JSONObject();
        checkSchema.put("type", "object");
        checkSchema.put("required", JSON.parseArray(JSON.toJSONString(List.of(
                "check_code", "rule_code", "check_question", "status", "reason",
                "evidence", "missing_items", "suggestion", "confidence"))));
        checkSchema.put("properties", checkProps);
        checkSchema.put("additionalProperties", false);

        JSONObject checkArr = new JSONObject();
        checkArr.put("type", "array");
        checkArr.put("items", checkSchema);

        JSONObject rootProps = new JSONObject();
        rootProps.put("summary", strProp("本切片审查总结（中文）。"));
        rootProps.put("issues", issuesArr);
        rootProps.put("passed_items", passedArr);
        rootProps.put("check_results", checkArr);

        JSONObject root = new JSONObject();
        root.put("type", "object");
        root.put("required", JSON.parseArray(JSON.toJSONString(List.of("summary", "issues", "passed_items", "check_results"))));
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
                List.of("chunk_id", "summary", "issues", "passed_items", "check_results"))));
        JSONObject chunkProps = new JSONObject();
        chunkProps.put("chunk_id", strProp("批次中该切片的唯一标记，必须与 user message 中的 ===CHUNK <id>=== 对齐。"));
        // 把单切片 schema 的 properties 嫁接进来，避免重复定义
        JSONObject innerProps = inner.getJSONObject("properties");
        chunkProps.put("summary", innerProps.getJSONObject("summary"));
        chunkProps.put("issues", innerProps.getJSONObject("issues"));
        chunkProps.put("passed_items", innerProps.getJSONObject("passed_items"));
        chunkProps.put("check_results", innerProps.getJSONObject("check_results"));
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

    /**
     * Per-request variant of {@link #schema()} that sets a {@code minItems} floor on the
     * {@code check_results} array, so the model must return at least that many judgements
     * (one per injected rule). Used together with the prompt's coverage anchoring to stop
     * weak models from silently dropping rule checks. {@code check_results} stays a free
     * array (model may still produce its own check_codes), so body-only rules keep working.
     */
    public static JSONObject schemaWithMinChecks(int minItems) {
        JSONObject root = schema();
        if (minItems > 0) {
            root.getJSONObject("properties")
                .getJSONObject("check_results")
                .put("minItems", minItems);
        }
        return root;
    }

    /**
     * RAG 分组评估 schema（按章节聚合、一次调用评估多个检查项）。
     *
     * <p>一次调用把"本组共享的证据原文 + 多个检查项"一起送给模型，模型对每个 check_code
     * 各返回一条结果。这样同一段原文只发一次、N 个检查项共用，相比"每检查项一次调用"大幅
     * 降低 token。后端按 check_code 回填；模型漏返回的 check_code 走补审轮兜底。
     */
    public static JSONObject ragGroupSchema() {
        JSONObject findingProps = new JSONObject();
        findingProps.put("location", strProp("违规所在位置：章节路径或可在原文中定位的线索。"));
        findingProps.put("evidence", strProp("支持该违规判定的原文摘录，必须逐字引用证据块中的文字。"));
        findingProps.put("description", strProp("该处违规的具体说明。"));
        findingProps.put("suggestion", strProp("针对该处的整改建议。"));
        JSONObject findingSchema = new JSONObject();
        findingSchema.put("type", "object");
        findingSchema.put("required", JSON.parseArray(JSON.toJSONString(List.of(
                "location", "evidence", "description", "suggestion"))));
        findingSchema.put("properties", findingProps);
        findingSchema.put("additionalProperties", false);
        JSONObject findingsArr = new JSONObject();
        findingsArr.put("type", "array");
        findingsArr.put("items", findingSchema);

        JSONObject resultProps = new JSONObject();
        resultProps.put("check_code", strProp("检查项编号，必须与输入中给出的 check_code 完全一致。"));
        resultProps.put("status", enumProp(CHECK_STATUS_ENUM,
                "三级判定：Pass（能引用到满足要求的原文）、Fail（存在违规或要求内容缺失）、"
                        + "Review（证据自相矛盾或确实无法判断）。"));
        resultProps.put("reason", strProp("总体判定理由（中文）。"));
        resultProps.put("confidence", enumProp(List.of("high", "medium", "low", "needs_review"),
                "置信度。证据不足、冲突或不确定时填 needs_review。"));
        resultProps.put("findings", findingsArr);
        JSONObject resultSchema = new JSONObject();
        resultSchema.put("type", "object");
        resultSchema.put("required", JSON.parseArray(JSON.toJSONString(List.of(
                "check_code", "status", "reason", "findings"))));
        resultSchema.put("properties", resultProps);
        resultSchema.put("additionalProperties", false);

        JSONObject resultsArr = new JSONObject();
        resultsArr.put("type", "array");
        resultsArr.put("items", resultSchema);
        resultsArr.put("minItems", 1);
        resultsArr.put("description", "对输入里每一个 check_code 各返回一条；不得遗漏、不得新增。");

        JSONObject root = new JSONObject();
        root.put("type", "object");
        root.put("required", JSON.parseArray(JSON.toJSONString(List.of("results"))));
        JSONObject rootProps = new JSONObject();
        rootProps.put("results", resultsArr);
        root.put("properties", rootProps);
        root.put("additionalProperties", false);
        return root;
    }

    /**
     * 二阶段复核 schema：对一条候选违规独立复判，只回 verdict + 理由，绝不改写原 finding。
     */
    public static JSONObject ragVerifySchema() {
        JSONObject props = new JSONObject();
        props.put("verdict", enumProp(List.of("CONFIRMED", "UNCERTAIN"),
                "CONFIRMED=确实违规；UNCERTAIN=证据不足/可能误报（召回优先：不下 Pass 结论，保留人工复核）。"));
        props.put("reason", strProp("复核理由（中文），说明确认或存疑的依据。"));

        JSONObject root = new JSONObject();
        root.put("type", "object");
        root.put("required", JSON.parseArray(JSON.toJSONString(List.of("verdict", "reason"))));
        root.put("properties", props);
        root.put("additionalProperties", false);
        return root;
    }

    public static final String RAG_GROUP_SCHEMA_NAME = "rag_group_result";
    public static final String RAG_VERIFY_SCHEMA_NAME = "rag_verify_result";

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
