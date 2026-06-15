package com.aireview.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;

/**
 * Utility to parse Word documents (.doc and .docx) and extract text content.
 * Supports heading-based chapter splitting for .docx files.
 * Tables are converted to HTML format to preserve structure for AI review.
 * Images/drawings are skipped during parsing (placeholders added).
 */
@Slf4j
public class WordParser {

    private WordParser() {
    }

    /**
     * A stable structural node in the parsed Word document. Re-introduced so the RAG
     * pipeline ({@code RagReviewService}) can split chapters into node-aligned blocks and
     * render structured source provenance. The .docx parser populates these during its
     * body-element walk; the legacy/.doc path leaves them empty and callers fall back to
     * {@link Chapter#getContent()}.
     */
    public static class DocumentNode {
        private final String id;
        private final String type;          // heading | paragraph | table | figure | chapter_title
        private final int nodeIndex;
        private final int headingLevel;
        private final String sectionPath;
        private final String text;
        private final String reviewText;
        private final String html;
        private final TableData table;

        DocumentNode(String id, String type, int nodeIndex, int headingLevel,
                     String sectionPath, String text, String reviewText, String html,
                     TableData table) {
            this.id = id;
            this.type = type;
            this.nodeIndex = nodeIndex;
            this.headingLevel = headingLevel;
            this.sectionPath = sectionPath;
            this.text = text;
            this.reviewText = reviewText;
            this.html = html;
            this.table = table;
        }

        public String getId() { return id; }
        public String getType() { return type; }
        public int getNodeIndex() { return nodeIndex; }
        public int getHeadingLevel() { return headingLevel; }
        public String getSectionPath() { return sectionPath; }
        public String getText() { return text; }
        public String getReviewText() { return reviewText; }
        public String getHtml() { return html; }
        public TableData getTable() { return table; }
    }

    public record TableCellData(int rowIndex, int columnIndex, String text,
                                boolean header, int rowSpan, int colSpan) {
    }

