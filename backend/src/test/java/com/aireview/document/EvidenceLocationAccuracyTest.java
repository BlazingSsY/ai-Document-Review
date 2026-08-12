package com.aireview.document;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原文定位精度回归。覆盖三类曾经导致「审查项写的原文 ≠ 右侧高亮原文」的缺陷：
 * 合并通用段只带首章节点、短节点反向包含抢匹配、多候选 min/max 跨度过大。
 */
class EvidenceLocationAccuracyTest {

    // ---------- 合并通用段必须携带全部被合并章节的节点与 HTML ----------

    @Test
    void generalSectionChunkExposesNodesFromEveryMergedChapter() {
        List<WordParser.Chapter> chapters = List.of(
                chapter(1, "试验目的", "本大纲规定了机载设备环境鉴定试验的目的与依据。"),
                chapter(2, "试验概述", "试验项目一览表见表 3，共计 21 项试验。"),
                chapter(3, "设备功能检查", "鉴定性能接受试验 QPAT 分为试验前、试验中、试验后三组检查。"),
                chapter(4, "温度和高度试验", "低温工作试验温度为 -55 摄氏度。"));

        List<ChunkUtils.ChunkResult> chunks = ChunkUtils.chunkWithGeneralSection(chapters, 25600, 3);
        ChunkUtils.ChunkResult general = chunks.get(0);
        assertThat(general.isGeneralSection()).isTrue();

        List<String> nodeTexts = general.getSourceNodes().stream()
                .map(WordParser.DocumentNode::getText)
                .toList();
        assertThat(nodeTexts)
                .as("合并片必须带出全部 3 章的节点，否则第 2、3 章的证据永远定位不到")
                .anyMatch(t -> t.contains("试验项目一览表"))
                .anyMatch(t -> t.contains("QPAT"));

        assertThat(general.getSourceHtml())
                .as("右侧原文面板渲染的是这段 HTML，缺章即为「对不上」")
                .contains("试验项目一览表")
                .contains("QPAT");
    }

    @Test
    void evidenceFromALaterMergedChapterResolvesToThatChapter() {
        List<WordParser.Chapter> chapters = List.of(
                chapter(1, "试验目的", "本大纲规定了机载设备环境鉴定试验的目的与依据。"),
                chapter(2, "试验概述", "试验项目一览表见表 3，共计 21 项试验。"),
                chapter(3, "设备功能检查", "鉴定性能接受试验 QPAT 分为试验前、试验中、试验后三组检查。"),
                chapter(4, "温度和高度试验", "低温工作试验温度为 -55 摄氏度。"));

        ChunkUtils.ChunkResult general =
                ChunkUtils.chunkWithGeneralSection(chapters, 25600, 3).get(0);

        DocumentEvidenceLocator.NodeRange range = DocumentEvidenceLocator.locate(
                        general.getSourceNodes(),
                        "原文“鉴定性能接受试验 QPAT 分为试验前、试验中、试验后三组检查”未说明各章选取关系")
                .orElseThrow();

        assertThat(range.sectionPath())
                .as("证据出自第 3 章，定位就必须落在第 3 章")
                .isEqualTo("设备功能检查");
    }

    // ---------- 短节点不得靠反向包含抢走匹配 ----------

    @Test
    void doesNotAnchorToAShortHeadingMerelyContainedInTheEvidence() {
        List<WordParser.DocumentNode> nodes = List.of(
                node("N-001", 1, "9 试验承试单位", "试验人员与承试单位"),
                node("N-002", 2, "9 试验承试单位", "承试单位为某检测中心，试验地点位于其电磁兼容实验室。"),
                node("N-003", 3, "9 试验承试单位",
                        "本大纲未对试验人员与承试单位的资质提出要求，需补充资质条款。"));

        DocumentEvidenceLocator.NodeRange range = DocumentEvidenceLocator.locate(
                        nodes, "原文“本大纲未对试验人员与承试单位的资质提出要求”")
                .orElseThrow();

        assertThat(range.startNodeId())
                .as("标题节点「试验人员与承试单位」被证据整体包含，不能因此抢在正文之前命中")
                .isEqualTo("N-003");
        assertThat(range.endNodeId()).isEqualTo("N-003");
    }

