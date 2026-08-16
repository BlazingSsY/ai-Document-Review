package com.aireview.review.feature;

/**
 * Self-contained registration unit for one review business feature.
 *
 * <p>A future test-report or sea-trial-report review is added as another Spring bean in
 * its own package. Its category, permission and document processor travel together, so
 * no central switch statement or category constant file needs editing.
 */
public interface ReviewFeature {

    String category();

    String displayName();

    String description();

    String permissionCode();

    ReviewDocumentProcessor documentProcessor();

    default boolean enabled() {
        return true;
    }

    default boolean defaultFeature() {
        return false;
    }

    /** Whether this feature uses the current shared rule-library assignment model. */
    default boolean usesSharedRuleLibraries() {
        return false;
    }
}
