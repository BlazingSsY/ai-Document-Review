package com.aireview.util;

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
     * 签署页、目录、图表清单 etc.) and skipped before AI review.
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
                results.add(new ChunkResult(label, fullText, tokens));
            } else {
                // Chapter too large, split by paragraphs
                log.info("Chapter '{}' has ~{} tokens, splitting further (max {})", label, tokens, maxTokens);
                List<String> subChunks = splitLargeText(fullText, maxTokens);
                for (int j = 0; j < subChunks.size(); j++) {
                    String subLabel = label + " (" + (j + 1) + "/" + subChunks.size() + ")";
                    results.add(new ChunkResult(subLabel, subChunks.get(j), estimateTokens(subChunks.get(j))));
                }
            }
        }

        log.info("Chapter-based chunking: {} chapter(s) → {} chunk(s)", chapters.size(), results.size());
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

        public ChunkResult(String label, String content, int estimatedTokens) {
            this.label = label;
            this.content = content;
            this.estimatedTokens = estimatedTokens;
        }

        public String getLabel() { return label; }
        public String getContent() { return content; }
        public int getEstimatedTokens() { return estimatedTokens; }
    }
}
