package com.aireview.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes reviewer edits back into the ORIGINAL .docx, in place.
 *
 * The point of this class is fidelity: the exported file must be byte-for-byte the
 * uploaded document except for the paragraphs the reviewer actually corrected. So we
 * reopen the original file and patch individual runs rather than regenerating a document
 * from the parsed model — regeneration would drop styles, headers/footers, images,
 * numbering, section breaks and everything else POI does not round-trip.
 *
 * <h2>How an edit finds its paragraph</h2>
 * Node ids ({@code DOC-C001-N0004}) are chapter-scoped ordinals and carry no file
 * position, so we re-parse the document we are about to modify and read the
 * {@code bodyIndex} {@link WordParser} recorded on each node. Parsing is deterministic,
 * so re-parsing at export time reproduces exactly the ids the reviewer saw in the UI.
 * Doing it this way (rather than trusting an index sent by the browser) also means a
 * stale client cannot corrupt an unrelated paragraph.
 *
 * <h2>Auto-numbering</h2>
 * Word renders list and heading numbers from numbering.xml at display time; they are not
 * present in the paragraph runs. {@link WordParser} reconstructs them and prepends them
 * to the node text, so the text the reviewer edited may start with a number that does
 * not physically exist in the file. We strip that prefix again before writing, otherwise
 * the exported document would show the number twice.
 */
@Slf4j
public final class DocumentRevisionWriter {

    private DocumentRevisionWriter() {
    }

    /**
     * One reviewer correction. {@code cellRow}/{@code cellColumn} are the 1-based
     * coordinates from the structured table payload and are null for plain paragraphs.
     */
    public record SourceEdit(String nodeId, String newText, Integer cellRow, Integer cellColumn) {
    }

    /** Outcome of an {@link #apply} run: the rewritten bytes plus what could not be placed. */
    public record RevisionResult(byte[] bytes, int appliedCount, List<String> skippedNodeIds) {
    }

