package com.aireview.document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps parsed Word content into the shared source contract:
 * structured JSON is the canonical representation, Markdown is used for review,
 * and HTML is used for browser rendering.
 */
public final class DocumentSourceMapper {

    private DocumentSourceMapper() {
    }

    public static Map<String, Object> toChapterSource(WordParser.Chapter chapter,
                                                       int chapterIndex,
                                                       String sourceId) {
        String plainText = Objects.toString(chapter.getPlainText(), "");
        Map<String, Object> source = baseSource(
                sourceId,
                "original_chapter",
                chapter.getTitle(),
                plainText,
                chapter.getContent(),
                chapter.getHtml(),
                chapter.getNodes());
        source.put("chapterIndex", chapterIndex);
        source.put("documentSourceId", chapter.getId());
        source.put("estimatedTokens", ChunkUtils.estimateTokens(chapter.getFullText()));
        return source;
    }

    public static Map<String, Object> toChunkSource(ChunkUtils.ChunkResult chunk,
                                                     int chunkIndex,
                                                     String sourceType) {
        WordParser.Chapter chapter = chunk.getSourceChapter();
        String plainText = chapter != null
                ? Objects.toString(chapter.getPlainText(), "")
                : Objects.toString(chunk.getContent(), "");
        String html = chapter != null ? Objects.toString(chapter.getHtml(), "") : "";
        List<WordParser.DocumentNode> nodes = chapter != null ? chapter.getNodes() : List.of();

        Map<String, Object> source = baseSource(
                "CHUNK-" + String.format("%03d", chunkIndex),
                sourceType,
                chunk.getLabel(),
                plainText,
                chunk.getContent(),
                html,
                nodes);
        source.put("chunk", chunkIndex);
        source.put("estimatedTokens", chunk.getEstimatedTokens());
        if (chapter != null) {
            source.put("chapterIndex", chunk.getChapterIndex() + 1);
            source.put("documentSourceId", chapter.getId());
            source.put("chapterTitle", chapter.getTitle());
        }
        return source;
    }

    public static List<Map<String, Object>> toStructuredNodes(List<WordParser.DocumentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        return nodes.stream().map(DocumentSourceMapper::toStructuredNode).toList();
    }

    private static Map<String, Object> baseSource(String sourceId,
                                                   String sourceType,
                                                   String title,
                                                   String plainText,
                                                   String reviewMarkdown,
                                                   String html,
                                                   List<WordParser.DocumentNode> nodes) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("sourceId", sourceId);
        source.put("blockId", sourceId);
        source.put("type", sourceType);
        source.put("title", Objects.toString(title, ""));
        source.put("sectionPath", Objects.toString(title, ""));
        source.put("text", Objects.toString(plainText, ""));
        source.put("markdown", Objects.toString(reviewMarkdown, ""));
        source.put("html", Objects.toString(html, ""));
        source.put("nodes", toStructuredNodes(nodes));
        source.put("contentFormat", "structured_json");
        source.put("reviewFormat", "markdown");
        source.put("displayFormat", "html");
        source.put("textLength", Objects.toString(plainText, "").length());
        return source;
    }

    private static Map<String, Object> toStructuredNode(WordParser.DocumentNode node) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", node.getId());
        item.put("type", node.getType());
        item.put("nodeIndex", node.getNodeIndex());
        item.put("headingLevel", node.getHeadingLevel());
        item.put("sectionPath", node.getSectionPath());
        item.put("text", node.getText());
        item.put("markdown", node.getReviewText());
        if (node.getTable() != null) {
            item.put("table", toStructuredTable(node.getTable()));
        }
        return item;
    }

    private static Map<String, Object> toStructuredTable(WordParser.TableData table) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", table.rowCount());
        result.put("columnCount", table.columnCount());
        result.put("rows", table.rows().stream().map(row -> {
            Map<String, Object> rowMap = new LinkedHashMap<>();
            rowMap.put("rowIndex", row.rowIndex());
            rowMap.put("cells", row.cells().stream().map(cell -> {
                Map<String, Object> cellMap = new LinkedHashMap<>();
                cellMap.put("rowIndex", cell.rowIndex());
                cellMap.put("columnIndex", cell.columnIndex());
                cellMap.put("text", cell.text());
                cellMap.put("header", cell.header());
                cellMap.put("rowSpan", cell.rowSpan());
                cellMap.put("colSpan", cell.colSpan());
                return cellMap;
            }).toList());
            return rowMap;
        }).toList());
        return result;
    }
}
