package com.aireview.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility to parse Word documents (.doc and .docx) and extract text content.
 * Supports heading-based chapter splitting for .docx files.
 * Tables are converted to HTML format to preserve structure for AI review.
 * Images/drawings are skipped during parsing.
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
     * Parse a .docx Word document and split it into chapters by Heading 1.
     * Images/drawings are skipped. Tables are converted to HTML format.
     * If no Heading 1 is found, the entire document is returned as a single chapter.
     *
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
     * Parse .docx format, adaptively splitting by the highest heading level found.
     * First pass scans all paragraphs to find the minimum (top-level) heading level.
     * Second pass splits the document by that level, treating each occurrence as a chapter.
     * Sub-headings below the split level are kept as formatted Markdown within the chapter body.
     * Tables are converted to HTML to preserve row/column structure.
     */
    private static List<Chapter> parseDocxChapters(String filePath) throws IOException {
        log.info("Parsing .docx file with adaptive chapter detection: {}", filePath);
        List<Chapter> chapters = new ArrayList<>();

        try (InputStream is = new FileInputStream(filePath);
             XWPFDocument document = new XWPFDocument(is)) {

            List<IBodyElement> bodyElements = document.getBodyElements();

            // First pass: find the FIRST heading level that appears in the document.
            // This is used as the chapter-split boundary. We scan in document order so that
            // the level of the very first heading encountered (= the document's top-level
            // chapter heading) is used, rather than the minimum across all headings.
            // Example: a document whose chapters are styled H3 but sub-sections are H2 would
            // have the first heading at H3, giving splitLevel = 3 (correct), whereas taking
            // the minimum would give H2 (incorrect).
            int firstHeadingLevel = -1;
            List<int[]> detectedHeadings = new ArrayList<>(); // [level, text-hash] for debug
            List<String> detectedHeadingTexts = new ArrayList<>(); // heading text for debug
            for (IBodyElement element : bodyElements) {
                if (element instanceof XWPFParagraph p) {
                    int lvl = getHeadingLevel(p);
                    if (lvl > 0) {
                        if (firstHeadingLevel == -1) {
                            firstHeadingLevel = lvl; // first heading found = chapter level
                        }
                        detectedHeadings.add(new int[]{lvl});
                        String text = p.getText();
                        if (text != null && !text.isBlank()) {
                            detectedHeadingTexts.add("H" + lvl + ": " + text.trim());
                        }
                    }
                }
            }
            log.info("Detected {} heading(s) in document. First heading level: H{}. Top headings: {}",
                    detectedHeadings.size(), firstHeadingLevel,
                    detectedHeadingTexts.stream().limit(20).toList());

            if (firstHeadingLevel == -1) {
                // No headings found at all — return entire document as one chapter
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

            // Second pass: split by the determined heading level
            String currentTitle = null;
            StringBuilder currentBody = new StringBuilder();

            for (IBodyElement element : bodyElements) {
                if (element instanceof XWPFParagraph paragraph) {
                    String paraText = paragraph.getText();
                    int headingLevel = getHeadingLevel(paragraph);

                    if (headingLevel == splitLevel) {
                        // Save previous chapter
                        if (currentTitle != null || currentBody.length() > 0) {
                            chapters.add(new Chapter(
                                    currentTitle != null ? currentTitle : "",
                                    currentBody.toString().trim()));
                        }
                        currentTitle = paraText != null ? paraText.trim() : "";
                        currentBody = new StringBuilder();
                        continue;
                    }

                    // Skip image-only paragraphs
                    if ((paraText == null || paraText.isBlank()) && hasDrawingOrPicture(paragraph)) {
                        continue;
                    }

                    // Format all other heading levels (not the split level) as Markdown headings.
                    // This covers both sub-headings (level > splitLevel) and any headings that
                    // appear within a chapter but have a numerically smaller level (e.g. H2 inside
                    // an H3-split document due to unconventional formatting).
                    if (headingLevel > 0 && headingLevel <= 6 && paraText != null && !paraText.isBlank()) {
                        currentBody.append("\n");
                        currentBody.append("#".repeat(headingLevel)).append(" ").append(paraText.trim());
                        currentBody.append("\n\n");
                        continue;
                    }

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
                String cellText = cell.getText().trim();
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
     * Supports English and Chinese heading style names in various formats.
     */
    private static int getHeadingLevel(XWPFParagraph paragraph) {
        String styleName = paragraph.getStyle();
        if (styleName != null) {
            String lower = styleName.toLowerCase().replace(" ", "");
            // Exact match: "heading1"-"heading6", Chinese "1"-"6" style IDs, "标题1"-"标题6"
            for (int i = 1; i <= 6; i++) {
                if (lower.equals("heading" + i)
                        || lower.equals(String.valueOf(i))
                        || lower.equals("标题" + i)) {
                    return i;
                }
            }
            // Pattern match: styles starting with "heading" or "标题" followed by a digit
            // Handles variations like "Heading1", "heading 1", "标题 1", "标题一级" etc.
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

        // Check outline level from paragraph properties
        try {
            var pPr = paragraph.getCTP().getPPr();
            if (pPr != null && pPr.getOutlineLvl() != null) {
                int level = pPr.getOutlineLvl().getVal().intValue();
                if (level >= 0 && level < 6) {
                    return level + 1; // outlineLvl is 0-based
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        return -1;
    }

    /**
     * Check if a paragraph contains only drawings or pictures (no meaningful text).
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
            return xml.contains("<w:drawing") || xml.contains("<w:pict");
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
