package com.aireview.rule.controller;

import com.aireview.common.dto.ApiResponse;
import com.aireview.common.dto.PageResponse;
import com.aireview.rule.dto.RuleContentUpdateRequest;
import com.aireview.rule.dto.RuleDTO;
import com.aireview.rule.dto.RuleMetadataUpdateRequest;
import com.aireview.rule.dto.RuleUploadConflictDTO;
import com.aireview.rule.service.RuleService;
import com.aireview.auth.security.SecurityUtils;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 全文逐章审查（chunk 管线）下的规则 REST 入口。
 *
 * 注意：checklist 导入端点已迁到 {@link RagRuleController}，因为它产出 rule_checks
 * 原子检查项，仅 RAG 管线消费。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<List<RuleDTO>> uploadRule(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(required = false) Long libraryId,
                                                 @RequestParam(required = false) Long folderId,
                                                 @RequestParam(defaultValue = "false") boolean replaceExisting,
                                                 Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            List<RuleDTO> rules = ruleService.uploadRuleAll(file, userId, libraryId, folderId, replaceExisting);
            String msg = rules.size() == 1
                    ? "规则上传成功"
                    : "规则上传成功，共解析 " + rules.size() + " 条规则";
            return ApiResponse.success(msg, rules);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Rule upload failed", e);
            return ApiResponse.error("规则上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/upload-conflicts")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<List<RuleUploadConflictDTO>> uploadConflicts(@RequestParam("fileName") String fileName,
                                                                    @RequestParam(required = false) Long libraryId,
                                                                    @RequestParam(required = false) Long folderId) {
        try {
            return ApiResponse.success(ruleService.findUploadConflicts(fileName, libraryId, folderId));
        } catch (Exception e) {
            log.error("Failed to check rule upload conflicts", e);
            return ApiResponse.error("检查规则冲突失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}/metadata")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<RuleDTO> updateMetadata(@PathVariable Long id,
                                               @RequestBody RuleMetadataUpdateRequest req) {
        try {
            RuleDTO updated = ruleService.updateMetadata(id, req);
            return ApiResponse.success("规则元信息已更新", updated);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update rule metadata id={}", id, e);
            return ApiResponse.error("更新失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}/content")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<RuleDTO> updateContent(@PathVariable Long id,
                                              @RequestBody RuleContentUpdateRequest req) {
        try {
            RuleDTO updated = ruleService.updateContent(id, req);
            return ApiResponse.success("规则内容已更新", updated);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update rule content id={}", id, e);
            return ApiResponse.error("更新失败: " + e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<PageResponse<RuleDTO>> listRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long libraryId,
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false, defaultValue = "false") boolean uncategorized,
            Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            String role = SecurityUtils.getRoleFromAuthentication(authentication);
            PageResponse<RuleDTO> result = ruleService.listRules(page, size, userId, role, libraryId, folderId, uncategorized);
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
