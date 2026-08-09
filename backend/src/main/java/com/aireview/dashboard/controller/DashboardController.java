package com.aireview.dashboard.controller;

import com.aireview.auth.security.SecurityUtils;
import com.aireview.common.dto.ApiResponse;
import com.aireview.dashboard.service.DashboardStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 数据看板：管理端的系统级统计概览与图表数据。仅主管/管理员可见。 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardStatsService dashboardStatsService;

    /**
     * @param unitId 按单位筛选；单位管理员传什么都会被服务端收敛到本单位
     * @param userId 按成员筛选
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<Map<String, Object>> stats(
            @RequestParam(required = false) Long unitId,
            @RequestParam(required = false) Long userId,
            Authentication authentication) {
        try {
            return ApiResponse.success(dashboardStatsService.build(
                    unitId, userId, SecurityUtils.getUserId(authentication)));
        } catch (Exception e) {
            log.error("Failed to build dashboard stats", e);
            return ApiResponse.error("获取看板数据失败: " + e.getMessage());
        }
    }
}
