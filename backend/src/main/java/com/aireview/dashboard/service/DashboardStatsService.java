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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overview", overview());
        out.put("statusDistribution", statusDistribution());
        out.put("modeDistribution", modeDistribution());
        out.put("dailyTrend", dailyTrend(14));
        out.put("topModels", topModels(8));
        out.put("resources", resources());
        out.put("generatedAt", java.time.LocalDateTime.now().toString());
        return out;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    private Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        long total = count("SELECT count(*) FROM review_tasks") + count("SELECT count(*) FROM sar_review_tasks");
        m.put("totalTasks", total);
        for (String st : List.of("COMPLETED", "PROCESSING", "PENDING", "FAILED", "CANCELLED")) {
            long c = count("SELECT count(*) FROM review_tasks WHERE status='" + st + "'")
                    + count("SELECT count(*) FROM sar_review_tasks WHERE status='" + st + "'");
            m.put(st.toLowerCase(), c);
        }
        m.put("todayTasks",
                count("SELECT count(*) FROM review_tasks WHERE created_at >= CURRENT_DATE")
                        + count("SELECT count(*) FROM sar_review_tasks WHERE created_at >= CURRENT_DATE"));
        long problems = count("SELECT COALESCE(SUM(problem_count),0) FROM review_tasks")
                + count("SELECT COALESCE(SUM(problem_count),0) FROM sar_review_tasks");
        m.put("totalProblems", problems);
        long completed = ((Number) m.get("completed")).longValue();
        m.put("avgProblems", completed > 0 ? Math.round((double) problems / completed * 10) / 10.0 : 0);
        return m;
    }

    private List<Map<String, Object>> statusDistribution() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, count(*) c FROM ("
                        + " SELECT status FROM review_tasks UNION ALL SELECT status FROM sar_review_tasks"
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

    private List<Map<String, Object>> modeDistribution() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(modeEntry("CHUNK", "全文逐章审查", count("SELECT count(*) FROM review_tasks")));
        out.add(modeEntry("SAR", "结构化审查", count("SELECT count(*) FROM sar_review_tasks")));
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
    private List<Map<String, Object>> dailyTrend(int days) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT to_char(created_at::date,'MM-DD') d,"
                        + " count(*) c,"
                        + " count(*) FILTER (WHERE status='COMPLETED') done"
                        + " FROM (SELECT created_at,status FROM review_tasks"
                        + "       UNION ALL SELECT created_at,status FROM sar_review_tasks) t"
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

    private List<Map<String, Object>> topModels(int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT selected_model m, count(*) c FROM ("
                        + " SELECT selected_model FROM review_tasks"
                        + " UNION ALL SELECT selected_model FROM sar_review_tasks) t"
                        + " WHERE selected_model IS NOT NULL AND selected_model <> ''"
                        + " GROUP BY 1 ORDER BY c DESC LIMIT " + limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", String.valueOf(r.get("m")));
            e.put("value", ((Number) r.get("c")).longValue());
            out.add(e);
        }
        return out;
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
