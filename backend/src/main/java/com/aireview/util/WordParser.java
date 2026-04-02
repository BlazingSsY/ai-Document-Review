package com.aireview.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
     * A document chapter identified by its heading-1 title and body text.
     */
    public static class Chapter {
        private final String title;
        private final String content;

        public Chapter(String title, String content) {
            this.title = title;
            this.content = content;
        }

        public String getTitle() { return title; }
        public String getContent() { return content; }

        /** Full text including the title line. */
        public String getFullText() {
            if (title == null || title.isBlank()) return content;
            return title + "\n\n" + content;
        }
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
                StringBuilder sb = new StringBuilder();
                for (IBodyElement element : bodyElements) {
                    if (element instanceof XWPFParagraph p) {
                        String t = p.getText();
                        if (t != null && !t.isBlank()) sb.append(t).append("\n");
                    } else if (element instanceof XWPFTable tbl) {
                        sb.append("\n").append(convertTableToHtml(tbl)).append("\n\n");
                    }
                }
                chapters.add(new Chapter("", sb.toString().trim()));
                return chapters;
            }

            int splitLevel = firstHeadingLevel;
            log.info("Splitting document by H{} (first heading level = chapter boundary)", splitLevel);

            // Second pass: split document by the determined heading level.
            String currentTitle = null;
            StringBuilder currentBody = new StringBuilder();
            int figureIndex = 0; // counter for unnamed figure placeholders

            for (IBodyElement element : bodyElements) {
                if (element instanceof XWPFParagraph paragraph) {
                    String paraText = paragraph.getText();
                    int headingLevel = getHeadingLevel(paragraph, styleHeadingLevels);

                    // Chapter boundary: save current chapter and start new one
                    if (headingLevel == splitLevel) {
                        if (currentTitle != null || currentBody.length() > 0) {
                            chapters.add(new Chapter(
                                    currentTitle != null ? currentTitle : "",
                                    currentBody.toString().trim()));
                        }
                        currentTitle = paraText != null ? paraText.trim() : "";
                        currentBody = new StringBuilder();
                        continue;
                    }

                    // OLE/image-only paragraph: add placeholder instead of silently skipping.
                    // This preserves the knowledge that visual content (Visio diagrams, EMF
                    // charts, etc.) exists at this point in the document.
                    if ((paraText == null || paraText.isBlank()) && hasDrawingOrPicture(paragraph)) {
                        figureIndex++;
                        currentBody.append("[图表 ").append(figureIndex).append("]\n");
                        continue;
                    }

                    // Non-split heading: format as Markdown heading using the CORRECT level
                    if (headingLevel > 0 && headingLevel <= 6 && paraText != null && !paraText.isBlank()) {
                        currentBody.append("\n");
                        currentBody.append("#".repeat(headingLevel)).append(" ").append(paraText.trim());
                        currentBody.append("\n\n");
                        continue;
                    }

                    // Regular paragraph text
                    if (paraText != null && !paraText.isBlank()) {
                        currentBody.append(paraText).append("\n");
                    }

                } else if (element instanceof XWPFTable table) {
                    currentBody.append("\n").append(convertTableToHtml(table)).append("\n\n");
                }
            }

            // Add the last chapter
            if (currentTitle != null || currentBody.length() > 0) {
                chapters.add(new Chapter(
                        currentTitle != null ? currentTitle : "",
                        currentBody.toString().trim()));
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
     * Convert a Word table to HTML string, preserving row/column structure.
     */
    private static String convertTableToHtml(XWPFTable table) {
        StringBuilder html = new StringBuilder();
        html.append("<table border=\"1\">\n");

        List<XWPFTableRow> rows = table.getRows();
        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
            XWPFTableRow row = rows.get(rowIdx);
            html.append("  <tr>\n");
            String cellTag = (rowIdx == 0) ? "th" : "td";

            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = extractCellText(cell);
                html.append("    <").append(cellTag).append(">");
                html.append(escapeHtml(cellText));
                html.append("</").append(cellTag).append(">\n");
            }
            html.append("  </tr>\n");
        }

        html.append("</table>");
        return html.toString();
    }

    /**
     * Recursively extract all text from a table cell, including text in nested tables.
     * Uses getBodyElements() so nested XWPFTable objects are included, unlike getText()
     * which only scans the cell's direct paragraph list and misses nested tables.
     */
    private static String extractCellText(XWPFTableCell cell) {
        StringBuilder sb = new StringBuilder();
        try {
            for (IBodyElement el : cell.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String t = p.getText();
                    if (t != null && !t.isBlank()) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(t.trim());
                    }
                } else if (el instanceof XWPFTable nestedTable) {
                    for (XWPFTableRow nRow : nestedTable.getRows()) {
                        StringBuilder rowSb = new StringBuilder();
                        for (XWPFTableCell nCell : nRow.getTableCells()) {
                            if (rowSb.length() > 0) rowSb.append(" | ");
                            rowSb.append(extractCellText(nCell));
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
            chapters.add(new Chapter("", result));
            return chapters;
        }
    }
}
