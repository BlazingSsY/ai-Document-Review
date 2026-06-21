package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.PageResponse;
import com.aireview.dto.ScenarioCreateRequest;
import com.aireview.dto.ScenarioDTO;
import com.aireview.service.SarScenarioService;
import com.aireview.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/sar/scenarios")
@RequiredArgsConstructor
public class SarScenarioController {

    private final SarScenarioService sarScenarioService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<ScenarioDTO> createScenario(@Valid @RequestBody ScenarioCreateRequest request,
                                                    Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            ScenarioDTO scenario = sarScenarioService.createScenario(request, userId);
            return ApiResponse.success("SAR 场景创建成功", scenario);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create SAR scenario", e);
            return ApiResponse.error("创建场景失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<ScenarioDTO> getScenario(@PathVariable Long id) {
        try {
            ScenarioDTO scenario = sarScenarioService.getScenarioById(id);
            return ApiResponse.success(scenario);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get SAR scenario", e);
            return ApiResponse.error("获取场景失败: " + e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<PageResponse<ScenarioDTO>> listScenarios(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            PageResponse<ScenarioDTO> result = sarScenarioService.listScenarios(page, size, userId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list SAR scenarios", e);
            return ApiResponse.error("获取场景列表失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<ScenarioDTO> updateScenario(@PathVariable Long id,
                                                    @Valid @RequestBody ScenarioCreateRequest request,
                                                    Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            ScenarioDTO scenario = sarScenarioService.updateScenario(id, request, userId);
            return ApiResponse.success("SAR 场景更新成功", scenario);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update SAR scenario", e);
            return ApiResponse.error("更新场景失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<Void> deleteScenario(@PathVariable Long id, Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            sarScenarioService.deleteScenario(id, userId);
            return ApiResponse.success("SAR 场景删除成功", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete SAR scenario", e);
            return ApiResponse.error("删除场景失败: " + e.getMessage());
        }
    }
}
