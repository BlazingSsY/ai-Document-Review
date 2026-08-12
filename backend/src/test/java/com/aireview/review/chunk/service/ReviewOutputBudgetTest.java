package com.aireview.review.chunk.service;

import com.aireview.modelconfig.entity.AiModelConfig;
import com.aireview.modelconfig.service.AiModelService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewOutputBudgetTest {

    @Test
    void growsWithCheckCountAndCapsAtConfiguredMaximum() {
        assertThat(ReviewService.dynamicOutputTokenBudget(0)).isEqualTo(8192);
        assertThat(ReviewService.dynamicOutputTokenBudget(8)).isEqualTo(8192);
        assertThat(ReviewService.dynamicOutputTokenBudget(20)).isEqualTo(14336);
        assertThat(ReviewService.dynamicOutputTokenBudget(100)).isEqualTo(24576);
    }

    @Test
    void removesClosedAndTruncatedReasoningBlocksBeforeJsonParsing() {
        assertThat(ReviewService.stripDelimitedReasoningBlocks(
                "<think>private reasoning</think>{\"summary\":\"final\"}"))
                .isEqualTo("{\"summary\":\"final\"}");
        assertThat(ReviewService.stripDelimitedReasoningBlocks(
                "<analysis>unfinished reasoning"))
                .isEmpty();
        assertThat(ReviewService.stripDelimitedReasoningBlocks(
                "private reasoning</think>{\"summary\":\"final\"}"))
                .isEqualTo("{\"summary\":\"final\"}");
    }

    @Test
    void expandsConfirmedLengthTruncationAndRespectsConfiguredCeiling() {
        AiModelConfig config = new AiModelConfig();
        config.setMaxTokens(128000);
        AiModelService.AiResponseMetadata first = new AiModelService.AiResponseMetadata(
                "length", 0, 18163, 17727, 10754, 28481, 10752, "", "");
        assertThat(ReviewService.nextOutputTokenBudgetAfterLength(config, 10752, 13, first))
                .isEqualTo(32768);

        AiModelService.AiResponseMetadata second = new AiModelService.AiResponseMetadata(
                "length", 0, 40000, 17727, 32768, 50495, 32768, "", "");
        assertThat(ReviewService.nextOutputTokenBudgetAfterLength(config, 32768, 13, second))
                .isEqualTo(65536);

        config.setMaxTokens(32768);
        assertThat(ReviewService.nextOutputTokenBudgetAfterLength(config, 32768, 13, second))
                .isEqualTo(65536);

        AiModelService.AiResponseMetadata completed = new AiModelService.AiResponseMetadata(
                "stop", 8000, 10000, 17727, 18000, 35727, 32768, "", "");
        assertThat(ReviewService.nextOutputTokenBudgetAfterLength(config, 32768, 13, completed))
                .isEqualTo(32768);
    }
}
