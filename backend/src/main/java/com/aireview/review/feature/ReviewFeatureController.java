package com.aireview.review.feature;

import com.aireview.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only feature catalog; populated automatically from registered feature modules. */
@RestController
@RequestMapping("/api/v1/review-features")
public class ReviewFeatureController {

    private final ReviewFeatureRegistry registry;

    public ReviewFeatureController(ReviewFeatureRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ApiResponse<List<ReviewFeatureDTO>> listFeatures() {
        return ApiResponse.success(registry.allFeatures().stream()
                .map(feature -> new ReviewFeatureDTO(
                        registry.resolveStoredCategory(feature.category()),
                        feature.displayName(),
                        feature.description(),
                        feature.enabled(),
                        feature.defaultFeature()))
                .toList());
    }
}
