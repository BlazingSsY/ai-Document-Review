package com.aireview.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates model-returned verbatim evidence in the parsed Word node sequence.
 *
 * <p>定位结果直接决定右侧原文面板高亮到哪一段，所以这里的取舍是**宁缺毋滥**：定位不到
 * 就返回空（前端退回章节级展示），也不要高亮到一段并非证据出处的文字——错误高亮比不高亮
 * 更容易让人误判。
 */
public final class DocumentEvidenceLocator {

    private static final int MIN_CANDIDATE_LENGTH = 6;

    /**
     * 反向包含（节点整体落在证据里）所需的最短节点长度。
     *
     * <p>模型的 evidence 常写成「原文“……”不符合要求」，其中会顺带出现章节标题之类的短文本。
     * 阈值取得太低时，"试验人员与承试单位" 这样的标题节点会因为被证据字符串包含而抢先命中，
     * 高亮就落在标题上而不是真正的正文句子。所以反向包含要求节点本身足够长。
     */
    private static final int MIN_REVERSE_MATCH_LENGTH = 14;

    /** 多条证据允许合并成一个高亮区间的最大节点跨度。超过就只高亮最可信的那一条。 */
    private static final int MAX_SPAN_DISTANCE = 4;

    private static final Pattern QUOTED_TEXT = Pattern.compile("[“\"]([^”\"]{4,})[”\"]");

    private DocumentEvidenceLocator() {
    }

    public static Optional<NodeRange> locate(List<WordParser.DocumentNode> nodes, String evidence) {
        if (nodes == null || nodes.isEmpty() || evidence == null || evidence.isBlank()) {
            return Optional.empty();
        }

        // 按可信度分层：引号内原文 > 整条证据 > 拆句。命中即止，不把低可信层的结果混进来。
        Optional<NodeRange> quoted = locateCandidates(nodes, quotedCandidates(evidence));
        if (quoted.isPresent()) return quoted;

        Optional<NodeRange> direct = locateCandidates(nodes, List.of(cleanCandidate(evidence)));
        if (direct.isPresent()) return direct;

        List<String> sentenceCandidates = new ArrayList<>();
        for (String part : evidence.split("\\r?\\n|[。；;]")) {
            String candidate = cleanCandidate(part);
            if (normalize(candidate).length() >= MIN_CANDIDATE_LENGTH) {
                sentenceCandidates.add(candidate);
            }
        }
        return locateCandidates(nodes, sentenceCandidates);
    }

    /**
     * 为每个候选选出**得分最高**的节点，再把彼此靠近的命中合并成一个区间。
     *
     * <p>早期实现是「从头扫、首个包含即命中并 break」，再对全部候选取 min/max 下标做区间。
     * 两个后果：短节点靠反向包含抢在正文之前命中；两条相距很远的证据会把中间几十个无关
     * 节点一起高亮。这里改为按匹配长度打分取最优，并限制合并跨度。
     */
    private static Optional<NodeRange> locateCandidates(List<WordParser.DocumentNode> nodes,
                                                        List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();

        List<Match> matches = new ArrayList<>();
        for (String candidate : candidates) {
            bestMatch(nodes, candidate).ifPresent(matches::add);
        }
        if (matches.isEmpty()) return Optional.empty();

        // 以最可信的一条为锚，只并入离它足够近的其它命中。
        Match anchor = matches.stream().max(Comparator.comparingInt(Match::score)).orElseThrow();
        int startIndex = anchor.index();
        int endIndex = anchor.index();
        for (Match match : matches) {
            if (Math.abs(match.index() - anchor.index()) > MAX_SPAN_DISTANCE) continue;
            startIndex = Math.min(startIndex, match.index());
            endIndex = Math.max(endIndex, match.index());
        }

        WordParser.DocumentNode start = nodes.get(startIndex);
        WordParser.DocumentNode end = nodes.get(endIndex);
        // sectionPath 取锚点所在节点的，合并片跨章时才不会串到别的章去。
        return Optional.of(new NodeRange(
                start.getId(), end.getId(), nodes.get(anchor.index()).getSectionPath()));
    }

    /** 在全部节点里为单个候选找最优匹配；得分即有效匹配字符数，越长越可信。 */
    private static Optional<Match> bestMatch(List<WordParser.DocumentNode> nodes, String candidate) {
        String needle = normalize(candidate);
        if (needle.length() < MIN_CANDIDATE_LENGTH) return Optional.empty();

        Match best = null;
        for (int index = 0; index < nodes.size(); index++) {
            String nodeText = normalize(nodes.get(index).getText());
            if (nodeText.isEmpty()) continue;

            int score;
            if (nodeText.contains(needle)) {
                // 正向包含：证据是节点的子串，最可信。
                score = needle.length() * 2;
            } else if (nodeText.length() >= MIN_REVERSE_MATCH_LENGTH && needle.contains(nodeText)) {
                // 反向包含：证据跨越了多个节点，本节点整体是其中一段。可信度低一档。
                score = nodeText.length();
            } else {
                continue;
            }

            if (best == null || score > best.score()) {
                best = new Match(index, score);
            }
        }
        return Optional.ofNullable(best);
    }

    private static List<String> quotedCandidates(String evidence) {
        Set<String> candidates = new LinkedHashSet<>();
        String trimmed = evidence == null ? "" : evidence.trim();
        if (trimmed.isEmpty()) return List.of();

        Matcher matcher = QUOTED_TEXT.matcher(trimmed);
        while (matcher.find()) {
            String candidate = cleanCandidate(matcher.group(1));
            if (normalize(candidate).length() >= MIN_CANDIDATE_LENGTH) candidates.add(candidate);
        }

        List<String> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        return ordered;
    }

    private static String cleanCandidate(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceFirst("^(?:原文(?:仅写|写道|为)?|证据(?:原文)?)[：:]?", "")
                .replaceFirst("^[“\"'‘]+", "")
                .replaceFirst("[”\"'’]+$", "")
                .trim();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\s|`*_#>]", "")
                .replace("“", "")
                .replace("”", "")
                .replace("\"", "")
                .trim();
    }

    /** 一次候选命中：节点下标 + 可信度得分。 */
    private record Match(int index, int score) {
    }

    public record NodeRange(String startNodeId, String endNodeId, String sectionPath) {
    }
}
