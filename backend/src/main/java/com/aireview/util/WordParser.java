package com.aireview.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Utility to parse Word documents (.doc and .docx) and extract text content.
 */
@Slf4j
public class WordParser {

    private WordParser() {
    }

    /**
     * Parse a Word document and return its text content.
     *
     * @param filePath path to the Word file
     * @return extracted text
     * @throws IOException if file cannot be read or parsed
     */
    public static String parse(String filePath) throws IOException {
        Path path = Path.of(filePath);
        String fileName = path.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".docx")) {
            return parseDocx(filePath);
        } else if (fileName.endsWith(".doc")) {
            return parseDoc(filePath);
        } else {
            throw new IllegalArgumentException("Unsupported file format: " + fileName
                    + ". Only .doc and .docx are supported.");
        }
    }

    /**
     * Parse .docx format (Office Open XML).
     */
    private static String parseDocx(String filePath) throws IOException {
        log.info("Parsing .docx file: {}", filePath);
        StringBuilder text = new StringBuilder();

        try (InputStream is = new FileInputStream(filePath);
             XWPFDocument document = new XWPFDocument(is)) {

            // Extract paragraphs
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String paraText = paragraph.getText();
                if (paraText != null && !paraText.isBlank()) {
                    text.append(paraText).append("\n");
                }
            }

            // Extract table content
            for (XWPFTable table : document.getTables()) {
                text.append("\n[Table]\n");
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder rowText = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (rowText.length() > 0) {
                            rowText.append(" | ");
                        }
                        rowText.append(cell.getText());
                    }
                    text.append(rowText).append("\n");
                }
                text.append("[/Table]\n\n");
            }
        }

        String result = text.toString().trim();
        log.info("Parsed .docx file, extracted {} characters", result.length());
        return result;
    }

    /**
     * Parse .doc format (legacy binary format).
     */
    private static String parseDoc(String filePath) throws IOException {
        log.info("Parsing .doc file: {}", filePath);

        try (InputStream is = new FileInputStream(filePath);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {

            String result = extractor.getText().trim();
            log.info("Parsed .doc file, extracted {} characters", result.length());
            return result;
        }
    }
}
