package com.aireview.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Locates model-returned verbatim evidence in the parsed Word node sequence. */
public final class DocumentEvidenceLocator {

    private static final int MIN_CANDIDATE_LENGTH = 6;
    private static final Pattern QUOTED_TEXT = Pattern.compile("[“\"]([^”\"]{4,})[”\"]");

    private DocumentEvidenceLocator() {
    }

    public static Optional<NodeRange> locate(List<WordParser.DocumentNode> nodes, String evidence) {
        if (nodes == null || nodes.isEmpty() || evidence == null || evidence.isBlank()) {
            return Optional.empty();
        }

        List<String> quotedCandidates = quotedCandidates(evidence);
        Optional<NodeRange> quotedRange = locateCandidates(nodes, quotedCandidates);
        if (quotedRange.isPresent()) return quotedRange;

        String directCandidate = cleanCandidate(evidence);
        Optional<NodeRange> directRange = locateCandidates(nodes, List.of(directCandidate));
        if (directRange.isPresent()) return directRange;

        List<String> sentenceCandidates = new ArrayList<>();
        for (String part : evidence.split("\\r?\\n|[。；;]")) {
            String candidate = cleanCandidate(part);
            if (normalize(candidate).length() >= MIN_CANDIDATE_LENGTH) {
                sentenceCandidates.add(candidate);
            }
        }
        return locateCandidates(nodes, sentenceCandidates);
    }

    private static Optional<NodeRange> locateCandidates(List<WordParser.DocumentNode> nodes,
                                                        List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        int startIndex = Integer.MAX_VALUE;
        int endIndex = -1;
        for (String candidate : candidates) {
            String needle = normalize(candidate);
            if (needle.length() < MIN_CANDIDATE_LENGTH) continue;
            for (int index = 0; index < nodes.size(); index++) {
                String nodeText = normalize(nodes.get(index).getText());
                if (nodeText.contains(needle)
                        || (nodeText.length() >= MIN_CANDIDATE_LENGTH && needle.contains(nodeText))) {
                    startIndex = Math.min(startIndex, index);
                    endIndex = Math.max(endIndex, index);
                    break;
                }
            }
        }

        if (endIndex < 0) return Optional.empty();
        WordParser.DocumentNode start = nodes.get(startIndex);
        WordParser.DocumentNode end = nodes.get(endIndex);
        return Optional.of(new NodeRange(start.getId(), end.getId(), start.getSectionPath()));
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

    public record NodeRange(String startNodeId, String endNodeId, String sectionPath) {
    }
}
