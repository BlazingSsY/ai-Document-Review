package com.aireview.review.dto;

import lombok.Data;

/**
 * One reviewer correction made in the 原文定位 panel.
 *
 * The node id is the only positional key the client sends: the backend re-derives the
 * document position from it at export time, so a stale browser tab can never address the
 * wrong paragraph.
 */
@Data
public class SourceEditRequest {

    /** Structural node id from the source payload, e.g. {@code DOC-C003-N0012}. */
    private String nodeId;

    /** Chapter/source id the node belongs to, kept for display and grouping. */
    private String sourceId;

    /** {@code paragraph} | {@code heading} | {@code chapter_title} | {@code table}. */
    private String nodeType;

    /** Text as parsed, used to detect a revert and to show a before/after diff. */
    private String originalText;

    /** Reviewer's corrected text. Equal to originalText clears the edit. */
    private String newText;

    /** 1-based table coordinates; null for plain paragraphs. */
    private Integer cellRow;

    private Integer cellColumn;
}
