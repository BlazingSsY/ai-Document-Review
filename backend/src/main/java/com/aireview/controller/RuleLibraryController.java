package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleLibraryDTO;
import com.aireview.service.RuleLibraryService;
import com.aireview.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/rule-libraries")
@RequiredArgsConstructor
public class RuleLibraryController {

    private final RuleLibraryService ruleLibraryService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<RuleLibraryDTO> create(@RequestBody Map<String, String> body,
                                               Authentication authentication) {
        try {
            String name = body.get("name");
            String description = body.get("description");
            if (name == null || name.isBlank()) {
                return ApiResponse.badRequest("规则库名称不能为空");
            }
            Long userId = SecurityUtils.getUserId(authentication);
            RuleLibraryDTO result = ruleLibraryService.createLibrary(name, description, userId);
            return ApiResponse.success("规则库创建成功", result);
        } catch (Exception e) {
            log.error("Failed to create rule library", e);
            return ApiResponse.error("创建规则库失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<RuleLibraryDTO> update(@PathVariable Long id,
                                               @RequestBody Map<String, String> body) {
        try {
            String name = body.get("name");
            String description = body.get("description");
            RuleLibraryDTO result = ruleLibraryService.updateLibrary(id, name, description);
            return ApiResponse.success("规则库更新成功", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update rule library", e);
            return ApiResponse.error("更新规则库失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            ruleLibraryService.deleteLibrary(id);
            return ApiResponse.success("规则库已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete rule library", e);
            return ApiResponse.error("删除规则库失败: " + e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<PageResponse<RuleLibraryDTO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            String role = SecurityUtils.getRoleFromAuthentication(authentication);
            PageResponse<RuleLibraryDTO> result = ruleLibraryService.listLibraries(page, size, userId, role);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list rule libraries", e);
            return ApiResponse.error("获取规则库列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/all")
    public ApiResponse<List<RuleLibraryDTO>> listAll() {
        try {
            List<RuleLibraryDTO> result = ruleLibraryService.listAllLibraries();
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list all rule libraries", e);
            return ApiResponse.error("获取规则库列表失败: " + e.getMessage());
        }
    }
}
