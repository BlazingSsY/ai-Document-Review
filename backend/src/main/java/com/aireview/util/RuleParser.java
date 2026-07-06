package com.aireview.util;

import com.aireview.review.ReviewResultSchema;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses rule files in .md or .json format and converts them into structured system prompts
 * suitable for AI model consumption.
 */
@Slf4j
public class RuleParser {

    private static final String BUILT_IN_QUALITY_RULE_CODE = "R-Q";

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
     * Build a system prompt with per-rule metadata (rule_code, rule_type, ...) inlined
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
        sp.append("      \"category\": \"问题分类，例如 格式、完整性、标准符合性、逻辑一致性\",\n");
        sp.append("      \"evidence\": \"判定依据：摘录支持该结论的原文片段或表格行\"\n");
        sp.append("    }\n");
        sp.append("  ],\n");
        sp.append("  \"passed_items\": [\"通过的检查项\"]\n");
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
     * 收敛性审查的四段式系统提示词。结构固定：
     * <ol>
     *   <li>ROLE + 任务（极简，不到 200 字）；</li>
     *   <li>JSON Schema（机器可读，直接 stringify {@link ReviewResultSchema#schema()}）；</li>
     *   <li>Few-shot 锚点：1 不通过例 + 1 待复核例 + 1 混合例，含 category 判定说明；</li>
     *   <li>规则清单：调用方已按 {@code rule_code} 升序排序，每条以 {@code [R-XXX]} 编号；</li>
     * </ol>
     * 末尾追加严重度默认值兜底和"只能用清单内编号"的约束，把模型自由度压到最小。
     */
    public static String buildStructuredSystemPrompt(List<RuleEntry> rules) {
        List<RuleEntry> sorted = new ArrayList<>(rules == null ? List.of() : rules);
        sorted.sort(Comparator
                .comparing((RuleEntry e) -> orderKey(e.code))
                .thenComparing(e -> e.name == null ? "" : e.name));

        StringBuilder sp = new StringBuilder();

        // ① ROLE + 任务
        sp.append("【角色与任务】\n");
        sp.append("你是一名专业的文档审查员。请仅基于本提示词内列出的规则审查用户消息中的章节内容，使用中文回复。\n");
        sp.append("禁止补充未在规则中出现的检查项；术语抽取只填写 term_observations，不作为检查项输出；禁止臆断、推测、自由发挥。\n\n");

        // ② JSON Schema
        sp.append("【输出 Schema（必须严格遵守）】\n");
        sp.append("你的输出必须是一个合法 JSON 对象，且符合以下 JSON Schema：\n");
        sp.append("```json\n");
        sp.append(JSON.toJSONString(ReviewResultSchema.schema(),
                JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteMapNullValue));
        sp.append("\n```\n");
        sp.append("禁止输出任何解释、前后缀、markdown 围栏；只能是符合上述 schema 的 JSON 对象本身。\n\n");

        // ③ Few-shot 锚点
        sp.append("【判定锚点 / Few-shot】\n");
        sp.append("category 锚点：必须从 [格式, 完整性, 标准符合性, 逻辑一致性, 术语一致性, 其他] 中选；无法归类填 其他。\n");
        sp.append("rule_code 锚点：必须使用本提示词【审查规则清单】中给出的 [R-XXX] 编号；本次未注入的编号一律不得使用。\n");
        sp.append("location 锚点：按 \"一级标题 > 二级标题 > 三级标题\" 写，逐字与原文一致，禁止仅写 \"原文\" / \"表格中\" / \"上文\"。\n\n");
        sp.append("示例 1（正例，识别为问题）：\n");
        sp.append("{\"summary\":\"试验条件未明确温度区间\",\"issues\":[{\"location\":\"4 试验条件 > 4.2 环境条件\",\"description\":\"未给出工作温度区间\",\"suggestion\":\"补充 \\\"-40℃ ~ +70℃\\\" 等明确区间\",\"rule\":\"环境条件完整性\",\"rule_code\":\"R-001\",\"category\":\"完整性\",\"evidence\":\"原文仅写 \\\"在常温下进行\\\"\"}],\"passed_items\":[],\"check_results\":[{\"check_code\":\"R-001-C001\",\"rule_code\":\"R-001\",\"check_question\":\"是否明确工作温度区间\",\"status\":\"Fail\",\"reason\":\"原文未给出明确温度上下限\",\"evidence\":\"原文仅写 \\\"在常温下进行\\\"\",\"missing_items\":[\"工作温度区间\"],\"suggestion\":\"补充明确温度区间\",\"confidence\":\"high\"}],\"term_observations\":[{\"term\":\"工作温度区间\",\"normalized_term\":\"工作温度区间\",\"term_type\":\"参数名称\",\"definition_or_meaning\":\"设备工作温度上下限范围\",\"location\":\"4 试验条件 > 4.2 环境条件\",\"evidence\":\"工作温度区间\"}]}\n");
        sp.append("示例 2（规则已适配到本章节，但原文明示前置条件不成立/不适用 → 判 Review 交人工复核，不产生 issue）：\n");
        sp.append("{\"summary\":\"本章节明确说明无陪试设备，陪试设备检查项前置条件不成立，转待复核\",\"issues\":[],\"passed_items\":[],\"check_results\":[{\"check_code\":\"R-001-C001\",\"rule_code\":\"R-001\",\"check_question\":\"陪试设备信息是否完整\",\"status\":\"Review\",\"reason\":\"原文明示陪试设备为无，前置条件不成立，无法直接判通过，转人工复核\",\"evidence\":\"陪试设备：无\",\"missing_items\":[],\"suggestion\":\"\",\"confidence\":\"needs_review\"}],\"term_observations\":[{\"term\":\"陪试设备\",\"normalized_term\":\"陪试设备\",\"term_type\":\"设备名称\",\"definition_or_meaning\":\"试验过程中配合受试设备工作的辅助设备\",\"location\":\"设备配置\",\"evidence\":\"陪试设备：无\"}]}\n");
        sp.append("示例 3（混合，有的检查项通过、有的不通过）：\n");
        sp.append("{\"summary\":\"试验步骤完整，但术语不一致\",\"issues\":[{\"location\":\"5 试验步骤 > 5.3\",\"description\":\"同一项目混用 \\\"试件\\\" 与 \\\"样件\\\"\",\"suggestion\":\"统一为 \\\"试件\\\"\",\"rule\":\"术语一致性\",\"rule_code\":\"R-007\",\"category\":\"术语一致性\",\"evidence\":\"5.3.1 用 \\\"样件\\\"，5.3.2 用 \\\"试件\\\"\"}],\"passed_items\":[\"[R-003] 试验步骤完整\"],\"check_results\":[{\"check_code\":\"R-003-C001\",\"rule_code\":\"R-003\",\"check_question\":\"试验步骤是否完整\",\"status\":\"Pass\",\"reason\":\"步骤要素均已给出\",\"evidence\":\"原文列出准备、执行和记录步骤\",\"missing_items\":[],\"suggestion\":\"\",\"confidence\":\"high\"},{\"check_code\":\"R-007-C001\",\"rule_code\":\"R-007\",\"check_question\":\"术语是否一致\",\"status\":\"Fail\",\"reason\":\"同一对象出现两个术语\",\"evidence\":\"5.3.1 用 \\\"样件\\\"，5.3.2 用 \\\"试件\\\"\",\"missing_items\":[],\"suggestion\":\"统一术语\",\"confidence\":\"high\"}],\"term_observations\":[{\"term\":\"样件\",\"normalized_term\":\"试件\",\"term_type\":\"专业术语\",\"definition_or_meaning\":\"接受试验的样品或设备\",\"location\":\"5 试验步骤 > 5.3.1\",\"evidence\":\"样件\"},{\"term\":\"试件\",\"normalized_term\":\"试件\",\"term_type\":\"专业术语\",\"definition_or_meaning\":\"接受试验的样品或设备\",\"location\":\"5 试验步骤 > 5.3.2\",\"evidence\":\"试件\"}]}\n\n");

        // ④ 规则清单
        sp.append("【审查规则清单（按 rule_code 升序）】\n");
        List<String> manifest = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            RuleEntry e = sorted.get(i);
            String code = (e.code != null && !e.code.isBlank()) ? e.code : ("R-AUTO-" + String.format("%03d", i + 1));
            manifest.add(code);
            sp.append("\n[").append(code).append("] ");
            if (e.name != null && !e.name.isBlank()) sp.append(e.name);
            sp.append("\n");
            if (e.body != null && !e.body.isBlank()) {
                sp.append(e.body.trim()).append("\n");
            }
            if (e.checks != null && !e.checks.isEmpty()) {
                if (isBuiltInQualityRuleCode(code)) {
                    sp.append("内置质量检查项（仅发现 Fail/Review 问题时输出 check_results；全部通过时不要输出 Pass 记录）：\n");
                } else {
                    sp.append("原子检查项（必须逐项输出 check_results）：\n");
                }
                for (CheckEntry check : e.checks) {
                    sp.append("- [").append(check.checkCode).append("] ");
                    sp.append(check.question == null ? "" : check.question);
                    if (check.passCriteria != null && !check.passCriteria.isBlank()) {
                        sp.append("；通过标准：").append(check.passCriteria);
                    }
                    if (check.category != null && !check.category.isBlank()) {
                        sp.append("；分类：").append(check.category);
                    }
                    if (Boolean.TRUE.equals(check.evidenceRequired)) {
                        sp.append("；必须给出证据");
                    }
                    sp.append("\n");
                }
            }
        }
        sp.append("\n本次注入的 rule_code 清单：").append(String.join(", ", manifest)).append("\n");
        sp.append("issues[].rule_code 必须且只能从该清单中选择；不在清单内的编号不允许出现。\n");
        sp.append("除内置基础文字质量规则 R-Q 外，若规则下列出了原子检查项，则必须为每个原子检查项输出一条 check_results[]；"
                + "status 只能是 Pass、Fail、Review 三选一（不得使用 N/A 或 Partial）。"
                + "证据充分且满足通过标准判 Pass；明确不满足判 Fail；"
                + "证据不足、部分满足、或原文明示前置条件不成立/不适用，一律判 Review 交人工复核（不得判 Pass）。"
                + "不能因为章节主题与规则无关而批量判 Review，这类规则应由规则分发阶段排除。"
                + "R-Q 基础文字质量检查始终适用，但仅在发现文字质量、图号/表号问题或需人工复核时输出；没有问题时不要输出 R-Q 的 Pass 检查结果。\n");

        // 表格阅读规则（保留，原 prompt 中证实对 HTML 表格审查很关键）
        sp.append("\n【表格阅读注意事项】\n");
        sp.append("- 单元格内容为 \"/\"、\"-\"、\"—\"、\"无\"、\"N/A\" 时表示不适用，不应判定为缺失；\n");
        sp.append("- rowspan/colspan 合并单元格的值同时适用于其覆盖的行/列，判定时按合并语义视为已填写；\n");
        sp.append("- 一行只有一个 <td> 且以 \"注/备注/说明\" 开头的为补充说明行，不作为数据缺失依据；\n");
        sp.append("- 章节正文中若出现 \"=== 以下为本章节引用的其他章节内容 ===\" 分隔块，仅用于补充上下文，不要对其内容套用规则。\n");

        sp.append("\n【术语抽取（用于全文专业术语一致性复核）】\n");
        sp.append("每次都必须填写 term_observations；没有可筛选术语时填空数组 []。\n");
        sp.append("只提取对全文一致性有复核价值的术语：专业术语、缩略语、设备/系统/软件名称、试验项目、参数名称、标准名称、关键对象称谓。\n");
        sp.append("不要提取普通虚词、常见动词、泛泛章节名或一次性无复核价值的普通词；每个切片最多提取 30 条。\n");
        sp.append("同一对象出现别名/简称/中英文混写时，normalized_term 填建议统一写法；证据不足时 normalized_term 与 term 相同。\n");

        // 末尾强制全覆盖锚定：放在 prompt 最后利用 recency，配合 schema 的 minItems，
        // 逼模型为每条注入规则都产出 check_results，避免弱模型（如 DeepSeek-V4-Flash）漏判。
        int expected = expectedCheckCount(sorted);
        sp.append("\n【强制全覆盖（务必遵守）】\n");
        sp.append("本次共注入 ").append(expected).append(" 个必须返回的业务待判定项（不含 R-Q 内置质量检查）。\n");
        sp.append("check_results 数组必须【至少包含 ").append(expected).append(" 条业务规则结果】，且逐一覆盖上面列出的每一条非 R-Q 规则：\n");
        sp.append("- 即使某条上传规则判 Pass、或与本章主题关系不大，也必须为其输出一条 check_results；\n");
        sp.append("- rule_code 用该规则编号；有原子检查项的用其 check_code，无原子检查项的用『规则编号-C001』作为 check_code；\n");
        sp.append("- R-Q 质量检查只在 Fail/Review 时额外输出，不计入上述 ").append(expected).append(" 条强制覆盖数量；\n");
        sp.append("- 严禁遗漏、合并或少于 ").append(expected).append(" 条业务规则结果。\n");
        return sp.toString();
    }