    public record TableRowData(int rowIndex, List<TableCellData> cells) {
        public TableRowData {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    public record TableData(int rowCount, int columnCount, List<TableRowData> rows) {
        public TableData {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** Mutable draft collected during parsing, finalized into a {@link DocumentNode}. */
    private record NodeDraft(String type, int headingLevel, String sectionPath,
                             String text, String reviewText, String html, TableData table) {
    }

    private record TableRendering(String plainText, String markdown, String html,
                                  TableData table) {
    }

    /**
     * A document chapter identified by its heading-1 title and body text, plus
     * browser-ready structured HTML and structural nodes for the RAG pipeline.
     */
    public static class Chapter {
        private final String id;
        private final String title;
        private final String content;
        private final String plainText;
        private final String html;
        private final List<DocumentNode> nodes;

        /** Legacy constructor: plain title + body; HTML derived from content, no nodes. */
        public Chapter(String title, String content) {
            this("", title, content, content, buildLegacyHtml(content), List.of());
        }

        Chapter(String id, String title, String content, String plainText,
                String html, List<DocumentNode> nodes) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.plainText = plainText;
            this.html = html;
            this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        /** Markdown used as the model review input. */
        public String getContent() { return content; }
        public String getReviewMarkdown() { return content; }
        public String getPlainText() { return plainText; }
        public String getHtml() { return html; }
        public List<DocumentNode> getNodes() { return nodes; }

        /** Full text including the title line. */
        public String getFullText() {
            if (title == null || title.isBlank()) return content;
            return title + "\n\n" + content;
        }
    }

    private static Chapter createChapter(int chapterNumber, String title,
                                         List<NodeDraft> bodyNodes, int titleHeadingLevel) {
        String chapterId = String.format("DOC-C%03d", chapterNumber);
        String normalizedTitle = title == null ? "" : title.trim();
        List<NodeDraft> drafts = new ArrayList<>();
        if (!normalizedTitle.isBlank()) {
            drafts.add(new NodeDraft(
                    "chapter_title",
                    Math.max(1, Math.min(6, titleHeadingLevel)),
                    normalizedTitle,
                    normalizedTitle,
                    normalizedTitle,
                    null,
                    null));
        }
        drafts.addAll(bodyNodes);

        List<DocumentNode> nodes = new ArrayList<>(drafts.size());
        StringBuilder html = new StringBuilder();
        StringBuilder reviewMarkdown = new StringBuilder();
        StringBuilder plainText = new StringBuilder();
        for (int i = 0; i < drafts.size(); i++) {
            NodeDraft draft = drafts.get(i);
            String nodeId = chapterId + "-N" + String.format("%04d", i + 1);
            String nodeHtml = renderNodeHtml(nodeId, draft);
            nodes.add(new DocumentNode(nodeId, draft.type(), i + 1, draft.headingLevel(),
                    draft.sectionPath(), draft.text(), draft.reviewText(), nodeHtml, draft.table()));
            html.append(nodeHtml);
            if (!"chapter_title".equals(draft.type())
                    && draft.reviewText() != null
                    && !draft.reviewText().isBlank()) {
                if (reviewMarkdown.length() > 0) reviewMarkdown.append("\n\n");
                reviewMarkdown.append(draft.reviewText().trim());
            }
            if (!"chapter_title".equals(draft.type())
                    && draft.text() != null
                    && !draft.text().isBlank()) {
                if (plainText.length() > 0) plainText.append("\n\n");
                plainText.append(draft.text().trim());
            }
        }
        return new Chapter(chapterId, normalizedTitle, reviewMarkdown.toString().trim(),
                plainText.toString().trim(), html.toString(), nodes);
    }

    private static NodeDraft paragraphNode(String sectionPath, String text) {
        String normalized = text == null ? "" : text.trim();
        return new NodeDraft("paragraph", 0, sectionPath, normalized, normalized, null, null);
    }

    private static NodeDraft headingNode(String sectionPath, int headingLevel, String text) {
        String normalized = text == null ? "" : text.trim();
        int level = Math.max(1, Math.min(6, headingLevel));
        return new NodeDraft("heading", level, sectionPath, normalized,
                "#".repeat(level) + " " + normalized, null, null);
    }

    private static NodeDraft figureNode(String sectionPath, String text) {
        String normalized = text == null ? "[figure]" : text.trim();
        return new NodeDraft("figure", 0, sectionPath, normalized, normalized, null, null);
    }

    private static NodeDraft tableNode(String sectionPath, TableRendering table) {
        return new NodeDraft("table", 0, sectionPath, table.plainText(),
                table.markdown(), table.html(), table.table());
    }

    private static String buildSectionPath(String chapterTitle,
                                           NavigableMap<Integer, String> sectionHeadings) {
        List<String> parts = new ArrayList<>();
        if (chapterTitle != null && !chapterTitle.isBlank()) {
            parts.add(chapterTitle.trim());
        }
        for (String heading : sectionHeadings.values()) {
            if (heading != null && !heading.isBlank()
                    && (parts.isEmpty() || !parts.get(parts.size() - 1).equals(heading.trim()))) {
                parts.add(heading.trim());
            }
        }
        return String.join(" > ", parts);
    }

    private static String renderNodeHtml(String nodeId, NodeDraft draft) {
        String attributes = " id=\"" + nodeId + "\" data-node-id=\"" + nodeId
                + "\" data-node-type=\"" + draft.type() + "\"";
        if ("table".equals(draft.type())) {
            return "<div" + attributes + " class=\"doc-node doc-table\">"
                    + Objects.toString(draft.html(), "") + "</div>\n";
        }
        if ("chapter_title".equals(draft.type()) || "heading".equals(draft.type())) {
            int level = Math.max(1, Math.min(6, draft.headingLevel()));
            return "<h" + level + attributes + " class=\"doc-node doc-heading\">"
                    + escapeHtml(draft.text()) + "</h" + level + ">\n";
        }
        String cssClass = "figure".equals(draft.type())
                ? "doc-node doc-figure"
                : "doc-node doc-paragraph";
        return "<p" + attributes + " class=\"" + cssClass + "\">"
                + escapeHtml(draft.text()).replace("\n", "<br>") + "</p>\n";
    }

    /** Wrap plain/inline-HTML content into a minimal browser-ready HTML fragment. */
    private static String buildLegacyHtml(String content) {
        if (content == null || content.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith("<")) {
                sb.append(line).append("\n");           // pass raw HTML (e.g. tables) through
            } else {
                sb.append("<p>").append(escapeHtml(t)).append("</p>\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Parse a Word document and return its text content as a single string.
     */
    public static String parse(String filePath) throws IOException {
        List<Chapter> chapters = parseChapters(filePath);
        StringBuilder sb = new StringBuilder();
        for (Chapter ch : chapters) {
            sb.append(ch.getFullText()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Parse a Word document and split it into chapters by Heading 1.
     * For .docx files, uses adaptive heading detection from the document's style table.
     * For .doc files, returns the whole document as a single chapter.
     */
    public static List<Chapter> parseChapters(String filePath) throws IOException {
        Path path = Path.of(filePath);
        String fileName = path.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".docx")) {
            return parseDocxChapters(filePath);
        } else if (fileName.endsWith(".doc")) {
            return parseDocChapters(filePath);
        } else {
            throw new IllegalArgumentException("Unsupported file format: " + fileName
                    + ". Only .doc and .docx are supported.");
        }
    }

    /**
     * Parse .docx format, splitting by the top-level heading found in the document.
     *
     * Key design decisions:
     * 1. STYLE TABLE LOOKUP: Heading levels are resolved by looking up the paragraph's
     *    style ID in styles.xml (via XWPFStyles). This avoids the bug where style ID "3"
     *    would be misinterpreted as "level 3" — in many documents, style ID "3" is actually
     *    "heading 1". We look up the actual style name ("heading 1", "heading 2", etc.)
     *    to determine the correct heading level.
     *
     * 2. FIRST-HEADING-WINS split level: We split by the FIRST heading level found in
     *    document order. This handles documents with inverted hierarchy (e.g., chapters
     *    styled as H3 while sub-sections use H2 — an unusual but real pattern).
     *
     * 3. XWPFSDT FLATTENING: Content controls (XWPFSDT) are flattened before processing
     *    to prevent silent content loss.
     *
     * 4. NESTED TABLE TEXT: Cell text is extracted via getBodyElements() (not getText())
     *    to capture text in nested tables.
     *
     * 5. OLE/IMAGE MARKERS: Paragraphs containing only OLE objects or images (Visio
     *    diagrams, EMF charts, etc.) are replaced with a [图/表] placeholder rather than
     *    silently skipped, so the AI reviewer knows visual content exists at that point.
     */
    private static List<Chapter> parseDocxChapters(String filePath) throws IOException {
        log.info("Parsing .docx file with adaptive chapter detection: {}", filePath);
        List<Chapter> chapters = new ArrayList<>();

        try (InputStream is = new FileInputStream(filePath);
             XWPFDocument document = new XWPFDocument(is)) {

            // Flatten XWPFSDT (content controls) into the body element stream first.
            // Must be done before buildStyleHeadingMap so we can collect style IDs.
            List<IBodyElement> bodyElements = flattenBodyElements(document.getBodyElements());
            log.info("Body elements after SDT flattening: {}", bodyElements.size());

            // Build styleId → heading level map from the document's style table.
            // This is the authoritative source for heading levels — style IDs are arbitrary
            // integers (e.g., style "3" may be "heading 1") and must not be used directly.
            // We first collect unique style IDs from all paragraphs, then look up each one.
            Map<String, Integer> styleHeadingLevels = buildStyleHeadingMap(document, bodyElements);
            log.info("Style heading map from styles.xml: {}", styleHeadingLevels);

            // Resolve auto-generated numbering (e.g. "1.", "1.1", "(1)"). Word stores list
            // numbers in numbering.xml and renders them at display time; XWPFParagraph.getText()
            // strips them out, so we have to recompute and prepend them ourselves.
            NumberingFormatter numberingFormatter = new NumberingFormatter(document.getNumbering());

            // First pass: find the FIRST heading level that appears in the document.
            // This is the chapter-split boundary (top-level heading = chapter heading).
            int firstHeadingLevel = -1;
            List<String> detectedHeadingTexts = new ArrayList<>();
            for (IBodyElement element : bodyElements) {
                if (element instanceof XWPFParagraph p) {
                    int lvl = getHeadingLevel(p, styleHeadingLevels);
                    if (lvl > 0) {
                        if (firstHeadingLevel == -1) {
                            firstHeadingLevel = lvl;
                        }
                        String text = p.getText();
                        if (text != null && !text.isBlank()) {
                            detectedHeadingTexts.add("H" + lvl + ": " + text.trim());
                        }
                    }
                }
            }
            log.info("Detected {} heading(s). First heading level: H{}. Sample: {}",
                    detectedHeadingTexts.size(), firstHeadingLevel,
                    detectedHeadingTexts.stream().limit(20).toList());

            if (firstHeadingLevel == -1) {
                log.warn("No headings found in document, treating entire document as a single chapter.");
                List<NodeDraft> nodes = new ArrayList<>();
                for (IBodyElement element : bodyElements) {
                    if (element instanceof XWPFParagraph p) {
                        String t = withNumbering(p, numberingFormatter, document.getStyles());
                        if (t != null && !t.isBlank()) {
                            nodes.add(paragraphNode("", t));
                        } else if (hasDrawingOrPicture(p)) {
                            nodes.add(figureNode("", "[figure]"));
                        }
                    } else if (element instanceof XWPFTable tbl) {
                        nodes.add(tableNode("", convertTable(
                                tbl, numberingFormatter, document.getStyles())));
                    }
                }
                chapters.add(createChapter(1, "", nodes, 1));
                return chapters;
            }

            int splitLevel = firstHeadingLevel;
            log.info("Splitting document by H{} (first heading level = chapter boundary)", splitLevel);

            // Pre-scan for appendix markers to initialize level-0 counters.
            // This is ONLY needed for documents where appendix main titles do NOT use the numbering system.
            // If the appendix style defines numbering (numPr in style definition), the numbering formatter
            // will handle it automatically through style inheritance, and we should NOT manually set counters.
            Map<BigInteger, Integer> appendixNumIdToLetterIndex = scanForAppendixNumIds(document, bodyElements);

            // Check if the appendix marker style (120) defines its own numbering.
            // If it does, we should NOT use manual counter initialization because it would conflict.
            boolean appendixStyleHasOwnNumbering = false;
            XWPFStyles styles = document.getStyles();
            if (styles != null) {
                XWPFStyle style120 = styles.getStyle("120");
                if (style120 != null) {
                    try {
                        CTStyle ctStyle = style120.getCTStyle();
                        CTPPrGeneral pPr = ctStyle.getPPr();
                        if (pPr != null && pPr.getNumPr() != null && pPr.getNumPr().getNumId() != null) {
                            appendixStyleHasOwnNumbering = true;
                            log.info("Appendix marker style (120) defines its own numbering, skipping manual counter initialization");
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            if (!appendixStyleHasOwnNumbering && !appendixNumIdToLetterIndex.isEmpty()) {
                log.info("Detected appendix numbering IDs that need level-0 initialization: {}", appendixNumIdToLetterIndex);
            }

            // Current appendix letter index (1=A, 2=B, 3=C, etc.)
            // This is ONLY updated if we need manual initialization (appendix style has no numbering).
            int currentAppendixLetterIndex = 0;

            // Second pass: split document by the determined heading level.
            String currentTitle = null;
            List<NodeDraft> currentNodes = new ArrayList<>();
            NavigableMap<Integer, String> sectionHeadings = new TreeMap<>();
            int figureIndex = 0; // counter for unnamed figure placeholders

            for (IBodyElement element : bodyElements) {
                if (element instanceof XWPFParagraph paragraph) {
                    // Check if this paragraph is an appendix marker (style contains "附录标识")
                    // Only do manual counter initialization if the style doesn't define its own numbering.
                    // Otherwise, the numbering formatter handles it through style inheritance.
                    String paraStyleId = paragraph.getStyle();
                    if (!appendixStyleHasOwnNumbering && isAppendixMarkerStyle(paraStyleId, document)) {
                        currentAppendixLetterIndex++;
                        log.debug("Detected appendix marker, setting letter index to {}", currentAppendixLetterIndex);
                        // Initialize level-0 counters for all detected appendix numIds
                        for (Map.Entry<BigInteger, Integer> entry : appendixNumIdToLetterIndex.entrySet()) {
                            numberingFormatter.setCounter(entry.getKey(), 0, currentAppendixLetterIndex);
                            // Reset level-1 counter so subsections start fresh for each appendix
                            numberingFormatter.setCounter(entry.getKey(), 1, 0);
                        }
                    }

                    // Use getParagraphTextWithSpecialChars to handle <w:noBreakHyphen/> and other special chars
                    String rawText = getParagraphTextWithSpecialChars(paragraph);
                    // Reconstruct auto-numbering. This advances internal counters even when
                    // the paragraph has no visible text, so list numbering stays correct.
                    String numberPrefix = numberingFormatter.formatNumber(paragraph, document.getStyles());
                    String paraText = combinePrefixAndText(numberPrefix, rawText);
                    int headingLevel = getHeadingLevel(paragraph, styleHeadingLevels);

                    // Chapter boundary: save current chapter and start new one
                    if (headingLevel == splitLevel) {
                        if (currentTitle != null || !currentNodes.isEmpty()) {
                            chapters.add(createChapter(
                                    chapters.size() + 1,
                                    currentTitle != null ? currentTitle : "",
                                    currentNodes,
                                    splitLevel));
                        }
                        currentTitle = paraText != null ? paraText.trim() : "";
                        currentNodes = new ArrayList<>();
                        sectionHeadings.clear();
                        continue;
                    }

                    String sectionPath = buildSectionPath(currentTitle, sectionHeadings);

                    // OLE/image-only paragraph: add placeholder instead of silently skipping.
                    // This preserves the knowledge that visual content (Visio diagrams, EMF
                    // charts, etc.) exists at this point in the document.
                    // Note: Use paragraph.getText() for this check since it returns empty for special chars
                    if ((paragraph.getText() == null || paragraph.getText().isBlank()) && hasDrawingOrPicture(paragraph)) {
                        figureIndex++;
                        currentNodes.add(figureNode(sectionPath, "[图表 " + figureIndex + "]"));
                        continue;
                    }

                    // Non-split heading: format as Markdown heading using the CORRECT level
                    if (headingLevel > 0 && headingLevel <= 6 && paraText != null && !paraText.isBlank()) {
                        sectionHeadings.tailMap(headingLevel, true).clear();
                        sectionHeadings.put(headingLevel, paraText.trim());
                        sectionPath = buildSectionPath(currentTitle, sectionHeadings);
                        currentNodes.add(headingNode(sectionPath, headingLevel, paraText.trim()));
                        continue;
                    }

                    // Regular paragraph text
                    if (paraText != null && !paraText.isBlank()) {
                        currentNodes.add(paragraphNode(sectionPath, paraText));
                    }

                } else if (element instanceof XWPFTable table) {
                    String sectionPath = buildSectionPath(currentTitle, sectionHeadings);
                    currentNodes.add(tableNode(sectionPath, convertTable(
                            table, numberingFormatter, document.getStyles())));
                }
            }

            // Add the last chapter
            if (currentTitle != null || !currentNodes.isEmpty()) {
                chapters.add(createChapter(
                        chapters.size() + 1,
                        currentTitle != null ? currentTitle : "",
                        currentNodes,
                        splitLevel));
            }

            log.info("Parsed .docx file into {} chapter(s) (split by H{})", chapters.size(), splitLevel);
            return chapters;
        }
    }

    /**
     * Build a map from style ID → heading level by reading the document's styles table.
     *
     * In OOXML, style IDs are arbitrary strings (often numeric like "3", "2") that do NOT
     * correspond to heading levels. The actual heading level is stored in the style's name
     * (e.g., "heading 1", "heading 2", "标题1", "标题 1").
     *
     * Strategy:
     * 1. Collect the unique style IDs actually used in the document's paragraphs.
     * 2. For each used style ID, look up the XWPFStyle via XWPFStyles.getStyle(id).
     * 3. Match the style's name against known heading patterns to determine the level.
     * 4. Also check the style's own outline-level property as a fallback.
     *
     * Note: XWPFStyles does not expose a getStyleList() method in POI 5.2.5.
     * We therefore look up only the styles that are actually referenced by paragraphs.
     *
     * @param document     the open XWPFDocument
     * @param bodyElements the already-flattened list of body elements (to collect style IDs)
     */
    private static Map<String, Integer> buildStyleHeadingMap(XWPFDocument document,
                                                              List<IBodyElement> bodyElements) {
        Map<String, Integer> map = new HashMap<>();
        try {
            XWPFStyles styles = document.getStyles();
            if (styles == null) return map;

            // Collect unique style IDs used by all paragraphs in the body
            Set<String> usedStyleIds = new HashSet<>();
            collectStyleIds(bodyElements, usedStyleIds);

            // For each used style ID, resolve its heading level
            for (String styleId : usedStyleIds) {
                XWPFStyle style = styles.getStyle(styleId);
                if (style == null) continue;

                String name = style.getName();
                if (name != null) {
                    String nameLower = name.toLowerCase().trim();
                    // Match standard heading names: "heading 1"-"heading 6",
                    // "heading1"-"heading6", "标题1"-"标题6", "标题 1"-"标题 6"
                    boolean matched = false;
                    for (int i = 1; i <= 6; i++) {
                        if (nameLower.equals("heading " + i)
                                || nameLower.equals("heading" + i)
                                || nameLower.equals("标题" + i)
                                || nameLower.equals("标题 " + i)) {
                            map.put(styleId, i);
                            matched = true;
                            break;
                        }
                    }
                    if (matched) continue;

                    // Pattern match for non-standard names starting with "heading"/"标题"
                    if (nameLower.startsWith("heading") || nameLower.startsWith("标题")) {
                        String numStr = nameLower.replaceAll("[^0-9]", "");
                        if (!numStr.isEmpty()) {
                            try {
                                int lvl = Integer.parseInt(numStr);
                                if (lvl >= 1 && lvl <= 6) {
                                    map.put(styleId, lvl);
                                    continue;
                                }
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                }

                // Fallback: check outline level defined in the style itself
                if (!map.containsKey(styleId)) {
                    try {
                        var stylePPr = style.getCTStyle().getPPr();
                        if (stylePPr != null && stylePPr.getOutlineLvl() != null) {
                            int outlineLvl = stylePPr.getOutlineLvl().getVal().intValue();
                            if (outlineLvl >= 0 && outlineLvl <= 5) {
                                map.put(styleId, outlineLvl + 1); // 0-based → 1-based
                            }
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build style heading map: {}", e.getMessage());
        }
        return map;
    }

    /**
     * Recursively collect all style IDs used by paragraphs within body elements,
     * including paragraphs nested inside table cells.
     */
    private static void collectStyleIds(List<IBodyElement> elements, Set<String> result) {
        for (IBodyElement el : elements) {
            if (el instanceof XWPFParagraph p) {
                String styleId = p.getStyle();
                if (styleId != null) result.add(styleId);
            } else if (el instanceof XWPFTable table) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        collectStyleIds(cell.getBodyElements(), result);
                    }
                }
            }
        }
    }

    /**
     * Flatten body elements: recursively replace XWPFSDT (Structured Document Tags /
     * content controls) with their inner body elements. This prevents silent content
     * loss for documents that use content controls to structure their chapters or sections.
     */
    private static List<IBodyElement> flattenBodyElements(List<IBodyElement> elements) {
        List<IBodyElement> result = new ArrayList<>();
        for (IBodyElement el : elements) {
            if (el instanceof XWPFSDT sdt) {
                try {
                    ISDTContent content = sdt.getContent();
                    if (content instanceof IBody body) {
                        List<IBodyElement> inner = body.getBodyElements();
                        if (inner != null && !inner.isEmpty()) {
                            result.addAll(flattenBodyElements(inner));
                            continue;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not flatten SDT element: {}", e.getMessage());
                }
                continue; // skip SDT if content is inaccessible
            }
            result.add(el);
        }
        return result;
    }

    /**
     * Convert a Word table to HTML, preserving row/column structure, merged cells
     * (rowspan/colspan), and any auto-numbering inside cells (e.g. the leading
     * "1.", "2." of a 序号 column that Word renders from numbering.xml).
     *
     * <p>Merge handling:
     * <ul>
     *   <li>Horizontal merge (gridSpan) → emitted as {@code colspan="N"}.</li>
     *   <li>Vertical merge (vMerge): the first cell with vMerge="restart" emits
     *       {@code rowspan="N"} where N counts subsequent rows whose corresponding
     *       cell has vMerge="continue"; continuation cells themselves are NOT
     *       emitted into the HTML, since their content is the merged cell's text.
     *       The continuation cell's paragraphs are still walked so the numbering
     *       formatter's counters stay correct.</li>
     * </ul>
     *
     * @param table     the Word table to convert
     * @param formatter shared numbering formatter (so cell auto-numbers stay in sync
     *                  with body counters); may be {@code null} for nested calls
     * @param styles    the document styles (for style-based numbering lookup)
     */
    private static TableRendering convertTable(XWPFTable table, NumberingFormatter formatter,
                                                XWPFStyles styles) {
        StringBuilder html = new StringBuilder();
        html.append("<table border=\"1\">\n");
        List<TableRowData> structuredRows = new ArrayList<>();
        int columnCount = 0;

        List<XWPFTableRow> rows = table.getRows();
        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
            XWPFTableRow row = rows.get(rowIdx);
            html.append("  <tr>\n");
            String cellTag = (rowIdx == 0) ? "th" : "td";
            List<TableCellData> structuredCells = new ArrayList<>();

            List<XWPFTableCell> cells = row.getTableCells();
            int logicalCol = 0;
            for (int cellIdx = 0; cellIdx < cells.size(); cellIdx++) {
                XWPFTableCell cell = cells.get(cellIdx);
                int gridSpan = getGridSpan(cell);
                STMerge.Enum vMergeState = getVMergeState(cell);

                if (vMergeState == STMerge.CONTINUE) {
                    // Continuation cell: the rowspan from the matching restart cell already
                    // covers this slot, so don't emit a <td>. Still walk the cell's paragraphs
                    // to keep the numbering formatter's counters aligned with document order.
                    extractCellText(cell, formatter, styles);
                    logicalCol += gridSpan;
                    continue;
                }

                int rowSpan = 1;
                if (vMergeState == STMerge.RESTART) {
                    rowSpan = computeRowSpan(rows, rowIdx, logicalCol);
                }

                String cellText = extractCellText(cell, formatter, styles);
                html.append("    <").append(cellTag);
                if (rowSpan > 1) html.append(" rowspan=\"").append(rowSpan).append("\"");
                if (gridSpan > 1) html.append(" colspan=\"").append(gridSpan).append("\"");
                html.append(">");
                html.append(escapeHtml(cellText));
                html.append("</").append(cellTag).append(">\n");
                structuredCells.add(new TableCellData(
                        rowIdx + 1,
                        logicalCol + 1,
                        cellText,
                        rowIdx == 0,
                        rowSpan,
                        gridSpan));

                logicalCol += gridSpan;
            }
            html.append("  </tr>\n");
            columnCount = Math.max(columnCount, logicalCol);
            structuredRows.add(new TableRowData(rowIdx + 1, structuredCells));
        }

        html.append("</table>");
        TableData tableData = new TableData(rows.size(), columnCount, structuredRows);
        return new TableRendering(
                renderTablePlainText(tableData),
                renderTableMarkdown(tableData),
                html.toString(),
                tableData);
    }

    private static String renderTablePlainText(TableData table) {
        List<String> lines = new ArrayList<>();
        for (List<String> row : expandTableGrid(table)) {
            lines.add(String.join(" | ", row));
        }
        return String.join("\n", lines).trim();
    }

    private static String renderTableMarkdown(TableData table) {
        List<List<String>> grid = expandTableGrid(table);
        if (grid.isEmpty() || table.columnCount() <= 0) return "";

        List<String> lines = new ArrayList<>();
        lines.add(markdownRow(grid.get(0)));
        lines.add("| " + String.join(" | ",
                Collections.nCopies(table.columnCount(), "---")) + " |");
        for (int i = 1; i < grid.size(); i++) {
            lines.add(markdownRow(grid.get(i)));
        }
        return String.join("\n", lines);
    }

    private static List<List<String>> expandTableGrid(TableData table) {
        if (table == null || table.rowCount() <= 0 || table.columnCount() <= 0) {
            return List.of();
        }
        List<List<String>> grid = new ArrayList<>();
        for (int row = 0; row < table.rowCount(); row++) {
            grid.add(new ArrayList<>(Collections.nCopies(table.columnCount(), "")));
        }
        for (TableRowData row : table.rows()) {
            for (TableCellData cell : row.cells()) {
                int startRow = Math.max(0, cell.rowIndex() - 1);
                int startCol = Math.max(0, cell.columnIndex() - 1);
                if (startRow >= grid.size() || startCol >= table.columnCount()) continue;
                grid.get(startRow).set(startCol, Objects.toString(cell.text(), ""));
                for (int r = startRow; r < Math.min(grid.size(), startRow + cell.rowSpan()); r++) {
                    for (int c = startCol; c < Math.min(table.columnCount(), startCol + cell.colSpan()); c++) {
                        if (r == startRow && c == startCol) continue;
                        grid.get(r).set(c, "");
                    }
                }
            }
        }
        return grid;
    }

    private static String markdownRow(List<String> cells) {
        return "| " + cells.stream()
                .map(WordParser::escapeMarkdownCell)
                .reduce((left, right) -> left + " | " + right)
                .orElse("") + " |";
    }

    private static String escapeMarkdownCell(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r\n", "<br>")
                .replace("\n", "<br>")
                .replace("\r", "<br>");
    }

    /** Number of grid columns spanned by this cell (default 1). */
    private static int getGridSpan(XWPFTableCell cell) {
        try {
            CTTc tc = cell.getCTTc();
            if (tc == null) return 1;
            CTTcPr tcPr = tc.getTcPr();
            if (tcPr == null || tcPr.getGridSpan() == null
                    || tcPr.getGridSpan().getVal() == null) {
                return 1;
            }
            int span = tcPr.getGridSpan().getVal().intValue();
            return span > 0 ? span : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Vertical-merge state of this cell: {@code RESTART} (first cell of merge),
     * {@code CONTINUE} (subsequent merged cell, no visible content), or {@code null}
     * (cell is not part of any vertical merge).
     *
     * <p>Per OOXML, a {@code <w:vMerge/>} element with no {@code val} attribute is a
     * continuation; only an explicit {@code val="restart"} starts a new merge.
     */
    private static STMerge.Enum getVMergeState(XWPFTableCell cell) {
        try {
            CTTc tc = cell.getCTTc();
            if (tc == null) return null;
            CTTcPr tcPr = tc.getTcPr();
            if (tcPr == null) return null;
            CTVMerge vMerge = tcPr.getVMerge();
            if (vMerge == null) return null;
            STMerge.Enum val = vMerge.getVal();
            return val != null ? val : STMerge.CONTINUE;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * For a vMerge="restart" cell at {@code startLogicalCol} in row {@code startRowIdx},
     * count how many subsequent rows have a matching vMerge="continue" cell aligned to
     * the same logical column. Returns the total rowspan (>= 1).
     */
    private static int computeRowSpan(List<XWPFTableRow> rows, int startRowIdx, int startLogicalCol) {
        int rowSpan = 1;
        for (int r = startRowIdx + 1; r < rows.size(); r++) {
            List<XWPFTableCell> rowCells = rows.get(r).getTableCells();
            int col = 0;
            XWPFTableCell match = null;
            for (XWPFTableCell c : rowCells) {
                int span = getGridSpan(c);
                if (col == startLogicalCol) {
                    match = c;
                    break;
                }
                col += span;
            }
            if (match == null) break;
            STMerge.Enum state = getVMergeState(match);
            if (state == STMerge.CONTINUE) {
                rowSpan++;
            } else {
                break;
            }
        }
        return rowSpan;
    }

    /**
     * Recursively extract all text from a table cell, including text in nested tables
     * and any auto-numbering rendered by Word from numbering.xml.
     *
     * <p>Uses {@code getBodyElements()} so nested {@link XWPFTable} objects are included,
     * unlike {@code getText()} which only scans the cell's direct paragraph list and misses
     * nested tables. When {@code formatter} is non-null, paragraph text is prefixed with the
     * computed number (e.g. "1.", "1.1", "(1)") that Word would otherwise render at display
     * time. Without this, columns like 序号 that rely on auto-numbering come out empty.
     */
    private static String extractCellText(XWPFTableCell cell, NumberingFormatter formatter, XWPFStyles styles) {
        StringBuilder sb = new StringBuilder();
        try {
            for (IBodyElement el : cell.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    // Use getParagraphTextWithSpecialChars to handle <w:noBreakHyphen/>
                    String t = (formatter != null) ? withNumbering(p, formatter, styles) : getParagraphTextWithSpecialChars(p);
                    if (t != null && !t.isBlank()) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(t.trim());
                    }
                } else if (el instanceof XWPFTable nestedTable) {
                    for (XWPFTableRow nRow : nestedTable.getRows()) {
                        StringBuilder rowSb = new StringBuilder();
                        for (XWPFTableCell nCell : nRow.getTableCells()) {
                            if (rowSb.length() > 0) rowSb.append(" | ");
                            rowSb.append(extractCellText(nCell, formatter, styles));
                        }
                        if (rowSb.length() > 0) {
                            if (sb.length() > 0) sb.append("; ");
                            sb.append(rowSb);
                        }
                    }
                }
            }
        } catch (Exception e) {
            String fallback = cell.getText();
            if (fallback != null) return fallback.trim();
        }
        return sb.toString().trim();
    }

    /**
     * Escape special HTML characters.
     */
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    /**
     * Get the heading level of a paragraph (1-6), or -1 if not a heading.
     *
     * Uses the pre-built styleHeadingLevels map (from buildStyleHeadingMap) as the
     * primary source. Falls back to checking the paragraph's own outline level property
     * for documents without a standard style table.
     *
     * @param paragraph         the paragraph to check
     * @param styleHeadingLevels map of styleId → heading level, built from styles.xml
     */
    private static int getHeadingLevel(XWPFParagraph paragraph, Map<String, Integer> styleHeadingLevels) {
        String styleId = paragraph.getStyle();
        if (styleId != null) {
            // Primary: look up in pre-built style table map
            Integer level = styleHeadingLevels.get(styleId);
            if (level != null) {
                return level;
            }

            // Fallback: try pattern matching on the style ID itself for documents
            // that use human-readable style IDs like "Heading1", "标题1", etc.
            String lower = styleId.toLowerCase().replace(" ", "");
            for (int i = 1; i <= 6; i++) {
                if (lower.equals("heading" + i) || lower.equals("标题" + i)) {
                    return i;
                }
            }
            if (lower.startsWith("heading") || lower.startsWith("标题")) {
                String numStr = lower.replaceAll("[^0-9]", "");
                if (!numStr.isEmpty()) {
                    try {
                        int lvl = Integer.parseInt(numStr);
                        if (lvl >= 1 && lvl <= 6) return lvl;
                    } catch (NumberFormatException ignored) { }
                }
            }
        }

        // Last resort: check outline level directly on the paragraph
        try {
            var pPr = paragraph.getCTP().getPPr();
            if (pPr != null && pPr.getOutlineLvl() != null) {
                int level = pPr.getOutlineLvl().getVal().intValue();
                if (level >= 0 && level < 6) {
                    return level + 1;
                }
            }
        } catch (Exception e) {
            // ignore
        }

        return -1;
    }

    /**
     * Pre-scan the document to find numIds that are used for appendix subsection numbering.
     *
     * In some documents, appendix subsections (e.g., "A.1 试验故障报告单", "B.1 试验前检测记录表")
     * use a numbering definition where:
     * - Level 0: format=upperLetter, lvlText="附录%1" (should produce "附录A", "附录B")
     * - Level 1: format=decimal, lvlText="%1.%2" (should produce "A.1", "B.1")
     *
     * However, the appendix main titles ("附录A 检测记录表", "附录B 试验故障报告") may not
     * use the numbering system at all (numId=null), so level 0's counter never gets initialized.
     * This causes subsections to show "0.1" instead of "A.1".
     *
     * This method scans for paragraphs at level 1 that use lvlText containing "%1" where
     * level 0 uses upperLetter format, and returns their numIds so we can manually
     * initialize the level-0 counter based on document structure.
     *
     * @param document the XWPFDocument
     * @param bodyElements the flattened body elements
     * @return a map of numId → any non-zero value (placeholder), indicating these numIds need appendix letter initialization
     */
    private static Map<BigInteger, Integer> scanForAppendixNumIds(XWPFDocument document, List<IBodyElement> bodyElements) {
        Map<BigInteger, Integer> result = new HashMap<>();
        XWPFNumbering numbering = document.getNumbering();
        if (numbering == null) return result;

        for (IBodyElement el : bodyElements) {
            if (!(el instanceof XWPFParagraph p)) continue;

            BigInteger numId = p.getNumID();
            BigInteger ilvl = p.getNumIlvl();
            if (numId == null || numId.signum() == 0) continue;

            // Only interested in level 1 paragraphs (where %1 references level 0)
            int level = (ilvl != null) ? ilvl.intValue() : 0;
            if (level != 1) continue;

            try {
                XWPFNum num = numbering.getNum(numId);
                if (num == null) continue;
                BigInteger absNumId = num.getCTNum().getAbstractNumId().getVal();
                XWPFAbstractNum absNum = numbering.getAbstractNum(absNumId);
                if (absNum == null) continue;
                CTAbstractNum ctAbsNum = absNum.getCTAbstractNum();
                List<CTLvl> lvlList = ctAbsNum.getLvlList();
                if (lvlList == null || lvlList.size() < 2) continue;

                // Check level 0: must use upperLetter format and lvlText containing "附录"
                CTLvl lvl0 = lvlList.get(0);
                String fmt0 = lvl0.getNumFmt() != null ? lvl0.getNumFmt().getVal().toString() : "decimal";
                String txt0 = lvl0.getLvlText() != null ? lvl0.getLvlText().getVal() : "";

                // Check level 1: lvlText must contain "%1" (reference to level 0)
                CTLvl lvl1 = lvlList.get(1);
                String txt1 = lvl1.getLvlText() != null ? lvl1.getLvlText().getVal() : "";

                if ("upperLetter".equalsIgnoreCase(fmt0) &&
                    txt0.contains("附录") &&
                    txt1.contains("%1")) {
                    // This numId needs appendix letter initialization
                    result.put(numId, 1);
                }
            } catch (Exception e) {
                // ignore
            }
        }

        return result;
    }

    /**
     * Check if a style is an "appendix marker" style - a paragraph that marks the start
     * of a new appendix (e.g., "附录A 检测记录表", "附录B 试验故障报告").
     *
     * These paragraphs typically have style names containing "附录标识" or similar.
     * They are used to visually separate appendices but may not use the numbering system.
     *
     * @param styleId the style ID of the paragraph
     * @param document the XWPFDocument to look up style names
     * @return true if this style indicates an appendix marker
     */
    private static boolean isAppendixMarkerStyle(String styleId, XWPFDocument document) {
        if (styleId == null) return false;

        XWPFStyles styles = document.getStyles();
        if (styles == null) return false;

        XWPFStyle style = styles.getStyle(styleId);
        if (style == null || style.getName() == null) return false;

        String name = style.getName().toLowerCase();
        // Match styles like "附录标识", "附录标识5#", etc.
        return name.contains("附录标识");
    }

    /**
     * Check if a paragraph contains only drawings or pictures (no meaningful text).
     * Also detects embedded OLE objects (Visio, Excel, etc.) via w:pict elements.
     */
    private static boolean hasDrawingOrPicture(XWPFParagraph paragraph) {
        try {
            if (!paragraph.getRuns().isEmpty()) {
                for (XWPFRun run : paragraph.getRuns()) {
                    if (!run.getEmbeddedPictures().isEmpty()) {
                        return true;
                    }
                }
            }
            String xml = paragraph.getCTP().xmlText();
            return xml.contains("<w:drawing") || xml.contains("<w:pict") || xml.contains("<w:object");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Prepend any auto-numbering text to the raw paragraph text, with a single space
     * separator. Returns the raw text unchanged if there is no numbering.
     */
    private static String combinePrefixAndText(String prefix, String rawText) {
        boolean hasPrefix = prefix != null && !prefix.isBlank();
        boolean hasText = rawText != null && !rawText.isBlank();
        if (hasPrefix && hasText) return prefix + " " + rawText;
        if (hasPrefix) return prefix;
        return rawText;
    }

    /**
     * Convenience for the no-headings code path: get the paragraph text with auto-numbering
     * prepended. Always advances the formatter's counters, even if the paragraph is empty.
     */
    private static String withNumbering(XWPFParagraph paragraph, NumberingFormatter formatter, XWPFStyles styles) {
        String prefix = formatter.formatNumber(paragraph, styles);
        // Use getParagraphTextWithSpecialChars to handle <w:noBreakHyphen/> and other special chars
        String rawText = getParagraphTextWithSpecialChars(paragraph);
        return combinePrefixAndText(prefix, rawText);
    }

    /**
     * Get paragraph text while preserving special characters that POI's getText() ignores.
     *
     * POI's XWPFParagraph.getText() silently skips certain special XML elements:
     * - <w:noBreakHyphen/> (non-breaking hyphen, U+2011) - should render as '-'
     * - <w:softHyphen/> (soft hyphen, U+00AD) - should render as hyphen when visible
     *
     * This method reconstructs the text by examining the raw XML and inserting these
     * characters where they appear in the document structure.
     *
     * @param paragraph the paragraph to extract text from
     * @return the text with special characters properly rendered
     */
    private static String getParagraphTextWithSpecialChars(XWPFParagraph paragraph) {
        // First get the standard text from POI
        String standardText = paragraph.getText();
        if (standardText == null) return null;

        // Check if the paragraph XML contains noBreakHyphen or softHyphen elements
        try {
            String xml = paragraph.getCTP().xmlText();
            if (!xml.contains("<w:noBreakHyphen") && !xml.contains("<w:softHyphen")) {
                // No special hyphens, return standard text
                return standardText;
            }

            // Reconstruct text by walking through runs and inserting hyphens
            StringBuilder sb = new StringBuilder();
            for (XWPFRun run : paragraph.getRuns()) {
                String runText = run.getText(0);
                if (runText != null) {
                    sb.append(runText);
                }
                // Check if this run is followed by a noBreakHyphen in the XML
                // by examining the run's CTR XML
                try {
                    String runXml = run.getCTR().xmlText();
                    // Check if the run element itself is a noBreakHyphen
                    if (runXml.contains("<w:noBreakHyphen") || runXml.equals("<w:noBreakHyphen/>")) {
                        sb.append("-"); // Render as regular hyphen for simplicity
                    }
                    if (runXml.contains("<w:softHyphen")) {
                        sb.append("-"); // Soft hyphen renders as hyphen when visible
                    }
                } catch (Exception e) {
                    // Ignore XML parsing errors for individual runs
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Failed to extract special chars from paragraph: {}", e.getMessage());
            return standardText;
        }
    }

    /**
     * Reconstructs the visible auto-numbering text (e.g. "1.", "1.1", "(1)", "一、") that
     * Word renders for paragraphs with a numbering definition.
     *
     * <p>POI exposes numbering metadata through {@code paragraph.getNumID()} and the
     * document's {@link XWPFNumbering} table, but it does NOT pre-render the actual number
     * — {@code paragraph.getText()} returns the body text without the leading number. The
     * number we see in Word is computed at render time by walking numbering.xml. This class
     * does that walk: for each numbered paragraph it looks up the level template (lvlText,
     * e.g. "%1.%2."), formats the per-level counters (decimal, letter, roman, Chinese), and
     * returns the resulting prefix.
     *
     * <p>Counter rules (matches Word's behavior):
     * <ul>
     *   <li>Counters are scoped per (numId, level). Different numId values are independent
     *       lists with independent counts.</li>
     *   <li>When a paragraph at level N is encountered, level N's counter increments (or
     *       initializes from the level's start value), and all deeper-level counters reset
     *       so the next nested item starts fresh.</li>
     * </ul>
     */
    private static class NumberingFormatter {
        private final XWPFNumbering numbering;
        // key = "numId:level", value = current count
        private final Map<String, Integer> counters = new HashMap<>();

        // The numId used by heading paragraphs in this document.
        // This is determined when the first heading 1 paragraph with an explicit numId is encountered.
        // All subsequent heading paragraphs (even those without explicit numId) should use this numId
        // to maintain consistent numbering across all heading levels.
        private BigInteger headingNumId = null;

        NumberingFormatter(XWPFNumbering numbering) {
            this.numbering = numbering;
        }

        /**
         * Manually set the counter for a specific numId and level.
         * This is used to initialize ancestor level counters that would otherwise
         * remain at 0 because no paragraphs exist at that level in the document.
         *
         * For example, in documents where appendix subsections (level 1) reference
         * level 0 counters that were never initialized by appendix main titles
         * (which may not use the numbering system), this method allows us to
         * manually set the correct letter counter (A=1, B=2, C=3, etc.) before
         * processing the subsections.
         *
         * @param numId the numbering ID
         * @param level the level (0-based)
         * @param value the counter value to set
         */
        void setCounter(BigInteger numId, int level, int value) {
            String key = numId + ":" + level;
            counters.put(key, value);
        }

        /**
         * Get the current counter value for a specific numId and level.
         * Returns 0 if the counter has not been initialized.
         */
        int getCounter(BigInteger numId, int level) {
            String key = numId + ":" + level;
            return counters.getOrDefault(key, 0);
        }

        /**
         * Compute the numbering prefix for the given paragraph, or {@code null} if it has
         * no numbering, the numbering definition is missing, or the format is non-numeric
         * (bullets / "none").
         *
         * This method also checks the paragraph's style for numbering definitions if the
         * paragraph itself doesn't have explicit numbering. However, this style inheritance
         * is ONLY applied to specific styles that need it (like appendix marker styles),
         * NOT to general heading styles which may have their own numbering system that
         * should not interfere with document parsing.
         */
        String formatNumber(XWPFParagraph paragraph, XWPFStyles styles) {
            if (numbering == null || paragraph == null) return null;
            BigInteger numId;
            BigInteger ilvl;
            try {
                numId = paragraph.getNumID();
                ilvl = paragraph.getNumIlvl();
            } catch (Exception e) {
                return null;
            }

            // Check if this is a heading paragraph (heading 1-6 / 标题 1-6 only)
            // Do NOT match other styles that contain "标题" (like "图表标题", "附录标题")
            boolean isHeading = false;
            String styleId = paragraph.getStyle();
            if (styles != null && styleId != null) {
                XWPFStyle style = styles.getStyle(styleId);
                if (style != null && style.getName() != null) {
                    String name = style.getName().toLowerCase();
                    // Only match heading 1-6 or 标题 1-6, not "图表标题" or "附录标题"
                    for (int i = 1; i <= 6; i++) {
                        if (name.equals("heading " + i) || name.equals("heading" + i) ||
                            name.equals("标题" + i) || name.equals("标题 " + i)) {
                            isHeading = true;
                            break;
                        }
                    }
                }
            }

            // If paragraph has explicit numId and it's a heading, record this as the document's heading numId
            if (numId != null && numId.signum() > 0 && isHeading && headingNumId == null) {
                headingNumId = numId;
                log.debug("Recording heading numId: {}", headingNumId);
            }

            // If paragraph has no explicit numbering, check if the style defines numbering.
            if (numId == null || numId.signum() == 0) {
                if (styles != null && styleId != null && shouldUseStyleNumbering(styleId, styles)) {
                    // For heading styles, use the recorded heading numId (if available)
                    // instead of the style-defined numId, which may be incorrect.
                    if (isHeading && headingNumId != null) {
                        numId = headingNumId;
                        ilvl = getHeadingIlvl(styleId, styles);
                    } else if (isHeading && headingNumId == null) {
                        // No heading numId recorded yet, skip numbering for this heading
                        // (it will be recorded when a heading with explicit numId is encountered)
                        return null;
                    } else {
                        // For appendix marker styles, use the style-defined numId
                        numId = getNumIdFromStyle(styleId, styles);
                        ilvl = getIlvlFromStyle(styleId, styles);
                    }
                }
            }

            // numId == 0 is OOXML's explicit "no numbering"; treat the same as null.
            if (numId == null || numId.signum() == 0) return null;

            try {
                XWPFNum num = numbering.getNum(numId);
                if (num == null || num.getCTNum() == null
                        || num.getCTNum().getAbstractNumId() == null) {
                    return null;
                }
                BigInteger absNumId = num.getCTNum().getAbstractNumId().getVal();
                XWPFAbstractNum absNum = numbering.getAbstractNum(absNumId);
                if (absNum == null) return null;
                CTAbstractNum ctAbsNum = absNum.getCTAbstractNum();
                if (ctAbsNum == null) return null;

                List<CTLvl> lvlList = ctAbsNum.getLvlList();
                if (lvlList == null || lvlList.isEmpty()) return null;

                int currentLevel = (ilvl != null) ? ilvl.intValue() : 0;
                if (currentLevel < 0 || currentLevel >= lvlList.size()) return null;
                CTLvl lvl = lvlList.get(currentLevel);

                String numFmt = "decimal";
                if (lvl.getNumFmt() != null && lvl.getNumFmt().getVal() != null) {
                    numFmt = lvl.getNumFmt().getVal().toString();
                }
                // Skip non-numeric formats — bullets render as glyphs, not numbers, and
                // "none" explicitly suppresses the prefix.
                if ("bullet".equalsIgnoreCase(numFmt) || "none".equalsIgnoreCase(numFmt)) {
                    return null;
                }

                int startVal = 1;
                if (lvl.getStart() != null && lvl.getStart().getVal() != null) {
                    startVal = lvl.getStart().getVal().intValue();
                }

                // Increment this level's counter (or initialize from start value).
                String key = numId + ":" + currentLevel;
                int current = counters.getOrDefault(key, startVal - 1) + 1;
                counters.put(key, current);
                // Reset deeper counters so the next nested item restarts.
                for (int deeper = currentLevel + 1; deeper < lvlList.size(); deeper++) {
                    counters.remove(numId + ":" + deeper);
                }

                String lvlText = (lvl.getLvlText() != null && lvl.getLvlText().getVal() != null)
                        ? lvl.getLvlText().getVal()
                        : ("%" + (currentLevel + 1) + ".");

                // DEBUG: 输出编号格式模板
                if (log.isDebugEnabled()) {
                    log.debug("Numbering format template (lvlText): '{}' for level {}", lvlText, currentLevel);
                }

                // Substitute %1, %2, ... with the formatted counter for that ancestor level.
                StringBuilder result = new StringBuilder();
                int i = 0;
                while (i < lvlText.length()) {
                    char c = lvlText.charAt(i);
                    if (c == '%' && i + 1 < lvlText.length()
                            && Character.isDigit(lvlText.charAt(i + 1))) {
                        int placeholder = lvlText.charAt(i + 1) - '0';
                        int targetLevel = placeholder - 1;
                        int value = (targetLevel >= 0 && targetLevel <= currentLevel)
                                ? counters.getOrDefault(numId + ":" + targetLevel, 0)
                                : 0;
                        String fmt = numFmt;
                        if (targetLevel >= 0 && targetLevel < lvlList.size()) {
                            CTLvl ancestorLvl = lvlList.get(targetLevel);
                            if (ancestorLvl.getNumFmt() != null
                                    && ancestorLvl.getNumFmt().getVal() != null) {
                                fmt = ancestorLvl.getNumFmt().getVal().toString();
                            }
                        }
                        result.append(formatValue(value, fmt));
                        i += 2;
                    } else {
                        result.append(c);
                        i++;
                    }
                }
                return result.toString();
            } catch (Exception e) {
                log.debug("Failed to format numbering for paragraph: {}", e.getMessage());
                return null;
            }
        }

        /**
         * Get the numId from a style definition's numPr element.
         * In OOXML, numbering can be defined at the style level, and paragraphs
         * inherit this if they don't have their own numPr.
         */
        private BigInteger getNumIdFromStyle(String styleId, XWPFStyles styles) {
            try {
                XWPFStyle style = styles.getStyle(styleId);
                if (style == null) return null;
                CTStyle ctStyle = style.getCTStyle();
                CTPPrGeneral pPr = ctStyle.getPPr();
                if (pPr == null) return null;
                CTNumPr numPr = pPr.getNumPr();
                if (numPr == null) return null;
                CTDecimalNumber numId = numPr.getNumId();
                if (numId == null) return null;
                return numId.getVal();
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * Get the ilvl (indent level) from a style definition's numPr element.
         */
        private BigInteger getIlvlFromStyle(String styleId, XWPFStyles styles) {
            try {
                XWPFStyle style = styles.getStyle(styleId);
                if (style == null) return BigInteger.ZERO;
                CTStyle ctStyle = style.getCTStyle();
                CTPPrGeneral pPr = ctStyle.getPPr();
                if (pPr == null) return BigInteger.ZERO;
                CTNumPr numPr = pPr.getNumPr();
                if (numPr == null) return BigInteger.ZERO;
                CTDecimalNumber ilvl = numPr.getIlvl();
                if (ilvl == null) return BigInteger.ZERO;
                return ilvl.getVal();
            } catch (Exception e) {
                return BigInteger.ZERO;
            }
        }

        /**
         * Determine whether style-based numbering inheritance should be used for this style.
         *
         * This method returns true for:
         * - Appendix marker styles (样式名称 contains "附录标识")
         * - Heading styles (heading 1-6), but with special handling to use the correct numId
         *
         * For heading styles, the style definition may specify a different numId than what
         * is actually used by heading paragraphs in the document. For example, heading 1
         * paragraphs may use numId=9, while heading 2 style definition specifies numId=1.
         * Using the wrong numId would cause counter conflicts.
         *
         * @param styleId the style ID to check
         * @param styles the document styles
         * @return true if this style should use style-based numbering inheritance
         */
        private boolean shouldUseStyleNumbering(String styleId, XWPFStyles styles) {
            if (styles == null) return false;

            XWPFStyle style = styles.getStyle(styleId);
            if (style == null || style.getName() == null) return false;

            String name = style.getName().toLowerCase();

            // Allow style numbering for appendix marker styles
            if (name.contains("附录标识")) {
                return true;
            }

            // Also allow for heading styles (heading 1-6 / 标题1-6)
            // but we'll use a different method to get the correct numId
            if (name.contains("heading") || name.contains("标题")) {
                return true;
            }

            return false;
        }

        /**
         * Get the numId for a heading style, using the document's actual heading numbering.
         *
         * For heading styles, the style definition may specify a wrong numId (e.g., heading 2
         * style may define numId=1, but heading 1 paragraphs actually use numId=9). To avoid
         * counter conflicts, we find the numId used by the FIRST heading 1 paragraph in the
         * document, and use that for all heading styles.
         *
         * @param styleId the style ID
         * @param styles the document styles
         * @return the correct numId for heading styles, or null if not applicable
         */
        private BigInteger getHeadingNumId(String styleId, XWPFStyles styles) {
            // This will be determined during parsing by tracking heading 1's numId
            // For now, return null - the actual logic is in formatNumber method
            return null;
        }

        /**
         * Get the ilvl (indent level) for a heading style based on its level.
         *
         * heading 1 → ilvl 0
         * heading 2 → ilvl 1
         * heading 3 → ilvl 2
         * etc.
         *
         * @param styleId the style ID
         * @param styles the document styles
         * @return the ilvl for this heading level
         */
        private BigInteger getHeadingIlvl(String styleId, XWPFStyles styles) {
            if (styles == null) return BigInteger.ZERO;

            XWPFStyle style = styles.getStyle(styleId);
            if (style == null || style.getName() == null) return BigInteger.ZERO;

            String name = style.getName().toLowerCase();

            // Determine heading level from style name
            for (int i = 1; i <= 6; i++) {
                if (name.equals("heading " + i) || name.equals("heading" + i) ||
                    name.equals("标题" + i) || name.equals("标题 " + i)) {
                    return BigInteger.valueOf(i - 1);  // heading 1 = ilvl 0
                }
            }

            // Pattern match for non-standard names
            if (name.startsWith("heading") || name.startsWith("标题")) {
                String numStr = name.replaceAll("[^0-9]", "");
                if (!numStr.isEmpty()) {
                    try {
                        int lvl = Integer.parseInt(numStr);
                        if (lvl >= 1 && lvl <= 6) {
                            return BigInteger.valueOf(lvl - 1);
                        }
                    } catch (NumberFormatException ignored) { }
                }
            }

            return BigInteger.ZERO;
        }

        private static String formatValue(int value, String numFmt) {
            if (numFmt == null) return Integer.toString(value);
            switch (numFmt.toLowerCase()) {
                case "decimal":
                    return Integer.toString(value);
                case "decimalzero":
                    return value < 10 ? "0" + value : Integer.toString(value);
                case "lowerletter":
                    return toLetters(value, false);
                case "upperletter":
                    return toLetters(value, true);
                case "lowerroman":
                    return toRoman(value).toLowerCase();
                case "upperroman":
                    return toRoman(value);
                case "chinesecounting":
                case "chinesecountingthousand":
                case "ideographdigital":
                case "japanesecounting":
                case "taiwanesecounting":
                    return toChineseCount(value);
                case "decimalfullwidth":
                    return toFullWidthDigits(value);
                default:
                    return Integer.toString(value);
            }
        }

        private static String toLetters(int n, boolean upper) {
            if (n <= 0) return Integer.toString(n);
            StringBuilder sb = new StringBuilder();
            while (n > 0) {
                int rem = (n - 1) % 26;
                sb.insert(0, (char) ((upper ? 'A' : 'a') + rem));
                n = (n - 1) / 26;
            }
            return sb.toString();
        }

        private static String toRoman(int n) {
            if (n <= 0) return Integer.toString(n);
            int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                while (n >= values[i]) {
                    sb.append(symbols[i]);
                    n -= values[i];
                }
            }
            return sb.toString();
        }

        private static String toChineseCount(int n) {
            if (n <= 0) return Integer.toString(n);
            String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
            if (n < 10) return digits[n];
            if (n < 20) return n == 10 ? "十" : "十" + digits[n - 10];
            if (n < 100) {
                int tens = n / 10;
                int ones = n % 10;
                return digits[tens] + "十" + (ones == 0 ? "" : digits[ones]);
            }
            return Integer.toString(n);
        }

        private static String toFullWidthDigits(int n) {
            StringBuilder sb = new StringBuilder();
            for (char c : Integer.toString(n).toCharArray()) {
                sb.append((char) (c - '0' + 0xFF10));
            }
            return sb.toString();
        }
    }

    /**
     * Parse .doc format — returns entire document as a single chapter.
     */
    private static List<Chapter> parseDocChapters(String filePath) throws IOException {
        log.info("Parsing .doc file: {}", filePath);
        try (InputStream is = new FileInputStream(filePath);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {

            String result = extractor.getText().trim();
            log.info("Parsed .doc file, extracted {} characters", result.length());
            List<Chapter> chapters = new ArrayList<>();
            List<NodeDraft> nodes = Arrays.stream(result.replace("\r\n", "\n").split("\\n\\s*\\n"))
                    .map(String::trim)
                    .filter(text -> !text.isBlank())
                    .map(text -> paragraphNode("", text))
                    .toList();
            chapters.add(createChapter(1, "", nodes, 1));
            return chapters;
        }
    }
}
