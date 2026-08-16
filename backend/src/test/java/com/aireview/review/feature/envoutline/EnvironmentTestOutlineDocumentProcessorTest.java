package com.aireview.review.feature.envoutline;

import com.aireview.document.WordParser;
import com.aireview.review.feature.ChapterReviewPlan;
import com.aireview.review.feature.ReviewDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentTestOutlineDocumentProcessorTest {

    private final EnvironmentTestOutlineDocumentProcessor processor =
            new EnvironmentTestOutlineDocumentProcessor();

    @Test
    void outlineSpecificDetectionAndChunkBoundaryStayInsideTheFeatureModule() {
        List<WordParser.Chapter> chapters = List.of(
                chapter("", "封面信息"),
                chapter("1 目的", "用于环境鉴定。"),
                chapter("7.1 试验概述", "本设备鉴定试验项目有：振动试验、湿热试验等。"),
                chapter("8 振动试验", "振动试验程序。"),
                chapter("9 湿热试验", "湿热试验程序。"));
        List<String> declared = EnvironmentTestOutlineDocumentProcessor
                .extractDeclaredTestItems(chapters);
        ReviewDocument document = new ReviewDocument(chapters, chapters, declared);

        ChapterReviewPlan plan = processor.planChapterReview(document, 25_600, 0);

        assertThat(declared).containsExactly("振动试验", "湿热试验");
        assertThat(plan.generalSectionEnd()).isEqualTo(3);
        assertThat(plan.chunks()).hasSize(3);
        assertThat(processor.isDomainSection(document, "8 振动试验")).isTrue();
        assertThat(processor.isDomainSection(document, "")).isFalse();
    }

    @Test
    void validatesItsOwnSupportedFileTypes() {
        processor.validateUpload("环境鉴定大纲.DOCX");

        assertThatThrownBy(() -> processor.validateUpload("试验报告.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Word");
    }

    private static WordParser.Chapter chapter(String title, String content) {
        return new WordParser.Chapter(title, content);
    }
}
