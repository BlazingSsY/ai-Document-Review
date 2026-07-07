package com.aireview.review.chunk.service;

import com.aireview.document.ChunkUtils;
import com.aireview.document.WordParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 诊断性测试：验证"当前系统能否从待审查文档的 7.1 试验概述检测到全部试验项目"。
 *
 * <p>跑的是真实管线逻辑：{@link WordParser#parseChapters} 解析 .docx →
 * {@link ReviewService#extractDeclaredTestItems} 提取声明的试验项目清单 →
 * {@link ReviewService#isTestItemChapter} 判定哪些一级标题是"试验项目章节"。
 *
 * <p>用法（容器内 maven 运行）：
 * <pre>
 *   mvn -B test -Dtest=TestItemDetectionTest -Ddocs=/repo/prompts
 *   mvn -B test -Dtest=TestItemDetectionTest -Ddoc=/repo/prompts/某大纲.docx
 * </pre>
 * 不指定时按 prompts / ../prompts 目录自动扫描全部 .docx。该测试只打印结果、不强断言，
 * 便于直接看到每份文档的检测覆盖情况。
 */
class TestItemDetectionTest {

    @Test
    void detectTestItemsFromOverview() throws Exception {
        List<Path> docs = resolveDocs();
        if (docs.isEmpty()) {
            System.out.println("[TID] 未找到任何 .docx；用 -Ddoc=<文件> 或 -Ddocs=<目录> 指定。");
            return;
        }
        System.out.println("[TID] 待检测文档数: " + docs.size());

        for (Path doc : docs) {
            System.out.println("\n[TID] ========== 文档: " + doc.getFileName() + " ==========");
            List<WordParser.Chapter> raw;
            try {
                raw = WordParser.parseChapters(doc.toString());
            } catch (Exception e) {
                System.out.println("[TID] 解析失败: " + e.getMessage());
                continue;
            }
            // 与生产一致：跳过封面/目录等前置内容
            int firstReal = ChunkUtils.findFirstRealChapterIndex(raw);
            List<WordParser.Chapter> chapters = firstReal > 0
                    ? new ArrayList<>(raw.subList(firstReal, raw.size())) : raw;
            System.out.println("[TID] 一级标题章节数(去前置): " + chapters.size());

            if (Boolean.getBoolean("dump")) {
                System.out.println("[TID] --- 全部一级标题 ---");
                for (int i = 0; i < chapters.size(); i++) {
                    System.out.println("       #" + (i + 1) + "  " + chapters.get(i).getTitle());
                }
                // 打印被定位到的"试验概述"章原文（提取就是基于它），看清 7.1 到底怎么写的
                WordParser.Chapter ov = null;
                for (WordParser.Chapter ch : chapters) {
                    String t = ch.getTitle() == null ? "" : ch.getTitle();
                    if (t.contains("试验概述") || t.contains("试验项目概述")) { ov = ch; break; }
                }
                if (ov == null) {
                    for (WordParser.Chapter ch : chapters) {
                        String c = ch.getContent();
                        if (c != null && (c.contains("试验项目有") || c.contains("鉴定试验项目有"))) { ov = ch; break; }
                    }
                }
                System.out.println("[TID] --- 定位到的试验概述章: " + (ov == null ? "未找到" : ov.getTitle()) + " ---");
                if (ov != null) {
                    String c = ov.getContent() == null ? "" : ov.getContent();
                    System.out.println("[TID] --- 该章原文(前 2000 字) ---");
                    System.out.println(c.length() > 2000 ? c.substring(0, 2000) : c);
                    System.out.println("[TID] --- 原文结束 ---");
                }
            }

            List<String> items = ReviewService.extractDeclaredTestItems(chapters);
            System.out.println("[TID] 从试验概述(7.1)提取到试验项目 " + items.size() + " 个:");
            System.out.println("       " + items);

            if (items.isEmpty()) {
                System.out.println("[TID] ⚠ 未能从 7.1 提取出任何试验项目——"
                        + "可能是概述用表格列项、或措辞不含『试验项目有：…』。test_item_chapter 规则在此文档将不作用于任何章。");
                continue;
            }

            // 哪些一级标题被识别为"试验项目章节"（= test_item_chapter 规则会作用到的章）
            List<String> testChapters = chapters.stream()
                    .map(WordParser.Chapter::getTitle)
                    .filter(t -> ReviewService.isTestItemChapter(t, items))
                    .collect(Collectors.toList());
            System.out.println("[TID] 识别为『试验项目章节』的一级标题 " + testChapters.size() + " 个:");
            testChapters.forEach(t -> System.out.println("       - " + t));

            // 覆盖核对：7.1 声明的每个项目，是否能在一级标题里找到对应章节
            int covered = 0;
            System.out.println("[TID] 声明项目 → 是否找到对应一级标题章节:");
            for (String it : items) {
                String hit = chapters.stream()
                        .map(WordParser.Chapter::getTitle)
                        .filter(t -> ReviewService.isTestItemChapter(t, List.of(it)))
                        .findFirst().orElse(null);
                if (hit != null) covered++;
                System.out.println("       [" + (hit != null ? "✓" : "✗ 未匹配到章节")
                        + "] " + it + (hit != null ? "  ->  " + hit : ""));
            }
            System.out.println("[TID] 覆盖率: " + covered + "/" + items.size()
                    + " 个声明项目能匹配到对应章节。");
        }
    }

    private List<Path> resolveDocs() throws Exception {
        String one = System.getProperty("doc");
        if (one != null && !one.isBlank()) return List.of(Path.of(one));

        List<String> candidates = new ArrayList<>();
        String dir = System.getProperty("docs");
        if (dir != null && !dir.isBlank()) candidates.add(dir);
        candidates.add("prompts");
        candidates.add("../prompts");

        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) {
                try (var s = Files.list(p)) {
                    List<Path> r = s.filter(f -> f.toString().toLowerCase().endsWith(".docx"))
                            .filter(f -> !f.getFileName().toString().startsWith("~$")) // 跳过 Office 临时锁文件
                            .sorted().collect(Collectors.toList());
                    if (!r.isEmpty()) return r;
                }
            }
        }
        return List.of();
    }
}
