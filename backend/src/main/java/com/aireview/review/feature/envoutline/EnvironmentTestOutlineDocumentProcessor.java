package com.aireview.review.feature.envoutline;

import com.aireview.document.ChunkUtils;
import com.aireview.document.WordParser;
import com.aireview.review.feature.ChapterReviewPlan;
import com.aireview.review.feature.ReviewDocument;
import com.aireview.review.feature.ReviewDocumentProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** All document-shape assumptions that are specific to environment test outlines. */
@Component
public class EnvironmentTestOutlineDocumentProcessor implements ReviewDocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentTestOutlineDocumentProcessor.class);

    private static final Pattern CHAPTER_NUMBER_IN_TITLE =
            Pattern.compile("^\\s*(?:第\\s*)?(\\d+)(?:\\s*章)?(?:\\s|[.．、:：-]|$)");
    private static final Pattern DECLARED_ITEMS =
            Pattern.compile("试验项目有[：:]\\s*(.+?)(?:等|。|见表|具体)");

    @Override
    public void validateUpload(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename.trim().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".doc") && !name.endsWith(".docx")) {
            throw new IllegalArgumentException("环境试验大纲仅支持 Word 文档（.doc、.docx）");
        }
    }

    @Override
    public ReviewDocument parse(String filePath) throws Exception {
        List<WordParser.Chapter> sourceChapters = WordParser.parseChapters(filePath);
        if (sourceChapters.isEmpty()
                || sourceChapters.stream().allMatch(chapter -> chapter.getContent().isBlank())) {
            throw new IllegalArgumentException("文档内容为空或无法解析");
        }

        int firstReal = ChunkUtils.findFirstRealChapterIndex(sourceChapters);
        List<WordParser.Chapter> structuredChapters = firstReal > 0
                ? new ArrayList<>(sourceChapters.subList(firstReal, sourceChapters.size()))
                : sourceChapters;
        return new ReviewDocument(sourceChapters, structuredChapters,
                extractDeclaredTestItems(sourceChapters));
    }

    @Override
    public ChapterReviewPlan planChapterReview(ReviewDocument document,
                                               int maxChunkTokens,
                                               int configuredGeneralSectionEnd) {
        List<WordParser.Chapter> chapters = document.sourceChapters();
        int generalEnd = resolveGeneralSectionEnd(
                chapters, document.declaredDomainSections(), configuredGeneralSectionEnd);
        return new ChapterReviewPlan(chapters,
                ChunkUtils.chunkWithGeneralSection(chapters, maxChunkTokens, generalEnd),
                generalEnd);
    }

    @Override
    public boolean isDomainSection(ReviewDocument document, String chapterTitle) {
        return isTestItemChapter(chapterTitle, document.declaredDomainSections());
    }

    int resolveGeneralSectionEnd(List<WordParser.Chapter> chapters,
                                 List<String> declaredTestItems,
                                 int configuredGeneralSectionEnd) {
        if (chapters == null || chapters.isEmpty()) return -1;

        if (configuredGeneralSectionEnd > 0) {
            for (int i = 0; i < chapters.size(); i++) {
                String title = chapters.get(i).getTitle();
                if (title == null) continue;
                Matcher matcher = CHAPTER_NUMBER_IN_TITLE.matcher(title.trim());
                if (matcher.find()) {
                    try {
                        if (Integer.parseInt(matcher.group(1)) == configuredGeneralSectionEnd) {
                            log.info("General section end pinned by config: chapter {} at index {}",
                                    configuredGeneralSectionEnd, i);
                            return i + 1;
                        }
                    } catch (NumberFormatException ignored) {
                        // Keep looking for a usable chapter number.
                    }
                }
            }
            log.warn("general-section-end-chapter={} not found; falling back to auto detection",
                    configuredGeneralSectionEnd);
        }

        if (declaredTestItems == null || declaredTestItems.isEmpty()) {
            log.info("No declared test items extracted; general-section merging disabled");
            return -1;
        }
        for (int i = 0; i < chapters.size(); i++) {
            if (isTestItemChapter(chapters.get(i).getTitle(), declaredTestItems)) {
                log.info("General section = chapters [0, {}), first test-item chapter: '{}'",
                        i, chapters.get(i).getTitle());
                return i;
            }
        }
        log.info("Declared test items did not match a chapter; general-section merging disabled");
        return -1;
    }

    /** Extract the test-item declaration from outline prose and overview tables. */
    public static List<String> extractDeclaredTestItems(List<WordParser.Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return List.of();
        String text = null;
        for (WordParser.Chapter chapter : chapters) {
            String title = chapter.getTitle() == null ? "" : chapter.getTitle();
            if (title.contains("试验概述") || title.contains("试验项目概述")) {
                text = chapter.getContent();
                break;
            }
        }
        if (text == null) {
            for (WordParser.Chapter chapter : chapters) {
                String content = chapter.getContent();
                if (content != null
                        && (content.contains("试验项目有") || content.contains("鉴定试验项目有"))) {
                    text = content;
                    break;
                }
            }
        }
        if (text == null || text.isBlank()) return List.of();

        List<String> items = new ArrayList<>();
        Matcher matcher = DECLARED_ITEMS.matcher(text);
        while (matcher.find()) {
            for (String part : matcher.group(1).split("[、，,；;/]")) {
                String item = part.trim();
                if (item.length() < 2) continue;
                if (item.contains("试验项目") || item.contains("本设备") || item.contains("应完成")) {
                    continue;
                }
                if (!items.contains(item)) items.add(item);
            }
        }

        for (String line : text.split("\\r?\\n")) {
            if (line.indexOf('|') < 0) continue;
            for (String cell : line.split("\\|")) {
                String value = cell.trim();
                if (value.length() < 3 || value.length() > 16 || !value.endsWith("试验")) continue;
                if (value.matches(".*[0-9A-Za-z：:].*")) continue;
                if (value.contains("条") || value.contains("类")
                        || value.contains("记录") || value.contains("报告")) continue;
                String core = value.replaceAll("(试验项目|试验)$", "").trim();
                if (core.length() >= 2 && !items.contains(core)) items.add(core);
            }
        }
        return List.copyOf(items);
    }

    /** Match a declared test item to its corresponding top-level chapter. */
    public static boolean isTestItemChapter(String chapterTitle, List<String> declaredTestItems) {
        if (chapterTitle == null || declaredTestItems == null || declaredTestItems.isEmpty()) return false;
        String title = chapterTitle.trim();
        if (title.isEmpty()) return false;
        String core = normalizeTitleCore(title);
        if (core.isEmpty()) core = title;
        for (String item : declaredTestItems) {
            String declared = item == null ? "" : item.trim();
            if (declared.length() < 2) continue;
            String declaredCore = normalizeTitleCore(declared);
            if (declaredCore.isEmpty()) declaredCore = declared;
            if (title.contains(declared) || declared.contains(core) || core.contains(declared)) return true;
            if (core.contains(declaredCore) || declaredCore.contains(core)) return true;
            if (cjkOverlapMatch(declaredCore, core)) return true;
        }
        return false;
    }

    private static String normalizeTitleCore(String title) {
        if (title == null) return "";
        return title.trim()
                .replaceFirst("^第?\\s*[0-9]+(\\.[0-9]+)*\\s*[章节]?[\\s\\.、:：-]*", "")
                .replaceAll("(试验项目|试验)$", "")
                .trim();
    }

    private static boolean cjkOverlapMatch(String left, String right) {
        Set<Character> leftChars = cjkCharSet(left);
        Set<Character> rightChars = cjkCharSet(right);
        if (leftChars.size() < 2 || rightChars.size() < 2) return false;
        int shared = 0;
        for (Character character : leftChars) {
            if (rightChars.contains(character)) shared++;
        }
        if (shared < 2) return false;
        int shorter = Math.min(leftChars.size(), rightChars.size());
        return shared >= Math.max(2, (int) Math.ceil(shorter * 0.8));
    }

    private static Set<Character> cjkCharSet(String value) {
        Set<Character> characters = new LinkedHashSet<>();
        if (value == null) return characters;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character >= 0x4e00 && character <= 0x9fa5) characters.add(character);
        }
        return characters;
    }
}
