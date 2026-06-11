package com.aireview.service;

import com.aireview.review.ReviewResultSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把审查结果（aiResult JSON）转为 Excel / Word / 审计日志 JSON 的纯函数集合。
 *
 * 抽出来的动机：CHUNK 与 RAG 两条管线的 review_tasks 存储不同（review_tasks vs
 * rag_review_tasks），但导出逻辑只依赖 aiResult 这个 Map 的结构。把工具静态化让两边
 * 共享同一份导出实现，避免维护两套相同的 Excel / Docx 代码。
 */
public final class ReviewExportUtil {

    private ReviewExportUtil() {}

    public static byte[] toExcel(Map<String, Object> aiResult) throws IOException {
        if (aiResult == null) {
            throw new IllegalArgumentException("No review result available for export");
        }

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("审查意见");
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 12);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setVerticalAlignment(VerticalAlignment.TOP);
            dataStyle.setWrapText(true);
            dataStyle.setBorderBottom(BorderStyle.THIN);
            dataStyle.setBorderTop(BorderStyle.THIN);
            dataStyle.setBorderLeft(BorderStyle.THIN);
            dataStyle.setBorderRight(BorderStyle.THIN);

            Object allCheckResultsObj = aiResult.get("allCheckResults");
            if (allCheckResultsObj instanceof List<?> checkList && !checkList.isEmpty()) {
                Row headerRow = sheet.createRow(0);
                String[] headers = {"序号", "章节", "检查项编号", "规则编码", "判定", "检查项", "判定理由",
                        "证据", "缺失项", "建议", "置信度", "人工复核"};
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }
                int rowNum = 1;
                for (Object item : checkList) {
                    if (!(item instanceof Map<?, ?>)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> check = (Map<String, Object>) item;
                    writeCheckResultRow(sheet.createRow(rowNum), rowNum, check, dataStyle);
                    rowNum++;
                }
                int[] widths = {2000, 6000, 5000, 4000, 3000, 12000, 12000, 12000, 9000, 10000, 3000, 3000};
                for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i]);
                workbook.write(out);
                return out.toByteArray();
            }

            Row headerRow = sheet.createRow(0);
            String[] headers = {"序号", "章节", "问题分类", "规则编码", "审查意见", "判定依据", "是否接受"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            Object chunkResultsObj = aiResult.get("chunkResults");
            if (chunkResultsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> chunkResults = (List<Map<String, Object>>) chunkResultsObj;
                for (Map<String, Object> chunk : chunkResults) {
                    String chapterTitle = chunk.get("chapterTitle") != null
                            ? chunk.get("chapterTitle").toString() : "";
                    Object result = chunk.get("result");
                    if (result instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) result;
                        Object issuesObj = resultMap.get("issues");
                        if (issuesObj instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> issues = (List<Map<String, Object>>) issuesObj;
                            for (Map<String, Object> issue : issues) {
                                writeIssueRow(sheet.createRow(rowNum), rowNum, issue, chapterTitle, dataStyle);
                                rowNum++;
                            }
                        }
                    }
                }
            }
            if (rowNum == 1) {
                Object allIssuesObj = aiResult.get("allIssues");
                if (allIssuesObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> allIssues = (List<Map<String, Object>>) allIssuesObj;
                    for (Map<String, Object> issue : allIssues) {
                        writeIssueRow(sheet.createRow(rowNum), rowNum, issue, "", dataStyle);
                        rowNum++;
                    }
                }
            }

            sheet.setColumnWidth(0, 2000);
            sheet.setColumnWidth(1, 6000);
            sheet.setColumnWidth(2, 3600);
            sheet.setColumnWidth(3, 4000);
            sheet.setColumnWidth(4, 14000);
            sheet.setColumnWidth(5, 12000);
            sheet.setColumnWidth(6, 3000);

            workbook.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] toAuditJson(List<Map<String, Object>> auditLogs, ObjectMapper mapper) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(auditLogs);
    }

    public static byte[] toReportDocx(String fileName, String taskId, String selectedModel,
                                       String status, Map<String, Object> aiResult,
                                       List<Map<String, Object>> auditLogs) throws IOException {
        if (aiResult == null) {
            throw new IllegalArgumentException("No review result available for export");
        }
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addDocParagraph(document, "机载文档审查报告", true, 18);
            addDocParagraph(document, "文件名称：" + fileName, false, 11);
            addDocParagraph(document, "任务编号：" + taskId, false, 11);
            addDocParagraph(document, "审查模型：" + selectedModel, false, 11);
            addDocParagraph(document, "任务状态：" + status, false, 11);
            addDocParagraph(document, "生成时间：" + LocalDateTime.now(), false, 11);

            addDocParagraph(document, "审查概要", true, 14);
            addDocParagraph(document, "问题数：" + aiResult.getOrDefault("totalIssues", 0)
                    + "；检查项数：" + aiResult.getOrDefault("totalCheckResults", 0)
                    + "；失败切片：" + aiResult.getOrDefault("failedChunkCount", 0), false, 11);
            Object manualSummary = aiResult.get("manualReviewSummary");
            if (manualSummary != null) {
                addDocParagraph(document, "人工复核：" + manualSummary, false, 11);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> checks = aiResult.get("allCheckResults") instanceof List<?> list
                    ? (List<Map<String, Object>>) (List<?>) list
                    : List.of();
            addDocParagraph(document, "检查项判定矩阵", true, 14);
            if (checks.isEmpty()) {
                addDocParagraph(document, "当前任务没有检查项判定矩阵。", false, 11);
            } else {
                XWPFTable table = document.createTable(checks.size() + 1, 7);
                String[] headers = {"序号", "章节", "检查项", "系统判定", "人工判定", "理由", "建议"};
                XWPFTableRow header = table.getRow(0);
                for (int i = 0; i < headers.length; i++) {
                    header.getCell(i).setText(headers[i]);
                }
                for (int i = 0; i < checks.size(); i++) {
                    Map<String, Object> check = checks.get(i);
                    XWPFTableRow row = table.getRow(i + 1);
                    row.getCell(0).setText(String.valueOf(i + 1));
                    row.getCell(1).setText(firstNonBlank(strField(check, "sourceTitle"), strField(check, "location")));
                    row.getCell(2).setText(firstNonBlank(strField(check, "check_question"), strField(check, "question")));
                    row.getCell(3).setText(renderCheckStatus(strField(check, "status")));
                    row.getCell(4).setText(renderCheckStatus(strField(check, "manualStatus")));
                    row.getCell(5).setText(firstNonBlank(strField(check, "manualComment"), strField(check, "reason")));
                    row.getCell(6).setText(strField(check, "suggestion"));
                }
            }

            addDocParagraph(document, "审计日志", true, 14);
            if (auditLogs == null || auditLogs.isEmpty()) {
                addDocParagraph(document, "暂无人工复核审计记录。", false, 11);
            } else {
                for (Map<String, Object> logEntry : auditLogs) {
                    addDocParagraph(document,
                            logEntry.get("createdAt") + " | " + logEntry.get("action")
                                    + " | " + logEntry.get("targetId")
                                    + " | " + Objects.toString(logEntry.get("comment"), ""),
                            false, 10);
                }
            }

            document.write(out);
            return out.toByteArray();
        }
    }

    // ---------------- shared cell / field helpers ----------------

    private static void addDocParagraph(XWPFDocument document, String text, boolean bold, int fontSize) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(bold);
        run.setFontSize(fontSize);
        run.setText(text == null ? "" : text);
    }

    private static void writeIssueRow(Row row, int rowNum, Map<String, Object> issue,
                                       String fallbackChapterTitle, CellStyle dataStyle) {
        String location = strField(issue, "location");
        String description = firstNonBlank(strField(issue, "description"), strField(issue, "explanation"));
        String suggestion = strField(issue, "suggestion");
        String rule = strField(issue, "rule");
        String ruleCode = firstNonBlank(strField(issue, "rule_code"), strField(issue, "ruleCode"));
        String category = strField(issue, "category");
        String evidence = strField(issue, "evidence");

        String opinion = description;
        if (!suggestion.isEmpty()) opinion += (opinion.isEmpty() ? "" : "\n") + "建议：" + suggestion;
        String basis = rule;
        if (!evidence.isEmpty()) basis += (basis.isEmpty() ? "" : "\n") + "判定依据：" + evidence;

        cell(row, 0, String.valueOf(rowNum), dataStyle);
        cell(row, 1, location.isEmpty() ? fallbackChapterTitle : location, dataStyle);
        cell(row, 2, category, dataStyle);
        cell(row, 3, ruleCode, dataStyle);
        cell(row, 4, opinion, dataStyle);
        cell(row, 5, basis, dataStyle);
        cell(row, 6, "", dataStyle);
    }

    private static void writeCheckResultRow(Row row, int rowNum, Map<String, Object> check, CellStyle dataStyle) {
        String sourceTitle = firstNonBlank(strField(check, "sourceTitle"), strField(check, "location"));
        String checkCode = firstNonBlank(strField(check, "check_code"), strField(check, "checkCode"));
        String ruleCode = firstNonBlank(strField(check, "rule_code"), strField(check, "ruleCode"));
        String status = renderCheckStatus(strField(check, "status"));
        String question = firstNonBlank(strField(check, "check_question"), strField(check, "question"));
        String reason = strField(check, "reason");
        String evidence = strField(check, "evidence");
        String missing = renderListField(check.get("missing_items"));
        String suggestion = strField(check, "suggestion");
        String confidence = strField(check, "confidence");
        String manual = renderManualReview(check);

        cell(row, 0, String.valueOf(rowNum), dataStyle);
        cell(row, 1, sourceTitle, dataStyle);
        cell(row, 2, checkCode, dataStyle);
        cell(row, 3, ruleCode, dataStyle);
        cell(row, 4, status, dataStyle);
        cell(row, 5, question, dataStyle);
        cell(row, 6, reason, dataStyle);
        cell(row, 7, evidence, dataStyle);
        cell(row, 8, missing, dataStyle);
        cell(row, 9, suggestion, dataStyle);
        cell(row, 10, confidence, dataStyle);
        cell(row, 11, manual, dataStyle);
    }

    private static void cell(Row row, int idx, String value, CellStyle style) {
        Cell c = row.createCell(idx);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static String strField(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? (b == null ? "" : b) : a;
    }

    private static String renderListField(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString)
                    .reduce((a, b) -> a + "；" + b).orElse("");
        }
        return value == null ? "" : value.toString();
    }

    private static String renderManualReview(Map<String, Object> check) {
        String manualStatus = renderCheckStatus(strField(check, "manualStatus"));
        String accepted = "";
        if (check.containsKey("manualAccepted")) {
            accepted = Boolean.TRUE.equals(check.get("manualAccepted")) ? "接受系统意见" : "不接受系统意见";
        }
        String comment = strField(check, "manualComment");
        List<String> parts = new ArrayList<>();
        if (!manualStatus.isBlank()) parts.add("最终判定：" + manualStatus);
        if (!accepted.isBlank()) parts.add(accepted);
        if (!comment.isBlank()) parts.add("备注：" + comment);
        return String.join("\n", parts);
    }

    private static String renderCheckStatus(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return switch (raw.trim()) {
            case "Pass" -> "通过";
            case "Partial" -> "部分通过";
            case "Fail" -> "不通过";
            case "N/A" -> "不适用";
            case "Review" -> "待复核";
            default -> raw.trim();
        };
    }

    /**
     * Look up a check_result by code (+ optional source chunk) inside a flat list. Returns
     * null if no match. Used by manual-decision handlers when patching `allCheckResults`.
     */
    public static Map<String, Object> findCheckResult(List<Map<String, Object>> checks,
                                                       String checkCode, Integer sourceChunk) {
        for (Map<String, Object> check : checks) {
            String code = firstNonBlank(strField(check, "check_code"), strField(check, "checkCode"));
            if (!checkCode.equals(code)) continue;
            if (sourceChunk == null) return check;
            Object chunk = check.get("sourceChunk");
            if (chunk instanceof Number n && n.intValue() == sourceChunk) return check;
            if (chunk != null && String.valueOf(sourceChunk).equals(chunk.toString())) return check;
        }
        return null;
    }

    /**
     * Mirror a {@code allCheckResults[i]} change back into the matching chunkResults' nested check_results,
     * so re-serialized JSON stays consistent.
     */
    @SuppressWarnings("unchecked")
    public static void syncChunkCheckResult(Map<String, Object> aiResult, Map<String, Object> updated) {
        Object chunkResultsObj = aiResult.get("chunkResults");
        if (!(chunkResultsObj instanceof List<?> chunks)) return;
        String checkCode = firstNonBlank(strField(updated, "check_code"), strField(updated, "checkCode"));
        Object sourceChunk = updated.get("sourceChunk");
        for (Object chunkObj : chunks) {
            if (!(chunkObj instanceof Map<?, ?>)) continue;
            Map<String, Object> chunk = (Map<String, Object>) chunkObj;
            if (sourceChunk != null && !Objects.equals(String.valueOf(sourceChunk), String.valueOf(chunk.get("chunk")))) {
                continue;
            }
            Object resultObj = chunk.get("result");
            if (!(resultObj instanceof Map<?, ?>)) continue;
            Map<String, Object> result = (Map<String, Object>) resultObj;
            Object checksObj = result.get("check_results");
            if (!(checksObj instanceof List<?> checkList)) continue;
            for (Object item : checkList) {
                if (!(item instanceof Map<?, ?>)) continue;
                Map<String, Object> check = (Map<String, Object>) item;
                String candidate = firstNonBlank(strField(check, "check_code"), strField(check, "checkCode"));
                if (checkCode.equals(candidate)) {
                    check.putAll(updated);
                    return;
                }
            }
        }
    }

    public static Map<String, Object> buildManualReviewSummary(List<Map<String, Object>> checks) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int reviewed = 0;
        int accepted = 0;
        Map<String, Integer> finalStatusCounts = new LinkedHashMap<>();
        for (String s : ReviewResultSchema.CHECK_STATUS_ENUM) finalStatusCounts.put(s, 0);
        for (Map<String, Object> check : checks) {
            String manualStatus = strField(check, "manualStatus");
            if (!manualStatus.isBlank()) {
                reviewed++;
                String normalized = ReviewResultSchema.CHECK_STATUS_ENUM.contains(manualStatus.trim())
                        ? manualStatus.trim() : "Review";
                finalStatusCounts.merge(normalized, 1, Integer::sum);
            }
            if (Boolean.TRUE.equals(check.get("manualAccepted"))) {
                accepted++;
            }
        }
        summary.put("reviewed", reviewed);
        summary.put("accepted", accepted);
        summary.put("pending", Math.max(0, checks.size() - reviewed));
        summary.put("finalStatusCounts", finalStatusCounts);
        summary.put("updatedAt", LocalDateTime.now().toString());
        return summary;
    }
}
