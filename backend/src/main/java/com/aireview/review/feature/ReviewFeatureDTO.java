package com.aireview.review.feature;

/** Discoverable metadata for review-feature selectors and administration clients. */
public record ReviewFeatureDTO(
        String category,
        String displayName,
        String description,
        boolean enabled,
        boolean defaultFeature) {
}
