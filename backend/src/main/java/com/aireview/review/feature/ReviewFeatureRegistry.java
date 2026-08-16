package com.aireview.review.feature;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Discovers review features from Spring and provides the only category lookup API. */
@Component
public class ReviewFeatureRegistry {

    private final Map<String, ReviewFeature> features;
    private final ReviewFeature defaultFeature;

    public ReviewFeatureRegistry(List<ReviewFeature> discoveredFeatures) {
        if (discoveredFeatures == null || discoveredFeatures.isEmpty()) {
            throw new IllegalStateException("At least one review feature must be registered");
        }

        Map<String, ReviewFeature> registered = new LinkedHashMap<>();
        ReviewFeature discoveredDefault = null;
        for (ReviewFeature feature : discoveredFeatures) {
            if (feature == null || feature.documentProcessor() == null) {
                throw new IllegalStateException("Every review feature must provide a document processor");
            }
            String category = normalizeCode(feature.category(), "review category");
            if (registered.putIfAbsent(category, feature) != null) {
                throw new IllegalStateException("Duplicate review category: " + category);
            }
            normalizeCode(feature.permissionCode(), "feature permission code");
            if (feature.defaultFeature()) {
                if (discoveredDefault != null) {
                    throw new IllegalStateException("Multiple default review features are registered");
                }
                discoveredDefault = feature;
            }
        }
        if (discoveredDefault == null) {
            throw new IllegalStateException("Exactly one review feature must be marked as default");
        }
        this.features = Collections.unmodifiableMap(registered);
        this.defaultFeature = discoveredDefault;
    }

    /** Resolve a category supplied when a new task is submitted. */
    public ReviewFeature requireEnabled(String rawCategory) {
        String category = rawCategory == null || rawCategory.isBlank()
                ? defaultCategory() : normalizeCode(rawCategory, "review category");
        ReviewFeature feature = features.get(category);
        if (feature == null || !feature.enabled()) {
            throw new IllegalArgumentException("暂不支持该审查类别：" + category);
        }
        return feature;
    }

    /** Resolve the processor for an already persisted task. */
    public ReviewFeature requireRegistered(String storedCategory) {
        String category = resolveStoredCategory(storedCategory);
        ReviewFeature feature = features.get(category);
        if (feature == null) {
            throw new IllegalArgumentException("未注册该审查类别的处理器：" + category);
        }
        return feature;
    }

    /** Keep legacy tasks readable while normalizing known/non-empty stored codes. */
    public String resolveStoredCategory(String storedCategory) {
        return storedCategory == null || storedCategory.isBlank()
                ? defaultCategory() : normalizeCode(storedCategory, "review category");
    }

    public String defaultCategory() {
        return normalizeCode(defaultFeature.category(), "review category");
    }

    public String permissionCode(ReviewFeature feature) {
        if (feature == null) {
            throw new IllegalArgumentException("review feature must not be null");
        }
        return normalizeCode(feature.permissionCode(), "feature permission code");
    }

    public List<ReviewFeature> allFeatures() {
        return List.copyOf(features.values());
    }

    public Set<String> enabledPermissionCodes() {
        java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
        for (ReviewFeature feature : features.values()) {
            if (feature.enabled()) {
                codes.add(permissionCode(feature));
            }
        }
        return Collections.unmodifiableSet(codes);
    }

    private static String normalizeCode(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
