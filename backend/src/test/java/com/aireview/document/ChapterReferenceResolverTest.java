package com.aireview.document;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨章引用识别。被引用章节的正文会作为上下文拼进审查提示词，漏识别会让模型看不到被引用
 * 内容，只能判 Review（「被引用内容未在本次输入中提供，无法核验」），是误报的主要来源之一。
 */
class ChapterReferenceResolverTest {

    private static final List<WordParser.Chapter> CHAPTERS = List.of(
            new WordParser.Chapter("6 试验设备描述", "受试设备构型与工作状态。"),
            new WordParser.Chapter("12 设备功能检查", "表12 鉴定性能接收试验（QPAT）。"),
            new WordParser.Chapter("13 温度和高度", "温度高度试验条件。"),
            new WordParser.Chapter("14 温度变化试验", "温度变化试验条件。"));

    private static Set<Integer> resolve(String text, String current) {
        return ChapterReferenceResolver.findReferencedChapters(text, current, CHAPTERS);
    }

    @Test
    void resolvesExplicitReferenceVerbs() {
        assertThat(resolve("详见6.4节受试设备工作状态", "14 温度变化试验")).containsExactly(0);
        assertThat(resolve("试验条件见13章", "14 温度变化试验")).containsExactly(2);
    }

    @Test
    void resolvesWeakVerbsThatDominateEngineeringDocuments() {
        // 试验大纲里「按照X章节」远比「见X章」高频：实测一份大纲中前者 376 处、后者 86 处。
        assertThat(resolve("按照12章节表12 QPAT1检测项对受试设备进行检测", "14 温度变化试验"))
                .containsExactly(1);
        assertThat(resolve("试验前，按12.2章节表12要求进行QPAT1检查", "13 温度和高度"))
                .containsExactly(1);
        assertThat(resolve("可按照13.7.4章节高温工作继续进行试验", "14 温度变化试验"))
                .containsExactly(2);
    }

    @Test
    void weakVerbsRequireASectionUnitSoMeasurementsAreNotMistakenForChapters() {
        // 「按5℃/min」「按2h」里的数字是量值不是章号；弱引导词强制要求章/节/条/款后缀，
        // 否则整份大纲的速率、时长都会被当成跨章引用，把无关章节拖进上下文。
        assertThat(resolve("按5℃/min的变化速率将箱温降至低温工作温度", "14 温度变化试验")).isEmpty();
        assertThat(resolve("按2h保持并使设备达到温度稳定", "13 温度和高度")).isEmpty();
        assertThat(resolve("按照6个方向各施加3次", "13 温度和高度")).isEmpty();
    }

    @Test
    void neverReferencesItsOwnChapter() {
        assertThat(resolve("按照13章节的要求执行", "13 温度和高度")).isEmpty();
    }
}
