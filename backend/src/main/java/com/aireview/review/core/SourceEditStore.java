package com.aireview.review.core;

import com.aireview.document.DocumentRevisionWriter;
import com.aireview.review.dto.SourceEditRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads and writes reviewer source edits inside a task's {@code ai_result} JSON.
 *
 * Stored on {@code ai_result.sourceEdits} rather than in a dedicated table for the same
 * reason manual review decisions live there: an edit only means anything relative to the
 * review run that produced the node ids, and re-review mints a new task with a fresh
 * {@code ai_result}, so the lifetimes already match. It also keeps the CHUNK and SAR
 * pipelines — which have physically separate tables but identical result shapes — sharing
 * one implementation instead of two mirrored schemas.
 *
 * Static and pipeline-agnostic on purpose: both {@code ReviewService} and
 * {@code SarReviewService} call in with their own task's map.
 */
public final class SourceEditStore {

    public static final String FIELD = "sourceEdits";

    private SourceEditStore() {
    }

    /** Existing edits, newest write order preserved. Never null. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> read(Map<String, Object> aiResult) {
        if (aiResult == null) return new ArrayList<>();
        Object raw = aiResult.get(FIELD);
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> edits = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                edits.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return edits;
    }

    /**
     * Insert or replace the edit for {@code request}'s node, writing the result back into
     * {@code aiResult}.
     *
     * An edit whose text matches the original is treated as a revert and removes the entry
     * instead of storing a no-op, so "已修改" badges and the revised-export count stay
     * truthful when a reviewer undoes their own change.
     *
     * @return the stored entry, or null when the edit was a revert
     */
    public static Map<String, Object> upsert(Map<String, Object> aiResult,
                                             SourceEditRequest request,
                                             Long userId) {
        Objects.requireNonNull(aiResult, "aiResult");
        if (request == null || request.getNodeId() == null || request.getNodeId().isBlank()) {
            throw new IllegalArgumentException("nodeId is required");
        }
        if (request.getNewText() == null) {
            throw new IllegalArgumentException("newText is required");
        }

        String key = editKey(request.getNodeId(), request.getCellRow(), request.getCellColumn());
        List<Map<String, Object>> edits = read(aiResult);
        edits.removeIf(edit -> key.equals(editKey(
                str(edit.get("nodeId")), intOrNull(edit.get("cellRow")), intOrNull(edit.get("cellColumn")))));

        boolean reverted = request.getOriginalText() != null
                && request.getOriginalText().equals(request.getNewText());
        Map<String, Object> stored = null;
        if (!reverted) {
            stored = new LinkedHashMap<>();
            stored.put("nodeId", request.getNodeId());
            stored.put("sourceId", request.getSourceId());
            stored.put("nodeType", request.getNodeType());
            stored.put("originalText", request.getOriginalText());
            stored.put("newText", request.getNewText());
            stored.put("cellRow", request.getCellRow());
            stored.put("cellColumn", request.getCellColumn());
            stored.put("editorId", userId);
            stored.put("editedAt", LocalDateTime.now().toString());
            edits.add(stored);
        }

        aiResult.put(FIELD, edits);
        return stored;
    }

    /** Drop every stored edit, returning how many were removed. */
    public static int clear(Map<String, Object> aiResult) {
        if (aiResult == null) return 0;
        int removed = read(aiResult).size();
        aiResult.put(FIELD, new ArrayList<>());
        return removed;
    }

    /** Translate stored edits into the writer's input shape. */
    public static List<DocumentRevisionWriter.SourceEdit> toWriterEdits(Map<String, Object> aiResult) {
        List<DocumentRevisionWriter.SourceEdit> out = new ArrayList<>();
        for (Map<String, Object> edit : read(aiResult)) {
            String nodeId = str(edit.get("nodeId"));
            if (nodeId.isBlank()) continue;
            out.add(new DocumentRevisionWriter.SourceEdit(
                    nodeId,
                    str(edit.get("newText")),
                    intOrNull(edit.get("cellRow")),
                    intOrNull(edit.get("cellColumn"))));
        }
        return out;
    }

    /** A table cell is addressed by node + coordinates; a paragraph by node alone. */
    private static String editKey(String nodeId, Integer cellRow, Integer cellColumn) {
        if (cellRow == null || cellColumn == null) return nodeId + "|-|-";
        return nodeId + "|" + cellRow + "|" + cellColumn;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
