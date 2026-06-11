package com.aireview.review.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One-shot migrator from {@code prompts/prompts/prompts.json} into the v2
 * {@code rule_checks} table.
 *
 * <p><b>What it does:</b> for every entry under {@code section_prompts}, it
 * looks up the {@code rules} row whose {@code rule_code} matches the prompt
 * key (e.g. {@code "G-1-test_unit"}). If found, it upserts a single
 * {@code rule_checks} row with {@code check_code = "<key>._default"} carrying
 * the joined system+user prompt text. If no rule matches, the entry is
 * <em>skipped</em> and recorded in the report — we never invent rules.
 *
 * <p><b>Why one-default-check-per-prompt:</b> the legacy prompts are long,
 * free-form system+user blobs, not atomic 是/否 checks. Decomposing them into
 * atomic checks correctly requires human judgement; this migrator preserves
 * the original text losslessly under a {@code ._default} check_code so the v2
 * pipeline has something to run while the user incrementally splits them via
 * the (eventual) rule-check editor.
 *
 * <p><b>Idempotency:</b> upserts use {@code ON CONFLICT (rule_id, check_code)
 * DO UPDATE} so re-running the migrator after editing prompts.json safely
 * refreshes the content without touching {@code id} / {@code created_at}.
 *
 * <p><b>Gating:</b> runs only when
 * {@code review.prompts.migration.run-on-startup=true} is set in
 * {@code application.yml}. Default is off — flip the flag, restart, then read
 * the generated markdown report before flipping it back.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "review.prompts.migration.run-on-startup", havingValue = "true")
public class PromptsMigrationRunner implements ApplicationRunner {

    private static final String DEFAULT_CHECK_SUFFIX = "._default";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;
    private final String promptsPath;
    private final String reportDir;

