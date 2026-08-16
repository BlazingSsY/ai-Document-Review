package com.aireview.review.feature;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewFeatureRegistryTest {

    private static final ReviewDocumentProcessor NOOP_PROCESSOR = new ReviewDocumentProcessor() {
        @Override public void validateUpload(String originalFilename) { }
        @Override public ReviewDocument parse(String filePath) { return new ReviewDocument(null, null, null); }
        @Override public ChapterReviewPlan planChapterReview(
                ReviewDocument document, int maxChunkTokens, int configuredGeneralSectionEnd) {
            return new ChapterReviewPlan(null, null, -1);
        }
        @Override public boolean isDomainSection(ReviewDocument document, String chapterTitle) { return false; }
    };

    @Test
    void discoversFeaturesWithoutACentralCategorySwitch() {
        ReviewFeature outline = feature("ENV_TEST_OUTLINE", "ENV_REVIEW", true, true);
        ReviewFeature report = feature("TEST_REPORT", "REPORT_REVIEW", true, false);
        ReviewFeatureRegistry registry = new ReviewFeatureRegistry(List.of(outline, report));

        assertThat(registry.requireEnabled(null)).isSameAs(outline);
        assertThat(registry.requireEnabled("test_report")).isSameAs(report);
        assertThat(registry.enabledPermissionCodes())
                .containsExactly("ENV_REVIEW", "REPORT_REVIEW");
    }

    @Test
    void rejectsUnknownAndDisabledFeaturesAtSubmissionBoundary() {
        ReviewFeature outline = feature("ENV_TEST_OUTLINE", "ENV_REVIEW", true, true);
        ReviewFeature disabled = feature("SEA_TRIAL_REPORT", "SEA_TRIAL_REVIEW", false, false);
        ReviewFeatureRegistry registry = new ReviewFeatureRegistry(List.of(outline, disabled));

        assertThatThrownBy(() -> registry.requireEnabled("TEST_REPORT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("暂不支持");
        assertThatThrownBy(() -> registry.requireEnabled("SEA_TRIAL_REPORT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("暂不支持");
    }

    @Test
    void failsFastOnDuplicateCategories() {
        assertThatThrownBy(() -> new ReviewFeatureRegistry(List.of(
                feature("TEST_REPORT", "A", true, true),
                feature("test_report", "B", true, false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate review category");
    }

    private static ReviewFeature feature(String category, String permission,
                                         boolean enabled, boolean isDefault) {
        return new ReviewFeature() {
            @Override public String category() { return category; }
            @Override public String displayName() { return category; }
            @Override public String description() { return category + " description"; }
            @Override public String permissionCode() { return permission; }
            @Override public ReviewDocumentProcessor documentProcessor() { return NOOP_PROCESSOR; }
            @Override public boolean enabled() { return enabled; }
            @Override public boolean defaultFeature() { return isDefault; }
        };
    }
}
