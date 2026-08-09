package com.aireview.dashboard.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据看板统计：跨两条管线(review_tasks / sar_review_tasks)及用户/规则/模型等资源做聚合，
 * 供管理端「数据看板」页展示概览数字与统计图。全部用 JdbcTemplate 直接聚合，按需查询、无副作用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardStatsService {

    private final JdbcTemplate jdbc;

    private static final Map<String, String> STATUS_LABELS = Map.of(
            "COMPLETED", "已完成",
            "PROCESSING", "处理中",
            "PENDING", "待处理",
            "FAILED", "失败",
            "CANCELLED", "已取消");

    public Map<String, Object> build() {
        return build(null, null, null);
    }

    /**
     * 带筛选的看板数据。
     *
     * @param unitId     只统计该单位成员的任务；null 表示不限
     * @param userId     只统计该成员的任务；与 unitId 同时给出时以 userId 为准（更细）
     * @param operatorId 操作者。单位管理员会被强制收敛到本单位，无法通过改参数越权查看
     */
    public Map<String, Object> build(Long unitId, Long userId, Long operatorId) {
        Long effectiveUnit = unitId;
        if (operatorId != null) {
            Map<String, Object> operator = loadOperator(operatorId);
            if ("admin".equals(operator.get("role"))) {
                // 单位管理员只能看本单位。这里直接覆盖入参，不看前端传了什么。
                effectiveUnit = (Long) operator.get("unitId");
            }
        }
        String scope = taskScope(effectiveUnit, userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overview", overview(scope));
        out.put("statusDistribution", statusDistribution(scope));
        out.put("modeDistribution", modeDistribution(scope));
        out.put("dailyTrend", dailyTrend(14, scope));
        out.put("topModels", topModels(8, scope));
        out.put("unitDistribution", unitDistribution(scope));
        out.put("memberDistribution", memberDistribution(12, scope));
        out.put("resources", resources());
        out.put("appliedFilter", Map.of(
                "unitId", effectiveUnit == null ? "" : effectiveUnit,
                "userId", userId == null ? "" : userId));
        out.put("generatedAt", java.time.LocalDateTime.now().toString());
        return out;
    }

    private Map<String, Object> loadOperator(Long operatorId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, unit_id FROM users WHERE id = ?", operatorId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            out.put("role", "user");
            out.put("unitId", null);
            return out;
        }
        Object unit = rows.get(0).get("unit_id");
        out.put("role", String.valueOf(rows.get(0).get("role")));
        out.put("unitId", unit == null ? null : ((Number) unit).longValue());
        return out;
    }

    /**
     * 任务范围的 SQL 条件片段。
     *
     * <p>做成对 {@code user_id} 的条件而不是 JOIN users，是因为下面所有统计都建立在
     * {@code review_tasks UNION ALL sar_review_tasks} 的子查询之上，加条件比给每个
     * 子查询都接一次 JOIN 要小得多。unitId / userId 都是 Long，拼接无注入面。
     */
    private String taskScope(Long unitId, Long userId) {
        if (userId != null) return "user_id = " + userId;
        if (unitId != null) return "user_id IN (SELECT id FROM users WHERE unit_id = " + unitId + ")";
        return "TRUE";
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    private Map<String, Object> overview(String scope) {
        Map<String, Object> m = new LinkedHashMap<>();
        long total = count("SELECT count(*) FROM review_tasks WHERE " + scope)
                + count("SELECT count(*) FROM sar_review_tasks WHERE " + scope);
        m.put("totalTasks", total);
        for (String st : List.of("COMPLETED", "PROCESSING", "PENDING", "FAILED", "CANCELLED")) {
            long c = count("SELECT count(*) FROM review_tasks WHERE status='" + st + "' AND " + scope)
                    + count("SELECT count(*) FROM sar_review_tasks WHERE status='" + st + "' AND " + scope);
            m.put(st.toLowerCase(), c);
        }
        m.put("todayTasks",
                count("SELECT count(*) FROM review_tasks WHERE created_at >= CURRENT_DATE AND " + scope)
                        + count("SELECT count(*) FROM sar_review_tasks WHERE created_at >= CURRENT_DATE AND " + scope));
        long problems = count("SELECT COALESCE(SUM(problem_count),0) FROM review_tasks WHERE " + scope)
                + count("SELECT COALESCE(SUM(problem_count),0) FROM sar_review_tasks WHERE " + scope);
        m.put("totalProblems", problems);
        long completed = ((Number) m.get("completed")).longValue();
        m.put("avgProblems", completed > 0 ? Math.round((double) problems / completed * 10) / 10.0 : 0);
        return m;
    }

    /** 按单位统计审查量，降序。未归属任何单位的账号（如平台主管）归入「未分配单位」。 */
    private List<Map<String, Object>> unitDistribution(String scope) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT COALESCE(un.name, '未分配单位') name, count(*) c"
                        + " FROM (SELECT user_id FROM review_tasks WHERE " + scope
                        + "       UNION ALL SELECT user_id FROM sar_review_tasks WHERE " + scope + ") t"
                        + " JOIN users u ON u.id = t.user_id"
                        + " LEFT JOIN units un ON un.id = u.unit_id"
                        + " GROUP BY 1 ORDER BY c DESC");
        return toNameValue(rows, "name", "c");
    }

    /** 按成员统计审查量，取前 limit 名。显示名带上单位，避免跨单位重名混淆。 */
    private List<Map<String, Object>> memberDistribution(int limit, String scope) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT COALESCE(u.name, u.username, u.email, '未知') || "
                        + " CASE WHEN un.name IS NULL THEN '' ELSE '（' || un.name || '）' END AS name,"
                        + " count(*) c"
                        + " FROM (SELECT user_id FROM review_tasks WHERE " + scope
                        + "       UNION ALL SELECT user_id FROM sar_review_tasks WHERE " + scope + ") t"
                        + " JOIN users u ON u.id = t.user_id"
                        + " LEFT JOIN units un ON un.id = u.unit_id"
                        + " GROUP BY 1 ORDER BY c DESC LIMIT " + limit);
        return toNameValue(rows, "name", "c");
    }

    private List<Map<String, Object>> toNameValue(List<Map<String, Object>> rows,
                                                   String nameKey, String valueKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", String.valueOf(r.get(nameKey)));
            e.put("value", ((Number) r.get(valueKey)).longValue());
            out.add(e);
        }
        return out;
    }

    private List<Map<String, Object>> statusDistribution(String scope) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, count(*) c FROM ("
                        + " SELECT status FROM review_tasks WHERE " + scope
                        + " UNION ALL SELECT status FROM sar_review_tasks WHERE " + scope
                        + ") t GROUP BY status");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String st = String.valueOf(r.get("status"));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("key", st);
            e.put("name", STATUS_LABELS.getOrDefault(st, st));
            e.put("value", ((Number) r.get("c")).longValue());
            out.add(e);
        }
        return out;
    }

    private List<Map<String, Object>> modeDistribution(String scope) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(modeEntry("CHUNK", "全文逐章审查",
                count("SELECT count(*) FROM review_tasks WHERE " + scope)));
        out.add(modeEntry("SAR", "结构化审查",
                count("SELECT count(*) FROM sar_review_tasks WHERE " + scope)));
        return out;
    }

    private Map<String, Object> modeEntry(String key, String name, long v) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("key", key);
        e.put("name", name);
        e.put("value", v);
        return e;
    }

    /** 近 days 天每日审查量(两管线合计) + 当日完成数；缺失日期补 0,按日期升序。 */
    private List<Map<String, Object>> dailyTrend(int days, String scope) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT to_char(created_at::date,'MM-DD') d,"
                        + " count(*) c,"
                        + " count(*) FILTER (WHERE status='COMPLETED') done"
                        + " FROM (SELECT created_at,status FROM review_tasks WHERE " + scope
                        + "       UNION ALL SELECT created_at,status FROM sar_review_tasks WHERE " + scope + ") t"
                        + " WHERE created_at >= CURRENT_DATE - (INTERVAL '1 day' * " + (days - 1) + ")"
                        + " GROUP BY 1");
        Map<String, long[]> byDate = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byDate.put(String.valueOf(r.get("d")),
                    new long[]{((Number) r.get("c")).longValue(), ((Number) r.get("done")).longValue()});
        }
        List<Map<String, Object>> out = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        for (int i = 0; i < days; i++) {
            String d = start.plusDays(i).format(fmt);
            long[] v = byDate.getOrDefault(d, new long[]{0, 0});
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("date", d);
            e.put("total", v[0]);
            e.put("completed", v[1]);
            out.add(e);
        }
        return out;
    }

    private List<Map<String, Object>> topModels(int limit, String scope) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT selected_model m, count(*) c FROM ("
                        + " SELECT selected_model FROM review_tasks WHERE " + scope
                        + " UNION ALL SELECT selected_model FROM sar_review_tasks WHERE " + scope + ") t"
                        + " WHERE selected_model IS NOT NULL AND selected_model <> ''"
                        + " GROUP BY 1 ORDER BY c DESC LIMIT " + limit);
        return toNameValue(rows, "m", "c");
    }

    private Map<String, Object> resources() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", count("SELECT count(*) FROM users"));
        List<Map<String, Object>> usersByRole = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT role, count(*) c FROM users GROUP BY role")) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", roleLabel(String.valueOf(r.get("role"))));
            e.put("value", ((Number) r.get("c")).longValue());
            usersByRole.add(e);
        }
        m.put("usersByRole", usersByRole);

        m.put("rules", count("SELECT count(*) FROM rules") + count("SELECT count(*) FROM sar_rules"));
        m.put("ruleChecks", count("SELECT count(*) FROM rule_checks") + count("SELECT count(*) FROM sar_rule_checks"));
        m.put("ruleLibraries",
                count("SELECT count(*) FROM rule_libraries") + count("SELECT count(*) FROM sar_rule_libraries"));
        m.put("ruleFolders",
                count("SELECT count(*) FROM rule_folders") + count("SELECT count(*) FROM sar_rule_folders"));
        m.put("scenarios", count("SELECT count(*) FROM scenarios") + count("SELECT count(*) FROM sar_scenarios"));

        m.put("models", count("SELECT count(*) FROM ai_model_config"));
        m.put("modelsEnabled", count("SELECT count(*) FROM ai_model_config WHERE is_enabled"));
        List<Map<String, Object>> modelsByType = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT model_type, count(*) c FROM ai_model_config GROUP BY model_type")) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", modelTypeLabel(String.valueOf(r.get("model_type"))));
            e.put("value", ((Number) r.get("c")).longValue());
            modelsByType.add(e);
        }
        m.put("modelsByType", modelsByType);
        return m;
    }

    private static String roleLabel(String r) {
        return switch (r) {
            case "supervisor" -> "主管";
            case "admin" -> "管理员";
            case "user" -> "普通用户";
            default -> r;
        };
    }

    private static String modelTypeLabel(String t) {
        return switch (t) {
            case "chat" -> "对话(chat)";
            case "embedding" -> "向量(embedding)";
            case "reranker" -> "重排(reranker)";
            default -> t;
        };
    }
}
