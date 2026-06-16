package com.aireview.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detect cross-chapter references inside a chunk ("见第X章", "参见 4.5 条",
 * "如附录A所述", ...) and resolve them to the document's actual chapter list,
 * so the dispatcher can attach the referenced chapters' bodies to the AI prompt
 * as supporting context.
 *
 * <p>Two reference families are supported:
 * <ol>
 *   <li>Numeric references: a chapter number / sub-section number (e.g. "4.5"
 *       or "13") preceded by an explicit reference verb such as 见 / 参见 /
 *       参考 / 详见 / 见第 / 参见第 ... and followed by 章 / 节 / 条 / 款 (when
 *       present). The number is matched against each chapter's title prefix.</li>
 *   <li>Title references: an exact (case-insensitive) match of another chapter's
 *       title that is preceded by 见 / 参见 / 详见 / 参考 within ~30 chars.
 *       This is intentionally restrictive — we never match a bare title token to
 *       avoid false positives.</li>
 * </ol>
 *
 * The current chapter (matched by title) is always excluded from the result.
 */
@Slf4j
public class ChapterReferenceResolver {

    private ChapterReferenceResolver() { }

    private static final Pattern NUMERIC_REF = Pattern.compile(
            // Allowed verbs (Chinese): 见/参见/参考/详见/具体见/详细见/详细参见; English: see/refer to
            "(?:见|参见|参考|详见|具体见|详细见|详细参见|see|refer to)\\s*第?\\s*"
            // Capture the chapter number, e.g. "13", "4.5", "4.5.1"
            + "([0-9]+(?:\\.[0-9]+)*)\\s*"
            // Optional unit suffix
            + "(?:章|节|条|款|部分|chapter|section|clause|appendix)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Find indices of chapters that {@code chunkContent} explicitly references.
     * The chapter matching {@code currentChapterTitle} is skipped so a chunk
     * never references itself.
     *
     * @param chunkContent          the text being reviewed
     * @param currentChapterTitle   title of the chapter the chunk belongs to (for self-skip)
     * @param chapters              all chapters parsed from the document, in document order
     * @return ordered set of chapter indices to include as supporting context (may be empty)
     */
    public static Set<Integer> findReferencedChapters(String chunkContent,
                                                      String currentChapterTitle,
                                                      List<WordParser.Chapter> chapters) {
        Set<Integer> hits = new LinkedHashSet<>();
        if (chunkContent == null || chunkContent.isEmpty() || chapters == null || chapters.isEmpty()) {
            return hits;
        }

        // Pass 1: numeric references (most common in 工程试验大纲 documents)
        Matcher m = NUMERIC_REF.matcher(chunkContent);
        while (m.find()) {
            String num = m.group(1);
            boolean matched = addChaptersStartingWith(num, currentChapterTitle, chapters, hits);
            // Sub-section references like "见5.5节" rarely match a top-level (H1) chapter
            // title directly, since chapters are split by H1 only. Fall back to the containing
            // top-level chapter (the segment before the first dot, e.g. "5"), whose chunk
            // holds that sub-section — so "见X.X章节" still pulls the right chunk as context.
            if (!matched && num.contains(".")) {
                String topLevel = num.substring(0, num.indexOf('.'));
                addChaptersStartingWith(topLevel, currentChapterTitle, chapters, hits);
            }
        }

        // Pass 2: title references — only when preceded by an explicit verb so
        // we don't incorrectly pull in a chapter just because its title appears
        // somewhere in body text (e.g. as a label inside a table).
        String contentLower = chunkContent.toLowerCase(Locale.ROOT);
        for (int i = 0; i < chapters.size(); i++) {
            WordParser.Chapter ch = chapters.get(i);
            if (ch == null || ch.getTitle() == null || ch.getTitle().isBlank()) continue;
            if (sameChapter(ch.getTitle(), currentChapterTitle)) continue;
            String titleNoNumber = stripLeadingNumber(ch.getTitle()).trim();
            if (titleNoNumber.length() < 3) continue; // too short → ambiguous
            String key = titleNoNumber.toLowerCase(Locale.ROOT);
            int idx = 0;
            while ((idx = contentLower.indexOf(key, idx)) >= 0) {
                int windowStart = Math.max(0, idx - 30);
                String window = contentLower.substring(windowStart, idx);
                if (window.contains("见") || window.contains("参考") || window.contains("详见")
                        || window.contains("参见") || window.contains("see ")
                        || window.contains("refer to")) {
                    hits.add(i);
                    break;
                }
                idx += key.length();
            }
        }

        return hits;
    }

    /**
     * Add every chapter whose title prefix matches {@code num} (skipping the current chapter)
     * to {@code hits}. Returns true if at least one chapter matched.
     */
    private static boolean addChaptersStartingWith(String num, String currentChapterTitle,
                                                   List<WordParser.Chapter> chapters,
                                                   Set<Integer> hits) {
        boolean matched = false;
        for (int i = 0; i < chapters.size(); i++) {
            WordParser.Chapter ch = chapters.get(i);
            if (ch == null || ch.getTitle() == null || ch.getTitle().isBlank()) continue;
            if (sameChapter(ch.getTitle(), currentChapterTitle)) continue;
            if (titleStartsWithNumber(ch.getTitle(), num)) {
                hits.add(i);
                matched = true;
            }
        }
        return matched;
    }

    /** Two chapter titles refer to the same chapter when their normalised forms match exactly. */
    private static boolean sameChapter(String a, String b) {
        if (a == null || b == null) return false;
        return normalise(a).equals(normalise(b));
    }

    private static String normalise(String s) {
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * True when {@code title}'s leading numeric prefix equals {@code num}. We
     * match the boundary explicitly (whitespace, dot, dash, 中文标点) so "13"
     * doesn't accidentally match a chapter titled "131".
     */
    private static boolean titleStartsWithNumber(String title, String num) {
        if (title == null || num == null) return false;
        String t = title.trim();
        if (!t.startsWith(num)) return false;
        if (t.length() == num.length()) return true;
        char next = t.charAt(num.length());
        return next == ' ' || next == '\t' || next == '.' || next == '、'
                || next == '-' || next == '—' || next == ':' || next == '：';
    }

    private static String stripLeadingNumber(String title) {
        if (title == null) return "";
        return title.replaceFirst("^\\s*[0-9]+(?:\\.[0-9]+)*\\s*[、.\\-—:：]?\\s*", "");
    }

    /** Render the referenced chapters as a clearly-delimited supporting-context block. */
    public static String renderSupportingContext(Set<Integer> referencedIndices,
                                                 List<WordParser.Chapter> chapters) {
        if (referencedIndices == null || referencedIndices.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== 以下为本章节引用的其他章节内容（仅供上下文参考，请勿将本次审查规则应用到这些被引用章节上） ===\n");
        List<Integer> ordered = new ArrayList<>(referencedIndices);
        for (int idx : ordered) {
            if (idx < 0 || idx >= chapters.size()) continue;
            WordParser.Chapter ref = chapters.get(idx);
            String title = Objects.toString(ref.getTitle(), "(无标题章节)");
            sb.append("\n【引用章节】").append(title).append("\n");
            sb.append(ref.getContent() == null ? "" : ref.getContent());
            sb.append("\n");
        }
        sb.append("=== 引用章节内容结束 ===\n");
        return sb.toString();
    }
}
