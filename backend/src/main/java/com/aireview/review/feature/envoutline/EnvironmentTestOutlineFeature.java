package com.aireview.review.feature.envoutline;

import com.aireview.review.feature.ReviewDocumentProcessor;
import com.aireview.review.feature.ReviewFeature;
import org.springframework.stereotype.Component;

/** Environment qualification test-outline review module. */
@Component
public class EnvironmentTestOutlineFeature implements ReviewFeature {

    public static final String CATEGORY = "ENV_TEST_OUTLINE";
    public static final String PERMISSION_CODE = "ENV_TEST_OUTLINE_REVIEW";

    private final EnvironmentTestOutlineDocumentProcessor documentProcessor;

    public EnvironmentTestOutlineFeature(EnvironmentTestOutlineDocumentProcessor documentProcessor) {
        this.documentProcessor = documentProcessor;
    }

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    public String displayName() {
        return "环境试验大纲审查";
    }

    @Override
    public String description() {
        return "使用全文逐章或结构化精准管线，对环境鉴定试验大纲进行合规性审查";
    }

    @Override
    public String permissionCode() {
        return PERMISSION_CODE;
    }

    @Override
    public ReviewDocumentProcessor documentProcessor() {
        return documentProcessor;
    }

    @Override
    public boolean defaultFeature() {
        return true;
    }

    @Override
    public boolean usesSharedRuleLibraries() {
        return true;
    }
}