    /**
     * 期望的强制 check_results 条数 = 每条非 R-Q 规则至少 1 条；定义了原子检查项的按其数量计。
     * R-Q 是内置质量检查，只有发现问题时才进入矩阵，不参与 minItems 下限。
     * 同时用于末尾覆盖锚定文案与 schema 的 {@code minItems} 下限。
     */
    public static int expectedCheckCount(List<RuleEntry> entries) {
        if (entries == null) return 0;
        int n = 0;
        for (RuleEntry e : entries) {
            if (e == null || isBuiltInQualityRuleCode(e.code)) continue;
            int c = (e.checks == null) ? 0 : e.checks.size();
            n += Math.max(1, c);
        }
        return n;
    }

    public static boolean isBuiltInQualityRuleCode(String code) {
        return code != null && BUILT_IN_QUALITY_RULE_CODE.equalsIgnoreCase(code.trim());
    }

    /**
     * 批量审查系统提示词。在单切片四段结构的基础上：
     * <ul>
     *   <li>替换 Schema 段为 {@link ReviewResultSchema#batchSchema()}；</li>
     *   <li>新增"批量输入约定"段：说明 ===CHUNK <id>=== 分隔符与 chunk_id 必须回填；</li>
     *   <li>把 chunk_id 列表显式列出，要求模型按列表完整输出，不得遗漏或新增。</li>
     * </ul>
     * 其余 ROLE / Few-shot / 规则清单复用单切片版本，保证 prompt 缓存命中（同签名同前缀）。
     */
    public static String buildBatchStructuredSystemPrompt(List<RuleEntry> rules, List<String> chunkIds) {
        // 复用单切片 prompt 主体作为前缀（命中 prompt 缓存的关键）
        String basePrompt = buildStructuredSystemPrompt(rules);
        // 把单切片 schema 段替换为 batch schema 段，保留 ROLE / Few-shot / 规则清单一字不差
        String singleSchemaBlock = "【输出 Schema（必须严格遵守）】";
        int schemaStart = basePrompt.indexOf(singleSchemaBlock);
        int fewShotStart = basePrompt.indexOf("【判定锚点 / Few-shot】");
        if (schemaStart < 0 || fewShotStart < 0 || fewShotStart <= schemaStart) {
            // 解析失败：直接退化为前缀 + batch 附录
            return basePrompt + "\n\n" + batchInputContract(chunkIds);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt, 0, schemaStart);
        sb.append("【输出 Schema（必须严格遵守 / 批量版本）】\n");
        sb.append("你的输出必须是一个合法 JSON 对象，且符合以下 JSON Schema：\n");
        sb.append("```json\n");
        sb.append(JSON.toJSONString(ReviewResultSchema.batchSchema(),
                JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteMapNullValue));
        sb.append("\n```\n");
        sb.append("禁止输出任何解释、前后缀、markdown 围栏；只能是符合上述 schema 的 JSON 对象本身。\n\n");
        sb.append(basePrompt, fewShotStart, basePrompt.length());
        sb.append("\n\n").append(batchInputContract(chunkIds));
        return sb.toString();
    }

