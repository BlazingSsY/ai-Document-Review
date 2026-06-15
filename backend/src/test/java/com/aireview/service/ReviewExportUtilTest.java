package com.aireview.service;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewExportUtilTest {

    @Test
    void excelOmitsHighConfidenceNotApplicableChecks() throws Exception {
        Map<String, Object> hidden = check("C-001", "N/A", "high");
        Map<String, Object> visibleNotApplicable = check("C-002", "N/A", "medium");
        Map<String, Object> visibleFailure = check("C-003", "Fail", "high");
        Map<String, Object> aiResult = Map.of(
                "allCheckResults", List.of(hidden, visibleNotApplicable, visibleFailure));

        byte[] excel = ReviewExportUtil.toExcel(aiResult);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excel))) {
            Sheet sheet = workbook.getSheet("审查意见");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("C-002");
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("C-003");
        }
    }

    private Map<String, Object> check(String checkCode, String status, String confidence) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("check_code", checkCode);
        check.put("status", status);
        check.put("confidence", confidence);
        check.put("check_question", "检查项 " + checkCode);
        return check;
    }
}
