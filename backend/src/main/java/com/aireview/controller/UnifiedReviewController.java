package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.PageResponse;
import com.aireview.dto.ReviewTaskDTO;
import com.aireview.service.ReviewService;
import com.aireview.service.SarReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工作台所需的"跨管线合并"接口。Dashboard 列表展示两条管线的任务时调用这里。
 * 单管线的 CRUD 仍走各自的 controller（/api/v1/reviews/* 或 /api/v1/rag/reviews/*）。
 *
 * <p>实现策略：分别从 chunk 与 RAG 拿到最近 N 条，本地内存合并后排序、分页。
 * 没用 SQL UNION 是因为两侧 ID 类型已经一致（VARCHAR(36)），但模型层分别走自己的
 * mapper 更干净；对于 dashboard 这种「最近 100 条以内」的查询，两次 SELECT 的开销
 * 也完全可以接受。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class UnifiedReviewController {

    /** 工作台合并视图默认从每条管线各取这么多条，保证排序结果稳定。 */
    private static final int PER_PIPELINE_FETCH = 200;

    private final ReviewService reviewService;
    private final SarReviewService sarReviewService;

    /**
     * 合并两条管线的最近任务，按 createdAt desc 排序，再做内存分页。
     * 可选 mode 参数（CHUNK / RAG / ALL，默认 ALL）支持只看其中一条。
     * 可选 status 在合并后再过滤（API 简洁，性能足够）。
     */
    @GetMapping("/all")
    public ApiResponse<PageResponse<ReviewTaskDTO>> listAll(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            String normalizedMode = mode == null ? "ALL" : mode.trim().toUpperCase();
            List<ReviewTaskDTO> merged = new ArrayList<>();
            if ("ALL".equals(normalizedMode) || "CHUNK".equals(normalizedMode)) {
                merged.addAll(reviewService.recentTasksForUser(userId, PER_PIPELINE_FETCH));
            }
            if ("ALL".equals(normalizedMode) || "SAR".equals(normalizedMode)) {
                merged.addAll(sarReviewService.recentTasksForUser(userId, PER_PIPELINE_FETCH));
            }
            if (status != null && !status.isBlank()) {
                String s = status.toUpperCase();
                merged.removeIf(t -> !s.equals(t.getStatus()));
            }
            merged.sort(Comparator.comparing(
                    (ReviewTaskDTO t) -> t.getCreatedAt() == null
                            ? LocalDateTime.MIN : t.getCreatedAt())
                    .reversed());

            int total = merged.size();
            int from = Math.max(0, (page - 1) * size);
            int to = Math.min(total, from + size);
            List<ReviewTaskDTO> records = from >= total ? List.of() : merged.subList(from, to);
            return ApiResponse.success(PageResponse.of(records, total, page, size));
        } catch (Exception e) {
            log.error("Failed to list unified review tasks", e);
            return ApiResponse.error("Failed to list tasks: " + e.getMessage());
        }
    }

    /**
     * 跨管线合并统计：dashboard 顶部数字卡使用。返回结构与单管线 /stats 一致，
     * 额外加 {@code byMode} 子对象拆分两条管线各自的统计。
     */
    /**
     * 跨管线任务详情：前端工作台页打开 /review/{taskId} 时调用一次，拿到 reviewMode
     * 后续所有 CRUD 都直接走 /api/v1/reviews/* 或 /api/v1/rag/reviews/*。
     *
     * 实现：先查 chunk 表；找不到再查 RAG 表；都没有 → 404。
     */
    @GetMapping("/by-id/{taskId}")
    public ApiResponse<ReviewTaskDTO> getTaskAnyPipeline(@PathVariable String taskId,
                                                          @RequestParam(defaultValue = "false") boolean light,
                                                          Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        try {
            ReviewTaskDTO chunkTask = light
                    ? reviewService.getTaskLight(taskId, userId)
                    : reviewService.getTask(taskId, userId);
            return ApiResponse.success(chunkTask);
        } catch (IllegalArgumentException chunkErr) {
            // 不在 chunk 表 — 尝试 SAR。
        }
        try {
            ReviewTaskDTO sarTask = light
                    ? sarReviewService.getTaskLight(taskId, userId)
                    : sarReviewService.getTask(taskId, userId);
            return ApiResponse.success(sarTask);
        } catch (IllegalArgumentException sarErr) {
            return ApiResponse.notFound("Task not found in any pipeline: " + taskId);
        } catch (Exception e) {
            log.error("Failed to fetch SAR task fallback", e);
            return ApiResponse.error("Failed to fetch task: " + e.getMessage());
        }
    }

    /**
     * 跨管线的「溯源原文」按需接口：详情页首屏用 {@code by-id?light=true} 拿到矩阵后，
     * 再后台拉这里补齐 {@code originalSources} / {@code chunkResults}。分发逻辑同 by-id：
     * 先 chunk 后 RAG。
     */
    @GetMapping("/by-id/{taskId}/sources")
    public ApiResponse<Map<String, Object>> getTaskSourcesAnyPipeline(@PathVariable String taskId,
                                                                       Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        try {
            return ApiResponse.success(reviewService.getSources(taskId, userId));
        } catch (IllegalArgumentException chunkErr) {
            // 不在 chunk 表 — 尝试 SAR。
        }
        try {
            return ApiResponse.success(sarReviewService.getSources(taskId, userId));
        } catch (IllegalArgumentException sarErr) {
            return ApiResponse.notFound("Task not found in any pipeline: " + taskId);
        } catch (Exception e) {
            log.error("Failed to fetch SAR task sources fallback", e);
            return ApiResponse.error("Failed to fetch sources: " + e.getMessage());
        }
    }

    @GetMapping("/stats/all")
    public ApiResponse<Map<String, Object>> statsAll(Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            Map<String, Object> chunk = reviewService.getStats(userId);
            Map<String, Object> sar = sarReviewService.getStats(userId);
            Map<String, Object> total = new HashMap<>();
            for (String key : List.of("total", "completed", "processing", "failed", "todayCount")) {
                total.put(key, asLong(chunk.get(key)) + asLong(sar.get(key)));
            }
            Map<String, Object> byMode = new HashMap<>();
            byMode.put("CHUNK", chunk);
            byMode.put("SAR", sar);
            total.put("byMode", byMode);
            return ApiResponse.success(total);
        } catch (Exception e) {
            log.error("Failed to get unified review stats", e);
            return ApiResponse.error("Failed to get stats: " + e.getMessage());
        }
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return v == null ? 0L : Long.parseLong(Objects.toString(v, "0"));
    }
}
