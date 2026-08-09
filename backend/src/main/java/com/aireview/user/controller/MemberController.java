package com.aireview.user.controller;

import com.aireview.auth.security.SecurityUtils;
import com.aireview.common.dto.ApiResponse;
import com.aireview.common.dto.PageResponse;
import com.aireview.user.dto.MemberImportResult;
import com.aireview.user.dto.UserDTO;
import com.aireview.user.entity.Unit;
import com.aireview.user.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 成员与单位管理。
 *
 * <p>接口层只做「是不是管理员」这一层粗筛，「能不能管这个人」由 {@code MemberService}
 * 按目标成员在库里的 unit_id 判定——单位边界必须在服务端兜住，不能指望前端只发合法请求。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
public class MemberController {

    private final MemberService memberService;

    // ---------------- 单位 ----------------

    @GetMapping("/units")
    public ApiResponse<List<Unit>> listUnits() {
        return ApiResponse.success(memberService.listUnits());
    }

    @PostMapping("/units")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ApiResponse<Unit> createUnit(@RequestBody Map<String, String> body) {
        try {
            return ApiResponse.success("单位已创建", memberService.createUnit(
                    body.get("name"), body.get("code"), body.get("remark")));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/units/{unitId}")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ApiResponse<Void> deleteUnit(@PathVariable Long unitId) {
        try {
            memberService.deleteUnit(unitId);
            return ApiResponse.success("单位已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    // ---------------- 成员 ----------------

    @GetMapping
    public ApiResponse<PageResponse<UserDTO>> listMembers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long unitId,
            @RequestParam(required = false) String keyword,
            Authentication authentication) {
        try {
            return ApiResponse.success(memberService.listMembers(
                    page, size, unitId, keyword, SecurityUtils.getUserId(authentication)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping
    public ApiResponse<UserDTO> createMember(@RequestBody Map<String, String> body,
                                             Authentication authentication) {
        try {
            String unitIdText = body.get("unitId");
            Long unitId = unitIdText == null || unitIdText.isBlank() ? null : Long.valueOf(unitIdText);
            return ApiResponse.success("成员已创建", memberService.createMember(
                    unitId, body.get("username"), body.get("name"), body.get("idCard"),
                    body.get("role"), SecurityUtils.getUserId(authentication)));
        } catch (NumberFormatException e) {
            return ApiResponse.badRequest("单位参数格式不正确");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/{memberId}/role")
    public ApiResponse<Void> updateRole(@PathVariable Long memberId,
                                        @RequestBody Map<String, String> body,
                                        Authentication authentication) {
        try {
            memberService.updateMemberRole(memberId, body.get("role"),
                    SecurityUtils.getUserId(authentication));
            return ApiResponse.success("角色已更新", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{memberId}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long memberId,
                                           Authentication authentication) {
        try {
            memberService.resetPassword(memberId, SecurityUtils.getUserId(authentication));
            return ApiResponse.success("密码已重置为初始密码，该成员下次登录须修改", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{memberId}")
    public ApiResponse<Void> deleteMember(@PathVariable Long memberId,
                                          Authentication authentication) {
        try {
            memberService.deleteMember(memberId, SecurityUtils.getUserId(authentication));
            return ApiResponse.success("成员已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    // ---------------- Excel 导入 ----------------

    @PostMapping("/import")
    public ApiResponse<MemberImportResult> importMembers(@RequestParam("file") MultipartFile file,
                                                         Authentication authentication) {
        try {
            MemberImportResult result = memberService.importFromExcel(
                    file, SecurityUtils.getUserId(authentication));
            String message = result.getFailureCount() == 0
                    ? "导入完成，成功 " + result.getSuccessCount() + " 人"
                    : "导入完成，成功 " + result.getSuccessCount()
                            + " 人，失败 " + result.getFailureCount() + " 人";
            return ApiResponse.success(message, result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Member import failed", e);
            return ApiResponse.error("导入失败：" + e.getMessage());
        }
    }

    @GetMapping("/import-template-headers")
    public ApiResponse<List<String>> importTemplateHeaders() {
        return ApiResponse.success(MemberService.importTemplateHeaders());
    }
}
