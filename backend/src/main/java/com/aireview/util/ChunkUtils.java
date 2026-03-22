package com.aireview.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for splitting large text into chunks suitable for AI model context windows.
 * Uses a token-approximation approach (1 token ~ 4 characters for English, ~2 for CJK).
 */
@Slf4j
public class ChunkUtils {

    private ChunkUtils() {
    }

    /**
     * Estimate token count for a given text.
     * Rough heuristic: count CJK characters as 1 token each, other words as ~1.3 tokens.
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
        // English: ~4 chars per token; CJK: ~1 char per token
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
     * Split text into chunks with a maximum token count and an overlap for context continuity.
     *
     * @param text          the full text to split
     * @param maxTokens     maximum tokens per chunk
     * @param overlapTokens number of overlapping tokens between consecutive chunks
     * @return list of text chunks
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

        // Split by paragraphs first for natural boundaries
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (String paragraph : paragraphs) {
            int paragraphTokens = estimateTokens(paragraph);

            if (paragraphTokens > maxTokens) {
                // Paragraph itself is too large; split by sentences
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = applyOverlap(currentChunk.toString(), overlapTokens);
                    currentTokens = estimateTokens(currentChunk.toString());
                }
                List<String> sentenceChunks = splitLargeParagraph(paragraph, maxTokens, overlapTokens);
                chunks.addAll(sentenceChunks);
                currentChunk = new StringBuilder();
                currentTokens = 0;
                continue;
            }

            if (currentTokens + paragraphTokens > maxTokens) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = applyOverlap(currentChunk.toString(), overlapTokens);
                currentTokens = estimateTokens(currentChunk.toString());
            }

            currentChunk.append(paragraph).append("\n\n");
            currentTokens += paragraphTokens;
        }

        if (currentChunk.length() > 0 && !currentChunk.toString().isBlank()) {
            chunks.add(currentChunk.toString().trim());
        }

        log.info("Text split into {} chunks (total ~{} tokens, max {} per chunk)",
                chunks.size(), totalTokens, maxTokens);
        return chunks;
    }

    private static List<String> splitLargeParagraph(String paragraph, int maxTokens, int overlapTokens) {
        List<String> chunks = new ArrayList<>();
        // Split by sentence-ending punctuation
        String[] sentences = paragraph.split("(?<=[.!?;。！？；])\\s*");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (String sentence : sentences) {
            int sentenceTokens = estimateTokens(sentence);

            if (currentTokens + sentenceTokens > maxTokens && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = applyOverlap(currentChunk.toString(), overlapTokens);
                currentTokens = estimateTokens(currentChunk.toString());
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
     * Create overlap content from the end of the previous chunk.
     */
    private static StringBuilder applyOverlap(String previousChunk, int overlapTokens) {
        if (overlapTokens <= 0 || previousChunk.isEmpty()) {
            return new StringBuilder();
        }

        // Approximate character count for overlap tokens
        int overlapChars = overlapTokens * 4;
        if (overlapChars >= previousChunk.length()) {
            return new StringBuilder(previousChunk);
        }

        String overlap = previousChunk.substring(previousChunk.length() - overlapChars);
        // Align to word boundary
        int spaceIdx = overlap.indexOf(' ');
        if (spaceIdx > 0) {
            overlap = overlap.substring(spaceIdx + 1);
        }

        return new StringBuilder(overlap);
    }
}
