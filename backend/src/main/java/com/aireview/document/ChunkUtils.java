package com.aireview.document;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for splitting large text into chunks suitable for AI model context windows.
 * Supports chapter-aware splitting: each chapter becomes one chunk unless it exceeds maxTokens,
 * in which case it is further split by paragraphs/sentences.
 */
@Slf4j
public class ChunkUtils {

    private ChunkUtils() {
    }

    /**
     * Estimate token count for a given text.
     * CJK characters count as ~1 token each, other characters ~4 chars per token.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkCount = 0;
        int otherCharCount = 0;
        for (char c : text.toCharArray()) {
            if (isCjk(c)) {
                cjkCount++;
            } else {
                otherCharCount++;
            }
        }
        return cjkCount + (otherCharCount / 4);
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }

    /**
     * Find the index of the first "real" chapter — the first heading whose title starts
     * with a chapter number ("1", "1.1", "一、", "第1章"...) followed by a non-numeric
     * chapter name. Everything before this point is treated as front matter (大标题、
     * 签署页、目录、图表清单 etc.) and skipped from per-chapter AI review. The caller may
     * still retain these regions as evidence for document-level rules.
     *
     * <p>Returns 0 if no numbered chapter is found, so behaviour is unchanged for
     * documents whose first content is already a real chapter.
     */
    public static int findFirstRealChapterIndex(List<WordParser.Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return 0;
        java.util.regex.Pattern numberedTitle = java.util.regex.Pattern.compile(
                "^\\s*(?:第\\s*)?(?:[0-9]+(?:\\.[0-9]+)*|[一二三四五六七八九十百零]+)" +
                "\\s*(?:章|节|篇|部分|、|\\.|\\s|[\\u4e00-\\u9fa5a-zA-Z]).*");
        for (int i = 0; i < chapters.size(); i++) {
            WordParser.Chapter ch = chapters.get(i);
            if (ch == null) continue;
            String title = ch.getTitle();
            String body = ch.getContent();
            if (title == null || title.isBlank()) continue;
            if (body == null || body.isBlank()) continue;
            if (numberedTitle.matcher(title.trim()).matches()) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Split chapters into chunks. Each chapter that fits within maxTokens becomes one chunk.
     * Chapters exceeding maxTokens are further split by paragraphs and sentences.
     * Empty / whitespace-only chapters are skipped so they never reach the AI model.
     *
     * @param chapters   list of chapter objects from WordParser
     * @param maxTokens  maximum tokens per chunk (e.g. 25600)
     * @return list of chunks, each with a label (chapter title) and content
     */
    public static List<ChunkResult> chunkByChapters(List<WordParser.Chapter> chapters, int maxTokens) {
        List<ChunkResult> results = new ArrayList<>();

        for (int i = 0; i < chapters.size(); i++) {
            WordParser.Chapter chapter = chapters.get(i);
            String fullText = chapter.getFullText();
            int tokens = estimateTokens(fullText);
            String label = chapter.getTitle() != null && !chapter.getTitle().isBlank()
                    ? chapter.getTitle()
                    : "第 " + (i + 1) + " 部分";

            // Skip empty / whitespace-only chapters and stray headings whose body is empty.
            // These produce 0-token chunks that waste an AI call and clutter the result file.
            if (tokens == 0 || fullText == null || fullText.isBlank()
                    || (chapter.getContent() != null && chapter.getContent().isBlank()
                        && tokens < 5)) {
                log.info("Skipping empty / near-empty chapter '{}' (tokens={}, len={})",
                        label, tokens, fullText == null ? 0 : fullText.length());
                continue;
            }

            if (tokens <= maxTokens) {
                // Chapter fits in one chunk
                results.add(new ChunkResult(label, fullText, tokens, i, chapter));
            } else {
                // Chapter too large, split by paragraphs
                log.info("Chapter '{}' has ~{} tokens, splitting further (max {})", label, tokens, maxTokens);
                List<String> subChunks = splitLargeText(fullText, maxTokens);
                for (int j = 0; j < subChunks.size(); j++) {
                    String subLabel = label + " (" + (j + 1) + "/" + subChunks.size() + ")";
                    results.add(new ChunkResult(
                            subLabel,
                            subChunks.get(j),
                            estimateTokens(subChunks.get(j)),
                            i,
                            chapter));
                }
            }
        }

        log.info("Chapter-based chunking: {} chapter(s) → {} chunk(s)", chapters.size(), results.size());
        return results;
    }

    /** 通用章节段合并切片的标签。 */
    public static final String GENERAL_SECTION_LABEL = "通用章节（封面/目录及试验项目前各章）";

    /**
     * 按「通用章节段 + 逐个试验项目章节」切片。
     *
     * <p>试验大纲的前半部分（封面、目录、试验目的、范围、引用文件、术语、承试单位、
     * 人员职责……）是一整块相互关联的通用内容：签署是否齐全、目录与正文是否对得上、
     * 术语前后是否一致、引用文件是否都在正文出现过——这些都要跨章看才判得准。逐章切开
     * 送审时，模型每次只看得到其中一章，跨章的问题结构上就发现不了。所以把它们合并成
     * 一个切片，让通用类审查在同一次调用里完成。
     *
     * <p>试验项目章节（温度和高度、振动、霉菌……）则保持一章一片：每章是一个独立的
     * 试验，规则也是按试验项目组织的，合并只会稀释注意力。
     *
     * @param chapters      解析出的全部章节，含封面/目录等前置内容（不要预先裁掉——
     *                      前置内容正是通用段要审的一部分）
     * @param maxTokens     单片 token 上限
     * @param generalEndExclusive 通用段的结束下标（不含）。传 &lt;=0 表示识别不出边界，
     *                      此时整体回退到 {@link #chunkByChapters} 的逐章行为
     */
    public static List<ChunkResult> chunkWithGeneralSection(List<WordParser.Chapter> chapters,
                                                            int maxTokens,
                                                            int generalEndExclusive) {
        if (chapters == null || chapters.isEmpty()) return new ArrayList<>();

        // 识别不出通用段边界时不要勉强合并：宁可退回原有逐章行为，也不能把整份文档
        // 并成一片送审。此时沿用原先「跳过前置内容」的做法，保持既有表现不变。
        if (generalEndExclusive <= 0 || generalEndExclusive > chapters.size()) {
            int firstReal = findFirstRealChapterIndex(chapters);
            List<WordParser.Chapter> reviewable = firstReal > 0
                    ? chapters.subList(firstReal, chapters.size())
                    : chapters;
            log.info("General-section boundary unavailable ({}), falling back to per-chapter chunking",
                    generalEndExclusive);
            return chunkByChapters(reviewable, maxTokens);
        }

        List<ChunkResult> results = new ArrayList<>();

        // ---- 通用章节段 ----
        List<WordParser.Chapter> generalChapters = chapters.subList(0, generalEndExclusive);
        StringBuilder merged = new StringBuilder();
        List<String> mergedTitles = new ArrayList<>();
        for (WordParser.Chapter chapter : generalChapters) {
            String text = chapter.getFullText();
            if (text == null || text.isBlank()) continue;
            if (merged.length() > 0) merged.append("\n\n");
            merged.append(text.trim());
            String title = chapter.getTitle();
            if (title != null && !title.isBlank()) mergedTitles.add(title.trim());
        }

        String generalText = merged.toString().trim();
        if (!generalText.isBlank()) {
            int tokens = estimateTokens(generalText);
            WordParser.Chapter first = generalChapters.isEmpty() ? null : generalChapters.get(0);
            if (tokens <= maxTokens) {
                results.add(new ChunkResult(GENERAL_SECTION_LABEL, generalText, tokens,
                        0, first, true, mergedTitles));
            } else {
                // 超限必须拆——否则这次调用会直接超模型上下文。拆出的每一片仍标记为
                // 通用段并携带同一份标题清单，规则调度因此不受影响，只是模型的可见
                // 范围被迫变窄。
                List<String> parts = splitLargeText(generalText, maxTokens);
                log.warn("General section is ~{} tokens (max {}), split into {} part(s); "
                                + "cross-chapter checks inside it may weaken",
                        tokens, maxTokens, parts.size());
                for (int i = 0; i < parts.size(); i++) {
                    results.add(new ChunkResult(
                            GENERAL_SECTION_LABEL + " (" + (i + 1) + "/" + parts.size() + ")",
                            parts.get(i), estimateTokens(parts.get(i)),
                            0, first, true, mergedTitles));
                }
            }
        }

        // ---- 其余章节：维持逐章切片 ----
        List<WordParser.Chapter> rest = chapters.subList(generalEndExclusive, chapters.size());
        List<ChunkResult> restChunks = chunkByChapters(rest, maxTokens);
        for (ChunkResult chunk : restChunks) {
            // chapterIndex 需要平移回全文坐标，否则 originalSources 的章节定位会错位。
            results.add(new ChunkResult(
                    chunk.getLabel(), chunk.getContent(), chunk.getEstimatedTokens(),
                    chunk.getChapterIndex() < 0
                            ? chunk.getChapterIndex()
                            : chunk.getChapterIndex() + generalEndExclusive,
                    chunk.getSourceChapter(), false, List.of()));
        }

        log.info("General-section chunking: {} chapter(s) → {} chunk(s) "
                        + "(通用段合并 {} 章，其余逐章 {} 片)",
                chapters.size(), results.size(), generalChapters.size(), restChunks.size());
        return results;
    }

    /**
     * Legacy: split plain text into chunks by token count.
     */
    public static List<String> chunkText(String text, int maxTokens, int overlapTokens) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        int totalTokens = estimateTokens(text);
        if (totalTokens <= maxTokens) {
            chunks.add(text);
            return chunks;
        }
        return splitLargeText(text, maxTokens);
    }

    /**
     * Split large text by paragraphs first, then by sentences if needed.
     */
    private static List<String> splitLargeText(String text, int maxTokens) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (String paragraph : paragraphs) {
            int paragraphTokens = estimateTokens(paragraph);

            if (paragraphTokens > maxTokens) {
                // Single paragraph too large, flush current and split by sentences
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
                chunks.addAll(splitBySentences(paragraph, maxTokens));
                continue;
            }

            if (currentTokens + paragraphTokens > maxTokens) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
                currentTokens = 0;
            }

            currentChunk.append(paragraph).append("\n\n");
            currentTokens += paragraphTokens;
        }

