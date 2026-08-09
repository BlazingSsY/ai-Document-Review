package com.aireview.user.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel 批量导入成员的结果。
 *
 * <p>逐行返回明细而不是只给一个成功数：名册表里手误几乎必然存在，操作者需要知道
 * 具体哪一行、为什么失败，才能改完重导。失败原因中不含身份证号——错误信息会被
 * 展示、复制、截图转发，敏感信息不应随之扩散。
 */
@Data
public class MemberImportResult {

    private int successCount;
    private int failureCount;
    private List<Row> succeeded = new ArrayList<>();
    private List<Row> failed = new ArrayList<>();

    @Data
    public static class Row {
        /** Excel 中的行号（含表头，从 1 开始），便于操作者直接定位。 */
        private int rowNumber;
        private String name;
        private String unitName;
        private String reason;
    }

    public void addSuccess(int rowNumber, String name, String unitName) {
        Row row = new Row();
        row.setRowNumber(rowNumber);
        row.setName(name);
        row.setUnitName(unitName);
        succeeded.add(row);
        successCount++;
    }

    public void addFailure(int rowNumber, String name, String reason) {
        Row row = new Row();
        row.setRowNumber(rowNumber);
        row.setName(name);
        row.setReason(reason);
        failed.add(row);
        failureCount++;
    }
}
