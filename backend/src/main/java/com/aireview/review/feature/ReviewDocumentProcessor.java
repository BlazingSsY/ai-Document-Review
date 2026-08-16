package com.aireview.review.feature;

/**
 * Extension point for document handling that varies by review business feature.
 *
 * <p>Implementations own file validation, parsing, preface/appendix selection, chapter
 * grouping and domain-section recognition. CHUNK and SAR only consume this contract, so
 * adding a new document type does not require branching inside either pipeline service.
 */
public interface ReviewDocumentProcessor {

    /** Reject unsupported upload types before the file is persisted. */
    void validateUpload(String originalFilename);

    /** Parse and normalize one persisted source document. */
    ReviewDocument parse(String filePath) throws Exception;

    /** Build the feature's chapter-level review plan for the CHUNK pipeline. */
    ChapterReviewPlan planChapterReview(ReviewDocument document,
                                        int maxChunkTokens,
                                        int configuredGeneralSectionEnd);

    /** Whether a chapter is one of this document type's domain-specific sections. */
    boolean isDomainSection(ReviewDocument document, String chapterTitle);
}
