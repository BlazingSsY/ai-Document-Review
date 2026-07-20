package com.aireview.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentEvidenceLocatorTest {

    @Test
    void locatesQuotedEvidenceWithoutTreatingTheExplanationAsSourceText() {
        List<WordParser.DocumentNode> nodes = List.of(
                node("N-001", 1, "3 试验对象与配置", "设备型号为 AFDX-200A。"),
                node("N-002", 2, "3 试验对象与配置", "试验期间设备保持正常工作。"));

        DocumentEvidenceLocator.NodeRange range = DocumentEvidenceLocator.locate(
                nodes,
                "原文“设备型号为 AFDX-200A”与配置清单不一致")
                .orElseThrow();

        assertThat(range.startNodeId()).isEqualTo("N-001");
        assertThat(range.endNodeId()).isEqualTo("N-001");
        assertThat(range.sectionPath()).isEqualTo("3 试验对象与配置");
    }

    @Test
    void returnsRangeAcrossTwoQuotedEvidenceNodes() {
        List<WordParser.DocumentNode> nodes = List.of(
                node("N-010", 10, "5 试验步骤", "5.3.1 使用样件完成试验。"),
                node("N-011", 11, "5 试验步骤", "5.3.2 使用试件记录数据。"));

        DocumentEvidenceLocator.NodeRange range = DocumentEvidenceLocator.locate(
                nodes,
                "原文“5.3.1 使用样件完成试验”和“5.3.2 使用试件记录数据”术语不一致")
                .orElseThrow();

        assertThat(range.startNodeId()).isEqualTo("N-010");
        assertThat(range.endNodeId()).isEqualTo("N-011");
    }

    @Test
    void locatesMarkdownTableEvidenceInPlainTableNodeText() {
        List<WordParser.DocumentNode> nodes = List.of(
                node("T-001", 20, "3 试验对象与配置",
                        "1 机载数据网络交换单元 AFDX-200A 1台 试验件"));

        DocumentEvidenceLocator.NodeRange range = DocumentEvidenceLocator.locate(
                nodes,
                "| 1 | 机载数据网络交换单元 | AFDX-200A | 1台 | 试验件 |")
                .orElseThrow();

        assertThat(range.startNodeId()).isEqualTo("T-001");
        assertThat(range.endNodeId()).isEqualTo("T-001");
    }

    private static WordParser.DocumentNode node(String id, int index, String section, String text) {
        return new WordParser.DocumentNode(
                id, "paragraph", index, 0, section, text, text, "<p>" + text + "</p>", null);
    }
}