        if (currentChunk.length() > 0 && !currentChunk.toString().isBlank()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private static List<String> splitBySentences(String paragraph, int maxTokens) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = paragraph.split("(?<=[.!?;。！？；])\\s*");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (String sentence : sentences) {
            int sentenceTokens = estimateTokens(sentence);
            if (currentTokens + sentenceTokens > maxTokens && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
                currentTokens = 0;
            }
            currentChunk.append(sentence).append(" ");
            currentTokens += sentenceTokens;
        }

        if (currentChunk.length() > 0 && !currentChunk.toString().isBlank()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * Result of chapter-based chunking, includes a label (chapter title) and content.
     */
    public static class ChunkResult {
        private final String label;
        private final String content;
        private final int estimatedTokens;
        private final int chapterIndex;
        private final WordParser.Chapter sourceChapter;
        private final boolean generalSection;
        private final List<String> mergedTitles;

        public ChunkResult(String label, String content, int estimatedTokens) {
            this(label, content, estimatedTokens, -1, null);
        }

        public ChunkResult(String label, String content, int estimatedTokens,
                           int chapterIndex, WordParser.Chapter sourceChapter) {
            this(label, content, estimatedTokens, chapterIndex, sourceChapter, false, List.of());
        }

        public ChunkResult(String label, String content, int estimatedTokens,
                           int chapterIndex, WordParser.Chapter sourceChapter,
                           boolean generalSection, List<String> mergedTitles) {
            this.label = label;
            this.content = content;
            this.estimatedTokens = estimatedTokens;
            this.chapterIndex = chapterIndex;
            this.sourceChapter = sourceChapter;
            this.generalSection = generalSection;
            this.mergedTitles = mergedTitles == null ? List.of() : List.copyOf(mergedTitles);
        }

        public String getLabel() { return label; }
        public String getContent() { return content; }
        public int getEstimatedTokens() { return estimatedTokens; }
        public int getChapterIndex() { return chapterIndex; }
        public WordParser.Chapter getSourceChapter() { return sourceChapter; }

        /** 本片是否属于「通用章节段」（封面/目录 + 试验项目章节之前的通用章节）。 */
        public boolean isGeneralSection() { return generalSection; }

        /**
         * 本片合并了哪些章节的标题。逐章切片时为空。
         *
         * <p>合并片必须把子章节标题带出来，否则 {@code section_specific} 规则会失效——
         * 调度器是拿标题去匹配 keywords/sections 的，十几章并成一片后只剩一个合成标题，
         * 那些针对「引用文件」「相关验证条款」写的专项规则就再也命中不到了。
         */
        public List<String> getMergedTitles() { return mergedTitles; }

        /** 供调度器匹配的标题清单：合并片用子章节标题，普通片用自身标签。 */
        public List<String> titlesForDispatch() {
            return mergedTitles.isEmpty() ? List.of(label == null ? "" : label) : mergedTitles;
        }
    }
}
