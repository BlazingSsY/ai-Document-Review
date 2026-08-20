package com.aireview.rule.engine;

import com.aireview.review.core.ReviewResultSchema;
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
    /**
     * {@code MultiRuleParser.renderCanonicalRuleBody} 渲染检查项段落时用的固定标题。
     * 提示词与 token 预算都靠它把「规则头部」与「检查项清单」切开。
     */
    private static final String CHECKS_SECTION_MARKER = "## 原子检查项";

    /**
     * 取规则正文中检查项段之前的部分（规则编号、说明等）。
     *
     * <p>仅对 JSON 规则包渲染出的正文有效；Markdown 规则正文没有该标记，原样返回。
     */
    public static String bodyWithoutChecks(String body) {
        if (body == null || body.isBlank()) return body;
        int idx = body.indexOf(CHECKS_SECTION_MARKER);
        if (idx < 0) return body;
        // 标题可能是 ## 或 ###：从「## 原子检查项」往前把同一行的 # 一并吃掉，
        // 否则 substring 会在正文尾部留下一个孤立的 #。
        while (idx > 0 && body.charAt(idx - 1) == '#') idx--;
        return body.substring(0, idx).trim();
    }

    public static String buildStructuredSystemPrompt(List<RuleEntry> rules) {
        List<RuleEntry> sorted = new ArrayList<>(rules == null ? List.of() : rules);
        sorted.sort(Comparator
                .comparing((RuleEntry e) -> orderKey(e.code))
                .thenComparing(e -> e.name == null ? "" : e.name));

        int expected = expectedCheckCount(sorted);
        List<String> manifest = new ArrayList<>();
        List<String> checkManifest = new ArrayList<>();
        StringBuilder sp = new StringBuilder();

        // ① 公共审查约束：所有章节规则共用，避免在每条规则内重复注入。
        sp.append("## 一、公共审查约束\n\n");
        sp.append("你是一名熟悉 RTCA DO-160G、环境鉴定试验大纲及航空产品验证文件的专业审查员。\n");
        sp.append("仅依据本提示词中的规则审查用户消息中的章节内容，所有分析和输出使用中文。不得补充规则之外的检查项，不得臆断、推测或自由发挥。\n\n");
        sp.append("### 1.1 状态\n\n");
        sp.append("- `Pass`：证据充分，满足规则全部适用条件。\n");
        sp.append("- `Fail`：存在明确不符合，或规则明确要求的内容缺失。\n");
        sp.append("- `Review`：前置条件不成立、证据不可核验、证据冲突，或规则明确要求人工复核。\n");
        sp.append("- 不得输出 `Partial`、`N/A`、“部分通过”“不适用”等其他状态。\n");
        sp.append("- 不适用情形按 1.7 处理：类别/制式明示落在条款适用范围之外的判 `Pass`；只有适用性本身无法判定时才用 `Review`。\n\n");
        sp.append("### 1.2 统一检索与判定\n\n");
        sp.append("1. 先检索后判定：检索当前章节正文、表格、图题/图注及当前输入中明确有效的引用。\n");
        sp.append("2. 只比较同一对象、字段和条件；不得凭经验补充原文不存在的事实、参数、型号或标准依据。\n");
        sp.append("3. `XXX`、`TBD`、`待定`、`待补充`视为未填写；`/`、`-`、`—`、`无`、`N/A`不自动构成通过证据。\n");
        sp.append("4. 缺失或不符合时，`reason`写明实际检索范围、缺失项或不符合事实；纯缺失时 `evidence` 必须为空字符串。\n");
        sp.append("5. `evidence`只能逐字引用输入中的最小充分原文或完整表格行，不得改写或加入解释。\n");
        sp.append("6. `Pass`的 `evidence`必须支持全部适用条件；Fail或有直接证据的Review，其reason中的引用必须与evidence一致。\n");
        sp.append("7. 同一检查项内任一强制条件Fail，最终为Fail；无Fail但存在Review，最终为Review；全部适用条件满足才为Pass。\n");
        sp.append("8. 同一事实只在最直接、最具体的规则下创建issue；其他规则保留自身结论，但不得重复相同issue。\n");
        sp.append("9. `suggestion`必须写明需要补充或修改的对象、字段和要求，保持原文原意且可直接采用；禁止只写“建议完善”“请核实”等空泛措辞，也不得臆造原文和规则均未给出的数值、型号或文件名称。\n\n");
        sp.append("### 1.3 表格与图示\n\n");
        sp.append("- 按 `rowspan`、`colspan` 还原合并单元格；跨行“注/备注/说明”不作为主体字段缺失。\n");
        sp.append("- 输入中的图片一律以 `[图表 N]` 占位符形式出现，图像内容无法读取，这是解析阶段的固有限制，不是文档缺陷。检查项要求「具备试验连接图/布置图」时，只要能检索到对应的图题、图注或图清单条目，即视为图示存在，判Pass，并在suggestion中提示图面内容需人工核对；不得因「未提供可读取的图形内容」判Fail或Review。\n");
        sp.append("- 仅当检查项要求的图**连图题都检索不到**时，才按缺图判Fail。图面细节（布置方式、连接关系、标注数值）不作为自动判定依据，一律只写入suggestion。\n");
        sp.append("- 表格数值必须结合表头、单位、适用行列和注释解释。\n\n");

        // 通用判定约束（从规则文件提取，统一注入一次，避免每条规则重复）
        sp.append("### 1.4 证据充分即通过\n\n");
        sp.append("evidence在数值、时长、次数、状态、顺序、精度上已满足要求时必须判Pass。不得以「表述易混淆」「未明确」「未显式说明」「表述不一致」为由推翻已摘录的证据；不得因原文用语与规则用词不同而判不符。原文写「至少3h」「保持3h」「稳定后再保持3h」，规则要求「至少3小时」，三者均判Pass；原文写「不低于5℃/min」，规则要求「最小5℃/min」，判Pass。若evidence中已含满足要求的数值或状态，reason不得出现「未明确」「未给出」「缺少」等与该证据矛盾的表述——出现即为判定错误。\n\n");

        sp.append("### 1.5 跨章引用视为本章证据\n\n");
        sp.append("原文以编号、代号或章节号引用本大纲其他章节的定义与要求（如「工作状态1」「详见6.4节」「见8.4」「按12.2章节表12要求」），必须先解析被引用处的内容再判定。被引用处已给出所需内容的，视为本章已满足，判Pass，不得以「本章未重复描述」判缺失。仅当被引用目标不存在、指向错误或其内容为空时才判Fail，且问题定位到被引用处，同一缺陷不在各引用章重复报告。\n\n");

        sp.append("### 1.6 建议项不扣分\n\n");
        sp.append("审查步骤中标注「【建议项】」的条款，以及以「宜」「建议」「可」「最好」表述的要求，一律不得作为Fail或Review依据。该条款未落实时仍判Pass，仅在suggestion中写出可直接执行的优化建议，不写入issues，不列入missing_items。只有标注「【强制项】」或以「应」「须」「必须」「不得」表述并给出明确判定阈值的条款，才可判Fail。\n\n");

        sp.append("### 1.7 不适用即通过\n\n");
        sp.append("条款的适用范围由设备类别、供电制式（交流/直流）、安装区域、试验方法或标准明文限定时，先判适用性再判符合性。原文已明示的类别/制式落在该条款适用范围之外的，判Pass，reason写明「本条适用于X，本设备为Y，不适用」，既不判Fail也不判Review。仅当类别/制式本身缺失、或与正文自相矛盾而无法判定适用性时，才判Review。\n\n");

        sp.append("### 1.8 与标准原文一致即通过\n\n");
        sp.append("大纲表述与DO-160G相应条款一致时一律判Pass，不得要求大纲比标准更细。标准本身只作原则性规定的（如「必须由有资质的人员来进行评价」并未定义何种资质、量值只给范围而不给单一值），大纲照搬即属合规，不得以「未明确资质」「未给出具体值」「未说明差异原因」判Fail或Review。仅当大纲的取值、名称或程序与标准确有出入时才判Fail，且必须同时引用标准原文与大纲原文两处证据，写明「标准为X、大纲为Y」。\n\n");

        sp.append("### 1.9 引用本文档表格前必须先读表\n\n");
        sp.append("原文以「见表N」「详见表N」「见附录X」指向本文档自身的表格或附录时，必须先定位并读取该表内容再判定。不得因正文只写了一句引用，就断言其内容缺失、不完整或与标准不一致。确实检索不到被引用的表/附录时判Review，写明检索范围与被引用编号，不判Fail。\n\n");

        // ② 输入边界：规则已经由调度器筛选，本段只约束当前调用的输入切片。
        sp.append("## 二、审查对象与输入边界\n\n");
        sp.append("用户消息应包含 `章节: <一级标题>` 及待审查正文。只审查当前输入章节及本提示词列出的规则。\n\n");
        sp.append("1. 若输入中出现 `=== 以下为本章节引用的其他章节内容 ===`，其后内容仅用于核验当前章节的有效引用和理解上下文，不对被引用章节自身另行报告问题。\n");
        sp.append("2. 被引用内容只有在当前章节存在明确、有效的引用关系时，才可作为当前规则的补充依据。\n");
        sp.append("3. 若输入标题或正文明显不属于当前规则适用章节，受影响规则判Review，并在reason中说明输入范围不匹配。\n");
        sp.append("4. 即使某项不存在或无法核验，也必须输出对应check_results，不得静默跳过。\n\n");

        // ③ 规则清单：只放编号、名称和原子检查项编号，作为结果编号锚点。
        sp.append("## 三、本章规则清单\n\n");
        sp.append("以下是本次实际注入的规则。每条规则只生成一个独立规则结果；规则内的多个审查步骤归并为该规则对应原子检查项的一条结论。\n\n");
        sp.append("| 规则编号 | 规则名称 | 原子检查项 |\n|---|---|---|\n");
        for (int i = 0; i < sorted.size(); i++) {
            RuleEntry e = sorted.get(i);
            String code = (e.code != null && !e.code.isBlank()) ? e.code : ("R-AUTO-" + String.format("%03d", i + 1));
            manifest.add(code);
            List<CheckEntry> checks = e.checks == null ? List.of() : e.checks;
            if (checks.isEmpty()) {
                checkManifest.add(code + "-C001");
                sp.append("| ").append(code).append(" | ").append(safe(e.name)).append(" | ").append(code).append("-C001 |\n");
            } else {
                String codes = checks.stream().map(c -> c.checkCode).filter(c -> c != null && !c.isBlank()).reduce((a, b) -> a + "、" + b).orElse(code + "-C001");
                for (CheckEntry check : checks) {
                    if (check.checkCode != null && !check.checkCode.isBlank()) checkManifest.add(check.checkCode);
                }
                sp.append("| ").append(code).append(" | ").append(safe(e.name)).append(" | ").append(codes).append(" |\n");
            }
        }
        sp.append("\n");

        // ④ 规则明细：规则正文只出现一次，原子检查项附在对应规则末尾。
        sp.append("## 四、本章规则明细\n\n");
        for (int i = 0; i < sorted.size(); i++) {
            RuleEntry e = sorted.get(i);
            String code = (e.code != null && !e.code.isBlank()) ? e.code : ("R-AUTO-" + String.format("%03d", i + 1));
            sp.append("### [").append(code).append("] ").append(safe(e.name)).append("\n\n");
            String body = (e.checks != null && !e.checks.isEmpty()) ? bodyWithoutChecks(e.body) : e.body;
            if (body != null && !body.isBlank()) sp.append(body.trim()).append("\n\n");
            sp.append("**原子检查项**\n");
            if (e.checks == null || e.checks.isEmpty()) {
                sp.append("- [").append(code).append("-C001] 是否满足“").append(safe(e.name)).append("”规则要求？\n");
            } else {
                for (CheckEntry check : e.checks) {
                    if (check.checkCode == null || check.checkCode.isBlank()) continue;
                    sp.append("- [").append(check.checkCode).append("] ").append(safe(check.question)).append("\n");
                    if (check.passCriteria != null && !check.passCriteria.isBlank()) {
                        sp.append("  通过标准：").append(check.passCriteria.trim()).append("\n");
                    }
                }
            }
            if (i + 1 < sorted.size()) sp.append("\n---\n\n");
        }
        sp.append("\n");
        sp.append("本次注入的rule_code清单：").append(String.join(", ", manifest)).append("\n");
        sp.append("本次必须输出的check_code清单：").append(String.join(", ", checkManifest)).append("\n\n");

        // ⑤ 输出Schema：由代码生成，避免提示词模板和后端解析Schema漂移。
        sp.append("## 五、输出Schema（必须严格遵守）\n\n");
        sp.append("只能输出一个合法JSON对象，禁止输出解释文字、前后缀或Markdown代码围栏。\n");
        sp.append("输出必须符合以下JSON Schema：\n\n```json\n");
        sp.append(JSON.toJSONString(ReviewResultSchema.schema(), JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteMapNullValue));
        sp.append("\n```\n\n");
        sp.append("### 5.1 输出约束\n\n");
        sp.append("- `location`按“一级标题 > 二级标题 > 三级标题”填写，标题文本必须逐字使用输入原文；没有更深标题时使用用户消息中 `章节:` 后的一级标题。\n");
        sp.append("- `rule_code`和`check_code`必须逐字取自本提示词第三、四节清单，不得使用示例或自造编号。\n");
        sp.append("- `check_question`必须逐字使用第四节对应原子检查项中的问题。\n");
        sp.append("- `issues`只收录需要整改的Fail；`passed_items`只收录Pass；Review只出现在`check_results`。\n");
        sp.append("- `missing_items`无缺失时必须为`[]`；纯缺失时`evidence`必须为空字符串，reason必须说明实际检索范围。\n");
        sp.append("- `confidence=needs_review`用于需要人工确认的Review，不得用低置信度替代Review。\n\n");

        // ⑥ 编号自检：放在末尾，增强弱模型的完整覆盖率。
        sp.append("## 六、编号自检\n\n");
        sp.append("check_results必须严格覆盖本次所有原子检查项，每个编号只出现一次；不得遗漏、合并或新增。\n\n");
        for (int i = 0; i < checkManifest.size(); i++) {
            sp.append(i + 1).append(". `").append(checkManifest.get(i)).append("`\n");
        }
        sp.append("\n输出前确认：\n");
        sp.append("- check_results恰好").append(expected).append("条；\n");
        sp.append("- 每条check_code与rule_code正确对应；\n");
        sp.append("- 每个Fail原则上至少对应一条issue，每个Pass对应一条passed_item，Review不进入这两个数组；\n");
        sp.append("- 所有evidence可在输入中逐字搜索，纯缺失时已说明检索范围；\n");
        sp.append("- 最终响应只有JSON对象本身。\n");
        return sp.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }


    /**
     * 期望的 check_results 条数 = 每条规则至少 1 条；定义了原子检查项的按其数量计。
     * 同时用于末尾覆盖锚定文案与 schema 的 {@code minItems} 下限。
     */
    public static int expectedCheckCount(List<RuleEntry> entries) {
        if (entries == null) return 0;
        int n = 0;
        for (RuleEntry e : entries) {
            int c = (e.checks == null) ? 0 : e.checks.size();
            n += Math.max(1, c);
        }
        return n;
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
        String singleSchemaBlock = "## 五、输出Schema（必须严格遵守）";
        int schemaStart = basePrompt.indexOf(singleSchemaBlock);
        int fewShotStart = basePrompt.indexOf("## 六、编号自检");
        if (schemaStart < 0 || fewShotStart < 0 || fewShotStart <= schemaStart) {
            // 解析失败：直接退化为前缀 + batch 附录
            return basePrompt + "\n\n" + batchInputContract(chunkIds);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt, 0, schemaStart);
        sb.append("## 五、输出Schema（必须严格遵守 / 批量版本）\n");
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