    public PromptsMigrationRunner(JdbcTemplate jdbc,
                                   ObjectMapper objectMapper,
                                   @Value("${review.prompts.path:./prompts/prompts/prompts.json}") String promptsPath,
                                   @Value("${review.prompts.migration.report-dir:./output/migration-reports}") String reportDir) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = objectMapper;
        this.promptsPath = promptsPath;
        this.reportDir = reportDir;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            PromptsMigrationReport report = migrate();
            Path written = writeReport(report);
            log.info("Prompts migration finished: {} entries → {} inserted, {} updated, {} skipped, {} errors. Report: {}",
                    report.getTotalEntries(), report.getInserted(), report.getUpdated(),
                    report.getSkippedNoMatchingRule() + report.getSkippedMalformed(),
                    report.getErrors(), written.toAbsolutePath());
        } catch (Exception e) {
            log.error("Prompts migration aborted: {}", e.getMessage(), e);
        }
    }

    /**
     * Run the migration and return the report. Public so the same logic can
     * be invoked from an admin endpoint or a unit test without bringing up
     * the {@link ApplicationRunner} hook.
     */
    public PromptsMigrationReport migrate() throws Exception {
        LocalDateTime started = LocalDateTime.now();
        File src = new File(promptsPath);
        if (!src.exists() || !src.isFile()) {
            throw new IllegalStateException("prompts.json not found at " + src.getAbsolutePath());
        }

        JsonNode root = objectMapper.readTree(src);
        JsonNode section = root.get("section_prompts");
        if (section == null || !section.isObject()) {
            throw new IllegalStateException("prompts.json missing 'section_prompts' object");
        }

        List<PromptsMigrationReport.Entry> upserts = new ArrayList<>();
        List<PromptsMigrationReport.Skip> skips = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        int total = 0;

        Iterator<Map.Entry<String, JsonNode>> it = section.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            total++;
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            try {
                String systemText = joinLines(value.get("system_prompt"));
                String userText = joinLines(value.get("user_prompt"));
                if (systemText.isBlank() && userText.isBlank()) {
                    skips.add(PromptsMigrationReport.Skip.builder()
                            .promptKey(key)
                            .reason("Both system_prompt and user_prompt are empty/missing")
                            .build());
                    continue;
                }

                Long ruleId = findRuleIdByCode(key);
                if (ruleId == null) {
                    skips.add(PromptsMigrationReport.Skip.builder()
                            .promptKey(key)
                            .reason("No rule found with rule_code='" + key + "' — onboard the rule first, then re-run")
                            .build());
                    continue;
                }

                String checkCode = key + DEFAULT_CHECK_SUFFIX;
                String question = systemText.isBlank() ? "(system prompt empty)" : systemText;
                String passCriteria = userText.isBlank() ? "(user prompt empty)" : userText;
                String category = deriveCategory(key);

                String action = upsertCheck(ruleId, checkCode, question, passCriteria, category);
                if ("INSERT".equals(action)) inserted++; else updated++;

                upserts.add(PromptsMigrationReport.Entry.builder()
                        .promptKey(key)
                        .matchedRuleCode(key)
                        .ruleId(ruleId)
                        .checkCode(checkCode)
                        .action(action)
                        .systemPromptChars(systemText.length())
                        .userPromptChars(userText.length())
                        .build());
            } catch (Exception ex) {
                log.warn("Migration error on '{}': {}", key, ex.getMessage(), ex);
                errors.add(key + ": " + ex.getMessage());
            }
        }

        return PromptsMigrationReport.builder()
                .sourcePath(src.getAbsolutePath())
                .startedAt(started)
                .finishedAt(LocalDateTime.now())
                .totalEntries(total)
                .inserted(inserted)
                .updated(updated)
                .skippedNoMatchingRule((int) skips.stream()
                        .filter(s -> s.getReason().startsWith("No rule")).count())
                .skippedMalformed((int) skips.stream()
                        .filter(s -> !s.getReason().startsWith("No rule")).count())
                .errors(errors.size())
                .upserts(upserts)
                .skips(skips)
                .errorMessages(errors)
                .build();
    }

    // ---------------- DB helpers ----------------

    private Long findRuleIdByCode(String ruleCode) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM rules WHERE rule_code = ? AND is_valid = TRUE ORDER BY id LIMIT 1",
                Long.class, ruleCode);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * INSERT … ON CONFLICT (rule_id, check_code) DO UPDATE. Returns "INSERT"
     * or "UPDATE" depending on which path Postgres took (via {@code xmax = 0}
     * trick — xmax is 0 on freshly-inserted rows, non-zero on updates).
     */
    private String upsertCheck(long ruleId, String checkCode, String question,
                                String passCriteria, String category) {
        String sql = "INSERT INTO rule_checks "
                + "(rule_id, check_code, check_type, question, pass_criteria, "
                + " category, evidence_required, display_order, is_active, created_at, updated_at) "
                + "VALUES (:rule_id, :check_code, 'presence', :question, :pass_criteria, "
                + " :category, TRUE, 0, TRUE, NOW(), NOW()) "
                + "ON CONFLICT (rule_id, check_code) DO UPDATE SET "
                + " question = EXCLUDED.question, "
                + " pass_criteria = EXCLUDED.pass_criteria, "
                + " category = EXCLUDED.category, "
                + " updated_at = NOW() "
                + "RETURNING (xmax = 0) AS inserted";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("rule_id", ruleId)
                .addValue("check_code", checkCode)
                .addValue("question", question)
                .addValue("pass_criteria", passCriteria)
                .addValue("category", category);

        Boolean wasInsert = namedJdbc.queryForObject(sql, params, Boolean.class);
        return Boolean.TRUE.equals(wasInsert) ? "INSERT" : "UPDATE";
    }

    // ---------------- text helpers ----------------

    private String joinLines(JsonNode arr) {
        if (arr == null) return "";
        if (arr.isTextual()) return arr.asText();
        if (!arr.isArray()) return arr.toString();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(arr.get(i).asText());
        }
        return sb.toString();
    }

    private String deriveCategory(String key) {
        if (key.startsWith("G-")) return "general";
        if (key.startsWith("section_")) return "section";
        if (key.startsWith("DO160")) return "do160";
        if (key.startsWith("DOC-")) return "document";
        return "other";
    }

    // ---------------- report writer ----------------

    private Path writeReport(PromptsMigrationReport report) throws Exception {
        Files.createDirectories(Paths.get(reportDir));
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(report.getStartedAt());
        Path out = Paths.get(reportDir, "prompts-migration-" + ts + ".md");

        try (FileWriter w = new FileWriter(out.toFile())) {
            w.write("# Prompts Migration Report\n\n");
            w.write("- **Source:** `" + report.getSourcePath() + "`\n");
            w.write("- **Started:** " + report.getStartedAt() + "\n");
            w.write("- **Finished:** " + report.getFinishedAt() + "\n\n");

            w.write("## 概要\n\n");
            w.write("| 指标 | 数量 |\n|---|---:|\n");
            w.write("| 解析到的 prompt 条目 | " + report.getTotalEntries() + " |\n");
            w.write("| INSERT (新建 rule_check) | " + report.getInserted() + " |\n");
            w.write("| UPDATE (覆盖已有 rule_check) | " + report.getUpdated() + " |\n");
            w.write("| 跳过 — 无匹配 rule | " + report.getSkippedNoMatchingRule() + " |\n");
            w.write("| 跳过 — 内容为空 | " + report.getSkippedMalformed() + " |\n");
            w.write("| 失败 | " + report.getErrors() + " |\n\n");

            if (!report.getUpserts().isEmpty()) {
                w.write("## 写入明细 (rule_checks)\n\n");
                w.write("| prompt key | rule_id | check_code | action | sys chars | user chars |\n");
                w.write("|---|---:|---|---|---:|---:|\n");
                for (PromptsMigrationReport.Entry e : report.getUpserts()) {
                    w.write("| `" + e.getPromptKey() + "` | "
                            + e.getRuleId() + " | `" + e.getCheckCode() + "` | "
                            + e.getAction() + " | " + e.getSystemPromptChars()
                            + " | " + e.getUserPromptChars() + " |\n");
                }
                w.write("\n");
            }

            if (!report.getSkips().isEmpty()) {
                w.write("## 跳过项 — 需人工处理\n\n");
                w.write("> 这些 prompt 条目找不到对应的 `rules.rule_code`。请在 rules 库\n");
                w.write("> 中先建好对应规则（rule_code 与 prompt key 一致），然后重跑迁移。\n\n");
                for (PromptsMigrationReport.Skip s : report.getSkips()) {
                    w.write("- **`" + s.getPromptKey() + "`** — " + s.getReason() + "\n");
                }
                w.write("\n");
            }

            if (!report.getErrorMessages().isEmpty()) {
                w.write("## 错误\n\n");
                for (String err : report.getErrorMessages()) {
                    w.write("- " + err + "\n");
                }
                w.write("\n");
            }

            w.write("## 下一步\n\n");
            w.write("1. 核对上面的「跳过项」是否符合预期；如果有遗漏的规则，先在规则库里建好 `rule_code`，重跑即可幂等覆盖。\n");
            w.write("2. 检查写入明细的 `check_code` 都以 `._default` 结尾 —— 这只是把整段 prompt 作为一条粗粒度 check 写入，后续应在 UI 上把它拆成原子 check（每条一个是/否问题）。\n");
            w.write("3. 确认无误后，把 `review.prompts.migration.run-on-startup` 改回 `false`，避免下次启动重跑。\n");
        }
        return out;
    }
}
