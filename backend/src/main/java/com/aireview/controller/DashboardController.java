package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.service.DashboardStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 数据看板：管理端的系统级统计概览与图表数据。仅主管/管理员可见。 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardStatsService dashboardStatsService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<Map<String, Object>> stats() {
        try {
            return ApiResponse.success(dashboardStatsService.build());
        } catch (Exception e) {
            log.error("Failed to build dashboard stats", e);
            return ApiResponse.error("获取看板数据失败: " + e.getMessage());
        }
    }
}
