package com.aireview.util;

import com.aireview.entity.RagDocumentBlock;
import com.aireview.service.RagReviewService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

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

        RagReviewService service = new RagReviewService(
                null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "blockMaxChars", 40);
        List<RagDocumentBlock> blocks = ReflectionTestUtils.invokeMethod(
                service, "buildBlocks", "task-1", first);
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.stream().allMatch(block ->
                block.getStartNodeId() != null && block.getEndNodeId() != null));
        assertTrue(blocks.stream().allMatch(block -> "node_range".equals(block.getBlockType())));
    }

    private static void heading(XWPFDocument document, String style, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(style);
        paragraph.createRun().setText(text);
    }
}