    private static String batchInputContract(List<String> chunkIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("【批量输入约定（极其重要）】\n");
        sb.append("用户消息包含多段章节，按以下方式分隔：\n");
        sb.append("  ===CHUNK <chunk_id>===\n");
        sb.append("  章节: <一级标题>\n");
        sb.append("  <章节正文>\n");
        sb.append("规则：\n");
        sb.append("1. 你必须为列表中的每一个 chunk_id 输出一条对应的 chunks[] 元素，且数量、顺序保持一致；\n");
        sb.append("2. chunk_id 字段必须与输入完全一致，禁止重命名、合并或新增；\n");
        sb.append("3. 每个 chunk 的 issues 必须只来自该 chunk 自己的正文，禁止跨 chunk 串味；\n");
        sb.append("4. 即使某个 chunk 没有发现问题，也要返回对应元素，issues 设为空数组，并在 passed_items 中说明。\n");
        if (chunkIds != null && !chunkIds.isEmpty()) {
            sb.append("\n本次必须输出的 chunk_id 列表（顺序需保持）：")
              .append(String.join(", ", chunkIds))
              .append("\n");
        }
        return sb.toString();
    }

    /** 安全排序键：缺失或非法的 rule_code 排到最后。 */
    private static String orderKey(String code) {
        return (code == null || code.isBlank()) ? "ZZZZZZZZ" : code;
    }

    /**
     * 一条规则在 prompt 中的最小载荷。
     * code：用于编号与排序；name：人类可读名称；body：规则正文。
     */
    public static final class RuleEntry {
        public final String code;
        public final String name;
        public final String body;
        public final List<CheckEntry> checks;

        public RuleEntry(String code, String name, String body) {
            this(code, name, body, List.of());
        }

        public RuleEntry(String code, String name, String body, List<CheckEntry> checks) {
            this.code = code;
            this.name = name;
            this.body = body;
            this.checks = checks == null ? List.of() : checks;
        }
    }

    public static final class CheckEntry {
        public final String checkCode;
        public final String question;
        public final String passCriteria;
        public final String category;
        public final Boolean evidenceRequired;

        public CheckEntry(String checkCode, String question, String passCriteria,
                          String category, Boolean evidenceRequired) {
            this.checkCode = checkCode;
            this.question = question;
            this.passCriteria = passCriteria;
            this.category = category;
            this.evidenceRequired = evidenceRequired;
        }
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