    /**
     * Apply {@code edits} to the document at {@code originalFilePath} and return the
     * rewritten bytes. Edits that cannot be located are skipped and reported rather than
     * failing the whole export, so one stale node id does not cost the reviewer every
     * other correction they made.
     *
     * @throws IllegalArgumentException if the source file is missing or is not a .docx
     */
    public static RevisionResult apply(String originalFilePath, List<SourceEdit> edits)
            throws IOException {
        Path path = Path.of(Objects.requireNonNull(originalFilePath, "originalFilePath"));
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("原始文档已不存在，无法导出修订版：" + path.getFileName());
        }
        String fileName = path.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".docx")) {
            // HWPF cannot round-trip .doc reliably, and a lossy rewrite would defeat the
            // whole purpose of this export. Fail loudly instead of shipping a damaged file.
            throw new IllegalArgumentException(
                    "修订版导出仅支持 .docx 文档，当前文件为 " + fileName + "，请上传 .docx 后重新审查");
        }

        List<SourceEdit> pending = edits == null ? List.of() : edits.stream()
                .filter(edit -> edit != null && edit.nodeId() != null && !edit.nodeId().isBlank())
                .filter(edit -> edit.newText() != null)
                .toList();

        try (InputStream is = new FileInputStream(path.toFile());
             XWPFDocument document = new XWPFDocument(is)) {

            Map<String, WordParser.DocumentNode> nodesById = indexNodes(document);
            List<IBodyElement> bodyElements =
                    WordParser.flattenBodyElements(document.getBodyElements());

            int applied = 0;
            List<String> skipped = new ArrayList<>();
            for (SourceEdit edit : pending) {
                if (applyOne(edit, nodesById, bodyElements)) {
                    applied++;
                } else {
                    skipped.add(edit.nodeId());
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            log.info("Revised document built from {}: {} edit(s) applied, {} skipped",
                    path.getFileName(), applied, skipped.size());
            return new RevisionResult(out.toByteArray(), applied, skipped);
        }
    }

    /** Re-parse the open document so node ids resolve to positions in this same instance. */
    private static Map<String, WordParser.DocumentNode> indexNodes(XWPFDocument document) {
        Map<String, WordParser.DocumentNode> nodesById = new HashMap<>();
        for (WordParser.Chapter chapter : WordParser.parseDocxChapters(document)) {
            for (WordParser.DocumentNode node : chapter.getNodes()) {
                nodesById.put(node.getId(), node);
            }
        }
        return nodesById;
    }

    private static boolean applyOne(SourceEdit edit,
                                    Map<String, WordParser.DocumentNode> nodesById,
                                    List<IBodyElement> bodyElements) {
        WordParser.DocumentNode node = nodesById.get(edit.nodeId());
        if (node == null) {
            log.warn("Skipping edit: node {} no longer exists in the source document", edit.nodeId());
            return false;
        }
        int bodyIndex = node.getBodyIndex();
        if (bodyIndex < 0 || bodyIndex >= bodyElements.size()) {
            log.warn("Skipping edit: node {} has no writable origin (bodyIndex={})",
                    edit.nodeId(), bodyIndex);
            return false;
        }

        IBodyElement element = bodyElements.get(bodyIndex);
        if (edit.cellRow() != null && edit.cellColumn() != null) {
            if (!(element instanceof XWPFTable table)) {
                log.warn("Skipping edit: node {} is not a table but carries cell coordinates",
                        edit.nodeId());
                return false;
            }
            XWPFTableCell cell = WordParser.resolveLogicalCell(table, edit.cellRow(), edit.cellColumn());
            if (cell == null) {
                log.warn("Skipping edit: node {} has no cell at (row={}, col={})",
                        edit.nodeId(), edit.cellRow(), edit.cellColumn());
                return false;
            }
            setCellText(cell, edit.newText());
            return true;
        }

        if (!(element instanceof XWPFParagraph paragraph)) {
            log.warn("Skipping edit: node {} resolves to {}, which is not an editable paragraph",
                    edit.nodeId(), element.getClass().getSimpleName());
            return false;
        }
        setParagraphText(paragraph, stripNumberPrefix(edit.newText(), node.getNumberPrefix()));
        return true;
    }

    /**
     * Remove the reconstructed auto-numbering prefix so it is not written into the body.
     * Only strips when the edited text still starts with it — a reviewer who deliberately
     * rewrote or removed the leading number gets their text through untouched.
     */
    static String stripNumberPrefix(String newText, String numberPrefix) {
        if (newText == null) return "";
        if (numberPrefix == null || numberPrefix.isBlank()) return newText;
        // WordParser joins prefix and text with a single space (combinePrefixAndText).
        String withSeparator = numberPrefix + " ";
        if (newText.startsWith(withSeparator)) {
            return newText.substring(withSeparator.length());
        }
        if (newText.startsWith(numberPrefix)) {
            return newText.substring(numberPrefix.length()).stripLeading();
        }
        return newText;
    }

    /**
     * Replace a paragraph's text while keeping its look. The first run carries the
     * paragraph's font, size and weight, so we rewrite that run and drop the remaining
     * text runs instead of clearing the paragraph and building a fresh, unstyled run.
     *
     * Runs holding a drawing, picture or embedded object are left alone: a paragraph can
     * mix text with an inline image, and removing such a run would silently delete the
     * image from the exported document.
     */
    private static void setParagraphText(XWPFParagraph paragraph, String text) {
        List<XWPFRun> runs = paragraph.getRuns();

        XWPFRun target = null;
        for (XWPFRun run : runs) {
            if (!carriesVisual(run)) {
                target = run;
                break;
            }
        }
        if (target == null) {
            target = paragraph.createRun();
        }

        // Remove the other text-only runs back-to-front so earlier indices stay valid.
        for (int index = paragraph.getRuns().size() - 1; index >= 0; index--) {
            XWPFRun run = paragraph.getRuns().get(index);
            if (run == target || carriesVisual(run)) continue;
            paragraph.removeRun(index);
        }

        writeRunText(target, text);
    }

    /**
     * A Word paragraph has no newlines; a visual line break is a {@code <w:br/>} inside a
     * run. Split the edited text so multi-line input renders the way the reviewer typed it
     * rather than collapsing onto one line.
     */
    private static void writeRunText(XWPFRun run, String text) {
        // Must clear first: a single run often holds several <w:t> elements (Word splits
        // them on spell-check and formatting boundaries), and setText(s, 0) only replaces
        // the one at index 0 — the leftovers would be appended after the new text.
        clearRunText(run);
        String[] lines = text.split("\n", -1);
        run.setText(lines[0], 0);
        for (int index = 1; index < lines.length; index++) {
            run.addBreak();
            run.setText(lines[index]);
        }
    }

    /** Drop every text and line-break child of a run, leaving its formatting intact. */
    private static void clearRunText(XWPFRun run) {
        CTR ctr = run.getCTR();
        for (int index = ctr.sizeOfTArray() - 1; index >= 0; index--) {
            ctr.removeT(index);
        }
        for (int index = ctr.sizeOfBrArray() - 1; index >= 0; index--) {
            ctr.removeBr(index);
        }
        for (int index = ctr.sizeOfCrArray() - 1; index >= 0; index--) {
            ctr.removeCr(index);
        }
        for (int index = ctr.sizeOfTabArray() - 1; index >= 0; index--) {
            ctr.removeTab(index);
        }
    }

    private static boolean carriesVisual(XWPFRun run) {
        try {
            return !run.getEmbeddedPictures().isEmpty()
                    || run.getCTR().sizeOfDrawingArray() > 0
                    || run.getCTR().sizeOfPictArray() > 0
                    || run.getCTR().sizeOfObjectArray() > 0;
        } catch (Exception e) {
            // Be conservative: if we cannot tell, treat the run as carrying content and
            // leave it in place rather than risk deleting an image.
            return true;
        }
    }

    /**
     * Rewrite a table cell. The cell's first paragraph takes the new text and any further
     * paragraphs are emptied, which keeps the cell's borders, shading and width intact.
     */
    private static void setCellText(XWPFTableCell cell, String text) {
        List<XWPFParagraph> paragraphs = cell.getParagraphs();
        if (paragraphs.isEmpty()) {
            writeRunText(cell.addParagraph().createRun(), text);
            return;
        }
        setParagraphText(paragraphs.get(0), text);
        for (int index = 1; index < paragraphs.size(); index++) {
            setParagraphText(paragraphs.get(index), "");
        }
    }
}
