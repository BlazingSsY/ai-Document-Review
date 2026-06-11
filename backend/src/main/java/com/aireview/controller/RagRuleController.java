package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.ChecklistImportResultDTO;
import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleDTO;
import com.aireview.dto.RuleMetadataUpdateRequest;
import com.aireview.service.ChecklistRuleImportService;
import com.aireview.service.RagRuleService;
import com.aireview.util.SecurityUtils;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * RAG 侧规则 REST 入口。与 {@link RuleController} 结构对称，但写入 rag_rules 表，
 * 且承载 checklist 导入（因为只有 RAG 才使用 rule_checks）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag/rules")
@RequiredArgsConstructor
public class RagRuleController {

    private final RagRuleService ragRuleService;
    private final ChecklistRuleImportService checklistRuleImportService;

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<List<RuleDTO>> uploadRule(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(required = false) Long libraryId,
                                                 Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            List<RuleDTO> rules = ragRuleService.uploadRuleAll(file, userId, libraryId);
            String msg = rules.size() == 1
                    ? "规则上传成功"
                    : "规则上传成功，共解析 " + rules.size() + " 条规则";
            return ApiResponse.success(msg, rules);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("RAG rule upload failed", e);
            return ApiResponse.error("规则上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/import-checklist")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<ChecklistImportResultDTO> importChecklist(@RequestParam("file") MultipartFile file,
                                                                 @RequestParam(required = false) Long libraryId,
                                                                 Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            ChecklistImportResultDTO result = checklistRuleImportService.importQtpChecklist(file, userId, libraryId);
            return ApiResponse.success("检查单导入成功，共生成 "
                    + result.getCheckCount() + " 个原子检查项", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("RAG checklist import failed", e);
            return ApiResponse.error("检查单导入失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}/metadata")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<RuleDTO> updateMetadata(@PathVariable Long id,
                                               @RequestBody RuleMetadataUpdateRequest req) {
        try {
            RuleDTO updated = ragRuleService.updateMetadata(id, req);
            return ApiResponse.success("规则元信息已更新", updated);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update RAG rule metadata id={}", id, e);
            return ApiResponse.error("更新失败: " + e.getMessage());
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
            PageResponse<RuleDTO> result = ragRuleService.listRules(page, size, userId, role, libraryId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list RAG rules", e);
            return ApiResponse.error("获取规则列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<RuleDTO> getRule(@PathVariable Long id) {
        try {
            RuleDTO rule = ragRuleService.getRuleById(id);
            return ApiResponse.success(rule);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get RAG rule", e);
            return ApiResponse.error("获取规则失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        try {
            ragRuleService.deleteRule(id);
            return ApiResponse.success("规则已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete RAG rule", e);
            return ApiResponse.error("删除规则失败: " + e.getMessage());
        }
    }
}
