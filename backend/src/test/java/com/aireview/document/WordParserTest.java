package com.aireview.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesStableStructuredHtmlNodes() throws Exception {
        Path file = tempDir.resolve("structured.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            heading(document, "Heading1", "1 第一章");
            document.createParagraph().createRun().setText("正文 <script>alert('x')</script>");
            heading(document, "Heading2", "1.1 子节");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目");
            table.getRow(0).getCell(1).setText("要求");
            table.getRow(1).getCell(0).setText("温度");
            table.getRow(1).getCell(1).setText("-55℃");
            heading(document, "Heading1", "2 第二章");
            document.createParagraph().createRun().setText("第二章正文");
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        List<WordParser.Chapter> first = WordParser.parseChapters(file.toString());
        List<WordParser.Chapter> second = WordParser.parseChapters(file.toString());

        assertEquals(2, first.size());
        assertEquals("DOC-C001", first.get(0).getId());
        assertEquals(
                first.get(0).getNodes().stream().map(WordParser.DocumentNode::getId).toList(),
                second.get(0).getNodes().stream().map(WordParser.DocumentNode::getId).toList());
        assertTrue(first.get(0).getHtml().contains("data-node-id=\"DOC-C001-N0001\""));
        assertTrue(first.get(0).getHtml().contains("<table border=\"1\">"));
        assertTrue(first.get(0).getHtml().contains("&lt;script&gt;"));
        assertFalse(first.get(0).getHtml().contains("<script>"));
        assertTrue(first.get(0).getContent().contains("| 项目 | 要求 |"));
        assertTrue(first.get(0).getContent().contains("| --- | --- |"));
        assertFalse(first.get(0).getContent().contains("<th>"));
        assertTrue(first.get(0).getPlainText().contains("项目 | 要求"));
        assertTrue(first.get(0).getNodes().stream()
                .anyMatch(node -> "table".equals(node.getType()) && node.getText().contains("温度")));
        WordParser.DocumentNode tableNode = first.get(0).getNodes().stream()
                .filter(node -> "table".equals(node.getType()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, tableNode.getTable().rowCount());
        assertEquals(2, tableNode.getTable().columnCount());
        assertEquals("项目", tableNode.getTable().rows().get(0).cells().get(0).text());
        assertTrue(first.get(0).getNodes().stream()
                .anyMatch(node -> "1 第一章 > 1.1 子节".equals(node.getSectionPath())));

        List<ChunkUtils.ChunkResult> chunks = ChunkUtils.chunkByChapters(first, 10000);
        Map<String, Object> source = DocumentSourceMapper.toChunkSource(
                chunks.get(0), 1, "document_chunk");
        assertEquals("structured_json", source.get("contentFormat"));
        assertEquals("markdown", source.get("reviewFormat"));
        assertTrue(String.valueOf(source.get("html")).contains("<table border=\"1\">"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> structuredNodes =
                (List<Map<String, Object>>) source.get("nodes");
        @SuppressWarnings("unchecked")
        Map<String, Object> structuredTable = (Map<String, Object>) structuredNodes.stream()
                .filter(node -> "table".equals(node.get("type")))
                .findFirst()
                .orElseThrow()
                .get("table");
        assertEquals(2, structuredTable.get("rowCount"));
        assertEquals(2, structuredTable.get("columnCount"));
    }

    @Test
    void splitsByHeading1EvenWhenAHeading2PrecedesTheFirstChapter() throws Exception {
        Path file = tempDir.resolve("frontmatter-h2.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            // Front matter: a "list of figures" heading styled as Heading 2 that appears
            // before any Heading 1 — exactly the pattern that made first-heading-wins split
            // the whole document by H2.
            heading(document, "Heading2", "图目录");
            heading(document, "Heading1", "1 目的");
            document.createParagraph().createRun().setText("目的正文");
            heading(document, "Heading2", "1.1 子节");
            document.createParagraph().createRun().setText("子节正文");
            heading(document, "Heading1", "2 范围");
            document.createParagraph().createRun().setText("范围正文");
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        List<WordParser.Chapter> chapters = WordParser.parseChapters(file.toString());
        List<String> titles = chapters.stream().map(WordParser.Chapter::getTitle).toList();

        // Split must happen at H1: the H1 chapters are real boundaries...
        assertTrue(titles.contains("1 目的"), "expected an H1 chapter '1 目的', got " + titles);
        assertTrue(titles.contains("2 范围"), "expected an H1 chapter '2 范围', got " + titles);
        // ...while the H2 subsection stays inside its chapter, never a boundary.
        assertFalse(titles.contains("1.1 子节"),
                "H2 subsection must not become a chapter boundary, got " + titles);
    }

    @Test
    void verticallyMergedCellsKeepTheirValueInEveryCoveredRow() throws Exception {
        Path file = tempDir.resolve("merged.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            heading(document, "Heading1", "7 试验概述");
            // 表 7-2 的形态：第 1 列「试验标准章条」纵向合并覆盖两行，
            // 两行各自的条款细分号写在第 2 列。
            XWPFTable table = document.createTable(3, 3);
            table.getRow(0).getCell(0).setText("试验标准章条");
            table.getRow(0).getCell(1).setText("条款");
            table.getRow(0).getCell(2).setText("试验项目名称");
            table.getRow(1).getCell(0).setText("RTCA/DO-160G第16章");
            table.getRow(1).getCell(1).setText("第16.6.1.1条");
            table.getRow(1).getCell(2).setText("电压（直流平均值）");
            table.getRow(2).getCell(1).setText("第16.6.1.3条");
            table.getRow(2).getCell(2).setText("瞬时电源中断");
            vMergeRestart(table, 1, 0);
            vMergeContinue(table, 2, 0);
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        String content = WordParser.parseChapters(file.toString()).get(0).getContent();

        // 被合并覆盖的行必须带上合并值，否则审查模型读到空列会误判「标准章条缺失」。
        assertTrue(content.contains("| RTCA/DO-160G第16章 | 第16.6.1.3条 | 瞬时电源中断 |"),
                "merged chapter reference must repeat on the covered row, got:\n" + content);
    }

    @Test
    void sectionBreakParagraphDoesNotConsumeAnAppendixLetter() throws Exception {
        Path file = tempDir.resolve("appendix.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            appendixStyle(document);
            heading(document, "Heading1", "1 目的");
            document.createParagraph().createRun().setText("正文");
            appendixMarker(document, "检测记录表");
            // Word 在分节处留下的空段落，继承了附录标识样式但不是附录。
            sectionBreakOnlyParagraph(document);
            appendixMarker(document, "试验故障报告");
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        List<WordParser.Chapter> chapters = WordParser.parseChapters(file.toString());
        List<String> titles = chapters.stream().map(WordParser.Chapter::getTitle).toList();

        assertFalse(titles.stream().anyMatch(t -> t.contains("附录C")),
                "空的分节符段落不得占用一个附录字母，实际章节: " + titles);
    }

    private static void appendixStyle(XWPFDocument document) {
        CTStyle style = CTStyle.Factory.newInstance();
        style.setStyleId("AppendixMark");
        style.addNewName().setVal("附录标识5#");
        document.createStyles().addStyle(new XWPFStyle(style));
    }

    private static void appendixMarker(XWPFDocument document, String title) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("AppendixMark");
        paragraph.createRun().setText(title);
    }

    private static void sectionBreakOnlyParagraph(XWPFDocument document) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("AppendixMark");
        paragraph.getCTP().addNewPPr().addNewSectPr();
    }

    private static void heading(XWPFDocument document, String style, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(style);
        paragraph.createRun().setText(text);
    }

    private static void vMergeRestart(XWPFTable table, int row, int col) {
        vMerge(table, row, col, STMerge.RESTART);
    }

    private static void vMergeContinue(XWPFTable table, int row, int col) {
        vMerge(table, row, col, STMerge.CONTINUE);
    }

    private static void vMerge(XWPFTable table, int row, int col, STMerge.Enum value) {
        CTTcPr properties = table.getRow(row).getCell(col).getCTTc().addNewTcPr();
        properties.addNewVMerge().setVal(value);
    }
}
