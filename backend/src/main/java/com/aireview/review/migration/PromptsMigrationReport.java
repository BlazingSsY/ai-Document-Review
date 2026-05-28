package com.aireview.review.migration;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured outcome of one {@link PromptsMigrationRunner} run. The runner
 * serialises this into a markdown report on disk and also logs the headline
 * counts — the report is the artifact the user should sign off on before the
 * v2 pipeline is wired into production.
 */
@Data
@Builder
public class PromptsMigrationReport {

    private final String sourcePath;
    private final LocalDateTime startedAt;
    private final LocalDateTime finishedAt;

    private final int totalEntries;
    private final int inserted;
    private final int updated;
    private final int skippedNoMatchingRule;
    private final int skippedMalformed;
    private final int errors;

    @Builder.Default
    private final List<Entry> upserts = new ArrayList<>();

    @Builder.Default
    private final List<Skip> skips = new ArrayList<>();

    @Builder.Default
    private final List<String> errorMessages = new ArrayList<>();

    @Data
    @Builder
    public static class Entry {
        /** prompts.json key, e.g. "G-1-test_unit". */
        private final String promptKey;
        /** Resolved rule_code on rules table that we matched against. */
        private final String matchedRuleCode;
        private final long ruleId;
        private final String checkCode;
        /** "INSERT" or "UPDATE". */
        private final String action;
        private final int systemPromptChars;
        private final int userPromptChars;
    }

    @Data
    @Builder
    public static class Skip {
        private final String promptKey;
        private final String reason;
    }
}