    // ---------- 多候选不得把无关节点之间的整段全高亮 ----------

    @Test
    void doesNotSpanUnrelatedFarApartNodes() {
        List<WordParser.DocumentNode> nodes = new ArrayList<>();
        nodes.add(node("N-001", 1, "7 试验概述", "试验项目一览表共列出 21 项试验项目。"));
        for (int i = 2; i <= 30; i++) {
            nodes.add(node("N-" + String.format("%03d", i), i, "7 试验概述",
                    "第 " + i + " 项试验的实施要求见对应章节，此处不再展开描述内容。"));
        }
        nodes.add(node("N-031", 31, "7 试验概述", "试验等级的取值依据设备安装区域确定。"));

        DocumentEvidenceLocator.NodeRange range = DocumentEvidenceLocator.locate(
                        nodes,
                        "原文“试验项目一览表共列出 21 项试验项目”与“试验等级的取值依据设备安装区域确定”存在矛盾")
                .orElseThrow();

        int start = indexOf(nodes, range.startNodeId());
        int end = indexOf(nodes, range.endNodeId());
        assertThat(end - start)
                .as("两条证据相距 30 个节点，不能把中间无关内容整段高亮")
                .isLessThanOrEqualTo(4);
    }

    @Test
    void stillSpansAdjacentQuotedEvidence() {
        List<WordParser.DocumentNode> nodes = List.of(
                node("N-010", 10, "5 试验步骤", "5.3.1 使用样件完成试验并记录数据。"),
                node("N-011", 11, "5 试验步骤", "5.3.2 使用试件记录数据并形成报告。"));

        DocumentEvidenceLocator.NodeRange range = DocumentEvidenceLocator.locate(
                        nodes,
                        "原文“5.3.1 使用样件完成试验并记录数据”和“5.3.2 使用试件记录数据并形成报告”术语不一致")
                .orElseThrow();

        assertThat(range.startNodeId()).isEqualTo("N-010");
        assertThat(range.endNodeId()).isEqualTo("N-011");
    }

    @Test
    void returnsEmptyWhenEvidenceIsPureParaphraseWithNoAnchor() {
        List<WordParser.DocumentNode> nodes = List.of(
                node("N-001", 1, "8 试验通用要求", "试验环境为温度 15 至 35 摄氏度，相对湿度 20% 至 80%。"),
                node("N-002", 2, "8 试验通用要求", "试验条件允许误差按各章规定执行。"));

        assertThat(DocumentEvidenceLocator.locate(nodes, "本章缺少对试验夹具刚度的量化要求"))
                .as("宁可不高亮，也不要高亮到无关段落")
                .isEmpty();
    }

    // ---------- helpers ----------

    private static int indexOf(List<WordParser.DocumentNode> nodes, String id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getId().equals(id)) return i;
        }
        return -1;
    }

    private static WordParser.DocumentNode node(String id, int index, String section, String text) {
        return new WordParser.DocumentNode(
                id, "paragraph", index, 0, section, text, text, "<p>" + text + "</p>", null,
                index, "");
    }

    /** 构造一个带标题节点 + 一个正文节点的章节，节点 id 与真实解析一致（DOC-Cnnn-Nnnnn）。 */
    private static WordParser.Chapter chapter(int number, String title, String body) {
        String chapterId = String.format("DOC-C%03d", number);
        List<WordParser.DocumentNode> nodes = List.of(
                new WordParser.DocumentNode(chapterId + "-N0001", "chapter_title", 1, 1,
                        title, title, title, "<h1 data-node-id=\"" + chapterId + "-N0001\">"
                        + title + "</h1>", null, -1, ""),
                new WordParser.DocumentNode(chapterId + "-N0002", "paragraph", 2, 0,
                        title, body, body, "<p data-node-id=\"" + chapterId + "-N0002\">"
                        + body + "</p>", null, 0, ""));
        String html = nodes.stream().map(WordParser.DocumentNode::getHtml)
                .reduce("", String::concat);
        return new WordParser.Chapter(chapterId, title, body, body, html, nodes);
    }
}
