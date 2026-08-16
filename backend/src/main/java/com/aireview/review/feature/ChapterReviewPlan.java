package com.aireview.review.feature;

import com.aireview.document.ChunkUtils;
import com.aireview.document.WordParser;

import java.util.List;

/** Feature-specific chapter view and chunks consumed by the CHUNK pipeline. */
public record ChapterReviewPlan(
        List<WordParser.Chapter> chapters,
        List<ChunkUtils.ChunkResult> chunks,
        int generalSectionEnd) {

    public ChapterReviewPlan {
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
