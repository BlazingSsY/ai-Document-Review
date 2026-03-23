package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleDTO;
import com.aireview.service.RuleService;
import com.aireview.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<RuleDTO> uploadRule(@RequestParam("file") MultipartFile file,
                                           @RequestParam(required = false) Long libraryId,
                                           Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            RuleDTO rule = ruleService.uploadRule(file, userId, libraryId);
            return ApiResponse.success("规则上传成功", rule);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Rule upload failed", e);
            return ApiResponse.error("规则上传失败: " + e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<PageResponse<RuleDTO>> listRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long libraryId,
            Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            String role = SecurityUtils.getRoleFromAuthentication(authentication);
            PageResponse<RuleDTO> result = ruleService.listRules(page, size, userId, role, libraryId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list rules", e);
            return ApiResponse.error("获取规则列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<RuleDTO> getRule(@PathVariable Long id) {
        try {
            RuleDTO rule = ruleService.getRuleById(id);
            return ApiResponse.success(rule);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get rule", e);
            return ApiResponse.error("获取规则失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        try {
            ruleService.deleteRule(id);
            return ApiResponse.success("规则已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete rule", e);
            return ApiResponse.error("删除规则失败: " + e.getMessage());
        }
    }
}
