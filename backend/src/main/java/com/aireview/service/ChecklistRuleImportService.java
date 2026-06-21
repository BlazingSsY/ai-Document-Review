package com.aireview.service;

import com.aireview.dto.ChecklistImportResultDTO;
import com.aireview.dto.RuleDTO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 QTP 评估检查单 (Excel) 转成规范化的多原子检查 JSON，并写入 RAG 侧规则表。
 *
 * 之所以归 RAG：检查单的产物是 {@code rule_checks}（原子检查项），这是 RAG 管线的核心
 * 输入。chunk 管线不需要原子 check，所以 chunk 侧没有检查单导入功能。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChecklistRuleImportService {

    private final RagRuleService ragRuleService;
    private final SarRuleService sarRuleService;
    private final DataFormatter formatter = new DataFormatter();

    public ChecklistImportResultDTO importQtpChecklist(MultipartFile file, Long creatorId, Long libraryId) throws Exception {
        return importQtpChecklist(file, creatorId, libraryId, "RAG");
    }

    /** 检查单导入到指定管线（RAG / SAR）。两条管线都用原子检查项，故共用同一套 Excel 解析。 */
    public ChecklistImportResultDTO importQtpChecklist(MultipartFile file, Long creatorId,
                                                       Long libraryId, String mode) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Checklist file is required");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Checklist file name is required");
        }
        String lower = originalFilename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new IllegalArgumentException("Only Excel checklist files (.xlsx, .xls) are supported");
        }

        JSONObject pack;
        try (InputStream in = file.getInputStream(); Workbook workbook = WorkbookFactory.create(in)) {
            pack = buildCanonicalRulePack(originalFilename, workbook);
        }
        String canonicalJson = JSON.toJSONString(pack,
                JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteMapNullValue);
        String generatedName = stripExtension(originalFilename) + ".rules.json";
        List<RuleDTO> imported = "SAR".equalsIgnoreCase(mode)
                ? sarRuleService.importRuleContent(generatedName, canonicalJson, creatorId, libraryId, true)
                : ragRuleService.importRuleContent(generatedName, canonicalJson, creatorId, libraryId, true);

        ChecklistImportResultDTO dto = new ChecklistImportResultDTO();
        dto.setSourceFile(originalFilename);
        dto.setGeneratedRuleFile(generatedName);
        dto.setRuleCode(pack.getJSONArray("rules").getJSONObject(0).getString("rule_code"));
        dto.setRuleCount(imported.size());
        dto.setCheckCount(imported.stream()
                .mapToInt(r -> r.getChecks() == null ? 0 : r.getChecks().size())
                .sum());
        dto.setCanonicalJson(canonicalJson);
        dto.setImportedRules(imported);
        return dto;
    }

    private JSONObject buildCanonicalRulePack(String originalFilename, Workbook workbook) {
        List<String> sections = inferSections(originalFilename);
        String ruleCode = inferRuleCode(originalFilename, sections);
        String ruleName = inferRuleName(originalFilename, sections);

        JSONArray checks = new JSONArray();
        int order = 1;
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            order = extractSheetChecks(sheet, ruleCode, checks, seen, order);
        }
        if (checks.isEmpty()) {
            throw new IllegalArgumentException("No checklist items found in Excel file");
        }

        JSONObject appliesTo = new JSONObject();
        appliesTo.put("sections", sections);
        appliesTo.put("keywords", inferKeywords(originalFilename, checks));

        JSONObject rule = new JSONObject();
        rule.put("rule_code", ruleCode);
        rule.put("name", ruleName);
        rule.put("rule_type", sections.isEmpty() ? "global" : "section_specific");
        rule.put("document_type", "QTP");
        rule.put("description", "由QTP评估检查单自动导入的原子检查规则");
        rule.put("applies_to", appliesTo);
        rule.put("checks", checks);

        JSONArray rules = new JSONArray();
        rules.add(rule);

        JSONObject pack = new JSONObject();
        pack.put("version", "1.0");
        pack.put("source_type", "qtp_excel_checklist");
        pack.put("source_file", originalFilename);
        pack.put("rules", rules);
        return pack;
    }

    private int extractSheetChecks(Sheet sheet, String ruleCode, JSONArray checks,
                                   Set<String> seen, int startOrder) {
        int order = startOrder;
        String sectionTitle = "";
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            List<String> values = rowValues(sheet, row, 5);
            if (values.stream().allMatch(String::isBlank)) continue;
            if (isTitleOrHeaderRow(values)) continue;

            String only = onlyMeaningfulCell(values);
            if (only != null && !looksLikeChecklistItem(values)) {
                sectionTitle = only;
                continue;
            }

            Candidate candidate = toCandidate(values, sectionTitle);
            if (candidate == null || candidate.question().isBlank()) continue;

            String fingerprint = (candidate.item() + "|" + candidate.question()).toLowerCase(Locale.ROOT);
            if (!seen.add(fingerprint)) continue;

            JSONObject check = new JSONObject();
            check.put("check_code", ruleCode + "-" + String.format("%03d", order));
            check.put("check_type", inferCheckType(candidate.question()));
            check.put("question", buildQuestion(candidate));
            check.put("pass_criteria", buildPassCriteria(candidate));
            check.put("category", inferCategory(candidate));
            check.put("evidence_required", true);
            check.put("display_order", order);
            checks.add(check);
            order++;
        }
        return order;
    }

    private List<String> rowValues(Sheet sheet, Row row, int maxCols) {
        List<String> values = new ArrayList<>(maxCols);
        for (int c = 0; c < maxCols; c++) {
            values.add(cellText(sheet, row.getRowNum(), c));
        }
        return values;
    }

    private String cellText(Sheet sheet, int rowIndex, int colIndex) {
        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(colIndex);
        String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
        if (!value.isBlank()) return normalizeCellText(value);

        for (CellRangeAddress range : sheet.getMergedRegions()) {
            if (range.isInRange(rowIndex, colIndex)) {
                Row firstRow = sheet.getRow(range.getFirstRow());
                Cell firstCell = firstRow == null ? null : firstRow.getCell(range.getFirstColumn());
                String merged = firstCell == null ? "" : formatter.formatCellValue(firstCell).trim();
                return normalizeCellText(merged);
            }
        }
        return "";
    }

    private boolean isTitleOrHeaderRow(List<String> values) {
        String joined = String.join("|", values);
        if (joined.contains("序号") && joined.contains("检查项")) return true;
        if (joined.contains("试验项目") && joined.contains("检查内容")) return true;
        return values.get(0).contains("检查单") && values.stream().filter(s -> !s.isBlank()).count() == 1;
    }

    private boolean looksLikeChecklistItem(List<String> values) {
        return !values.get(1).isBlank() || !values.get(2).isBlank();
    }

    private String onlyMeaningfulCell(List<String> values) {
        String only = null;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (only != null && !only.equals(value)) return null;
            only = value;
        }
        return only;
    }

    private Candidate toCandidate(List<String> values, String sectionTitle) {
        String item = values.get(1);
        String target = values.get(2);
        String resultHint = values.get(3);
        String remark = values.get(4);
        if (target.isBlank() && item.isBlank()) return null;

        // General checklist rows: col B = item, col C = confirmation target.
        if (!item.isBlank() && !target.isBlank() && !target.equals(item)) {
            return new Candidate(sectionTitle, item, stripLeadingNumber(target), resultHint, remark);
        }

        // Rows with only an item name still represent required document content.
        if (!item.isBlank() && target.isBlank()) {
            return new Candidate(sectionTitle, item, "应明确" + item + "相关内容", resultHint, remark);
        }

        // Test implementation rows often have merged col B and detailed check content in col C.
        if (!target.isBlank()) {
            String inheritedItem = item.isBlank() ? sectionTitle : item;
            return new Candidate(sectionTitle, inheritedItem, stripLeadingNumber(target), resultHint, remark);
        }
        return null;
    }

    private String buildQuestion(Candidate c) {
        String q = c.question();
        if (q.endsWith("?") || q.endsWith("？")) return q;
        if (q.startsWith("是否") || q.startsWith("应")) return q;
        return "是否满足检查项“" + c.item() + "”： " + q;
    }

    private String buildPassCriteria(Candidate c) {
        StringBuilder sb = new StringBuilder();
        sb.append("QTP中应提供能够证明该检查项满足要求的明确内容。检查要求：").append(c.question());
        if (c.resultHint() != null && !c.resultHint().isBlank()) {
            sb.append("；检查结果应能填写或确认：").append(c.resultHint());
        }
        if (c.remark() != null && !c.remark().isBlank()) {
            sb.append("；备注要求：").append(c.remark());
        }
        return sb.toString();
    }

    private String inferCheckType(String question) {
        if (question.contains("是否")) return "presence";
        if (question.contains("一致")) return "consistency";
        if (question.contains("℃") || question.contains("数量") || question.contains("速率")) return "numeric";
        return "presence";
    }

    private String inferSeverity(Candidate c) {
        String text = c.item() + c.question();
        if (text.contains("鉴定等级") || text.contains("DO160") || text.contains("DO-160")
                || text.contains("试验参数") || text.contains("合格判据")
                || text.contains("工作高温") || text.contains("工作低温")) {
            return "high";
        }
        if (text.contains("试验资质") || text.contains("试验程序") || text.contains("功能性能")) {
            return "medium";
        }
        return "medium";
    }

    private String inferCategory(Candidate c) {
        String text = c.item() + c.question();
        if (text.contains("一致")) return "逻辑一致性";
        if (text.contains("DO160") || text.contains("DO-160") || text.contains("鉴定等级")) return "标准符合性";
        if (text.contains("术语")) return "术语一致性";
        return "完整性";
    }

    private List<String> inferSections(String fileName) {
        List<String> sections = new ArrayList<>();
        Matcher matcher = Pattern.compile("DO\\s*-?\\s*160G?\\s*-\\s*([0-9、,，]+)\\s*章",
                Pattern.CASE_INSENSITIVE).matcher(fileName);
        if (matcher.find()) {
            for (String part : matcher.group(1).split("[、,，]")) {
                String s = part.trim();
                if (!s.isEmpty() && !sections.contains(s)) sections.add(s);
            }
        }
        return sections;
    }

    private String inferRuleCode(String fileName, List<String> sections) {
        if (!sections.isEmpty()) {
            return "DO160G-" + String.join("-", sections) + "-QTP";
        }
        String base = stripExtension(fileName).replaceAll("[^A-Za-z0-9]+", "-");
        base = base.replaceAll("^-+|-+$", "");
        return (base.isBlank() ? "QTP-CHECKLIST" : base.toUpperCase(Locale.ROOT)) + "-QTP";
    }

    private String inferRuleName(String fileName, List<String> sections) {
        if (!sections.isEmpty()) {
            return "DO160G 第" + String.join("、", sections) + "章 QTP评估检查";
        }
        return stripExtension(fileName) + " QTP评估检查";
    }

    private List<String> inferKeywords(String fileName, JSONArray checks) {
        List<String> keywords = new ArrayList<>();
        addKeyword(keywords, "QTP");
        addKeyword(keywords, "DO160G");
        addKeyword(keywords, "DO-160G");

        String name = stripExtension(fileName);
        for (String token : List.of("温度变化", "温度", "湿热", "振动", "霉菌", "盐雾", "防水",
                "流体敏感性", "加速度", "风车", "电磁", "雷击", "静电")) {
            if (name.contains(token)) addKeyword(keywords, token);
        }
        for (Object item : checks) {
            if (!(item instanceof JSONObject obj)) continue;
            String q = obj.getString("question");
            if (q == null) continue;
            for (String token : List.of("温度变化", "鉴定试验", "环境鉴定等级", "试验程序", "功能性能", "合格判据")) {
                if (q.contains(token)) addKeyword(keywords, token);
            }
        }
        return keywords;
    }

    private void addKeyword(List<String> keywords, String token) {
        if (token != null && !token.isBlank() && !keywords.contains(token)) keywords.add(token);
    }

    private String stripLeadingNumber(String text) {
        if (text == null) return "";
        return text.replaceFirst("^\\s*\\d+[\\.、)]\\s*", "").trim();
    }

    private String normalizeCellText(String text) {
        if (text == null) return "";
        return text.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String stripExtension(String fileName) {
        if (fileName == null) return "checklist";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private record Candidate(String section, String item, String question, String resultHint, String remark) {
    }
}
