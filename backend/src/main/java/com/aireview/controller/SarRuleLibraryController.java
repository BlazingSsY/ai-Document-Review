package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleFolderDTO;
import com.aireview.dto.RuleLibraryDTO;
import com.aireview.service.SarRuleLibraryService;
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
@RequestMapping("/api/v1/sar/rule-libraries")
@RequiredArgsConstructor
public class SarRuleLibraryController {

    private final SarRuleLibraryService sarRuleLibraryService;

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
            RuleLibraryDTO result = sarRuleLibraryService.createLibrary(name, description, userId);
            return ApiResponse.success("SAR 规则库创建成功", result);
        } catch (Exception e) {
            log.error("Failed to create SAR rule library", e);
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
            RuleLibraryDTO result = sarRuleLibraryService.updateLibrary(id, name, description);
            return ApiResponse.success("SAR 规则库更新成功", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update SAR rule library", e);
            return ApiResponse.error("更新规则库失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            sarRuleLibraryService.deleteLibrary(id);
            return ApiResponse.success("SAR 规则库已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete SAR rule library", e);
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
            PageResponse<RuleLibraryDTO> result = sarRuleLibraryService.listLibraries(page, size, userId, role);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list SAR rule libraries", e);
            return ApiResponse.error("获取规则库列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/all")
    public ApiResponse<List<RuleLibraryDTO>> listAll() {
        try {
            List<RuleLibraryDTO> result = sarRuleLibraryService.listAllLibraries();
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list all SAR rule libraries", e);
            return ApiResponse.error("获取规则库列表失败: " + e.getMessage());
        }
    }

    // ===================== 二级文件夹 =====================

    @GetMapping("/{libraryId}/folders")
    public ApiResponse<List<RuleFolderDTO>> listFolders(@PathVariable Long libraryId) {
        try {
            return ApiResponse.success(sarRuleLibraryService.listFolders(libraryId));
        } catch (Exception e) {
            log.error("Failed to list SAR rule folders", e);
            return ApiResponse.error("获取文件夹列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/{libraryId}/folders")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<RuleFolderDTO> createFolder(@PathVariable Long libraryId,
                                                   @RequestBody Map<String, String> body,
                                                   Authentication authentication) {
        try {
            String name = body.get("name");
            if (name == null || name.isBlank()) {
                return ApiResponse.badRequest("文件夹名称不能为空");
            }
            Long userId = SecurityUtils.getUserId(authentication);
            return ApiResponse.success("文件夹创建成功",
                    sarRuleLibraryService.createFolder(libraryId, name.trim(), userId));
        } catch (Exception e) {
            log.error("Failed to create SAR rule folder", e);
            return ApiResponse.error("创建文件夹失败: " + e.getMessage());
        }
    }

    @PutMapping("/folders/{folderId}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<RuleFolderDTO> updateFolder(@PathVariable Long folderId,
                                                   @RequestBody Map<String, Object> body) {
        try {
            String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
            Boolean enabled = body.get("enabled") == null ? null : Boolean.valueOf(String.valueOf(body.get("enabled")));
            return ApiResponse.success("文件夹已更新",
                    sarRuleLibraryService.updateFolder(folderId, name, enabled));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update SAR rule folder", e);
            return ApiResponse.error("更新文件夹失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/folders/{folderId}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ApiResponse<Void> deleteFolder(@PathVariable Long folderId) {
        try {
            sarRuleLibraryService.deleteFolder(folderId);
            return ApiResponse.success("文件夹已删除（其规则已移至未分类）", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete SAR rule folder", e);
            return ApiResponse.error("删除文件夹失败: " + e.getMessage());
        }
    }
}
