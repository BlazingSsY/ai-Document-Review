package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.ChecklistImportResultDTO;
import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleContentUpdateRequest;
import com.aireview.dto.RuleDTO;
import com.aireview.dto.RuleMetadataUpdateRequest;
import com.aireview.dto.RuleUploadConflictDTO;
import com.aireview.service.ChecklistRuleImportService;
import com.aireview.service.SarRuleService;
import com.aireview.util.SecurityUtils;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * SAR 侧规则 REST 入口。与 {@link RuleController} 结构对称，但写入 sar_rules 表。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sar/rules")
@RequiredArgsConstructor
public class SarRuleController {

    private final SarRuleService sarRuleService;
    private final ChecklistRuleImportService checklistRuleImportService;

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<List<RuleDTO>> uploadRule(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(required = false) Long libraryId,
                                                 @RequestParam(required = false) Long folderId,
                                                 @RequestParam(defaultValue = "false") boolean replaceExisting,
                                                 Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            List<RuleDTO> rules = sarRuleService.uploadRuleAll(file, userId, libraryId, folderId, replaceExisting);
            String msg = rules.size() == 1
                    ? "规则上传成功"
                    : "规则上传成功，共解析 " + rules.size() + " 条规则";
            return ApiResponse.success(msg, rules);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("SAR rule upload failed", e);
            return ApiResponse.error("规则上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/import-checklist")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<ChecklistImportResultDTO> importChecklist(@RequestParam("file") MultipartFile file,
                                                                 @RequestParam(required = false) Long libraryId,
                                                                 @RequestParam(required = false) Long folderId,
                                                                 @RequestParam(defaultValue = "false") boolean replaceExisting,
                                                                 Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            ChecklistImportResultDTO result = checklistRuleImportService.importQtpChecklist(
                    file, userId, libraryId, folderId, "SAR", replaceExisting);
            return ApiResponse.success("检查单导入成功，共生成 "
                    + result.getCheckCount() + " 个原子检查项", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("SAR checklist import failed", e);
            return ApiResponse.error("检查单导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/upload-conflicts")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<List<RuleUploadConflictDTO>> uploadConflicts(@RequestParam("fileName") String fileName,
                                                                    @RequestParam(required = false, defaultValue = "false") boolean checklist,
                                                                    @RequestParam(required = false) Long libraryId,
                                                                    @RequestParam(required = false) Long folderId) {
        try {
            String sourceFile = checklist ? checklistRuleImportService.generatedRuleFileName(fileName) : fileName;
            return ApiResponse.success(sarRuleService.findUploadConflicts(sourceFile, libraryId, folderId));
        } catch (Exception e) {
            log.error("Failed to check SAR rule upload conflicts", e);
            return ApiResponse.error("检查规则冲突失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}/metadata")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<RuleDTO> updateMetadata(@PathVariable Long id,
                                               @RequestBody RuleMetadataUpdateRequest req) {
        try {
            RuleDTO updated = sarRuleService.updateMetadata(id, req);
            return ApiResponse.success("规则元信息已更新", updated);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update SAR rule metadata id={}", id, e);
            return ApiResponse.error("更新失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}/content")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<RuleDTO> updateContent(@PathVariable Long id,
                                              @RequestBody RuleContentUpdateRequest req) {
        try {
            RuleDTO updated = sarRuleService.updateContent(id, req);
            return ApiResponse.success("规则内容已更新", updated);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update SAR rule content id={}", id, e);
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
            PageResponse<RuleDTO> result = sarRuleService.listRules(page, size, userId, role, libraryId, folderId, uncategorized);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list SAR rules", e);
            return ApiResponse.error("获取规则列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<RuleDTO> getRule(@PathVariable Long id) {
        try {
            RuleDTO rule = sarRuleService.getRuleById(id);
            return ApiResponse.success(rule);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get SAR rule", e);
            return ApiResponse.error("获取规则失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        try {
            sarRuleService.deleteRule(id);
            return ApiResponse.success("规则已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete SAR rule", e);
            return ApiResponse.error("删除规则失败: " + e.getMessage());
        }
    }
}
