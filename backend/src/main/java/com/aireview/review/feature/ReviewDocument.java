package com.aireview.review.feature;

import com.aireview.document.WordParser;

import java.util.List;

/**
 * A document after feature-specific parsing and structural normalization.
 *
 * <p>The two chapter views deliberately serve different consumers. {@code sourceChapters}
 * preserves every page/section needed by CHUNK review and source tracing, while
 * {@code structuredReviewChapters} is the feature-selected view used by SAR indexing.
 * A report feature may therefore keep appendices for source tracing but exclude them from
 * a particular structured review without changing either review pipeline.
 */
public record ReviewDocument(
        List<WordParser.Chapter> sourceChapters,
        List<WordParser.Chapter> structuredReviewChapters,
        List<String> declaredDomainSections) {

    public ReviewDocument {
        sourceChapters = sourceChapters == null ? List.of() : List.copyOf(sourceChapters);
        structuredReviewChapters = structuredReviewChapters == null
                ? List.of() : List.copyOf(structuredReviewChapters);
        declaredDomainSections = declaredDomainSections == null
                ? List.of() : List.copyOf(declaredDomainSections);
    }
}
