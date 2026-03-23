package com.aireview.controller;

import com.aireview.dto.*;
import com.aireview.service.UserService;
import com.aireview.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class UserManagementController {

    private final UserService userService;

    @GetMapping
    public ApiResponse<PageResponse<UserDTO>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            PageResponse<UserDTO> result = userService.listAllUsers(page, size);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list users", e);
            return ApiResponse.error("获取用户列表失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}/role")
    public ApiResponse<Void> updateRole(@PathVariable Long id,
                                         @RequestBody Map<String, String> body,
                                         Authentication authentication) {
        try {
            String role = body.get("role");
            if (role == null || role.isBlank()) {
                return ApiResponse.badRequest("角色不能为空");
            }
            Long operatorId = SecurityUtils.getUserId(authentication);
            userService.updateUserRole(id, role, operatorId);
            return ApiResponse.success("角色更新成功", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update user role", e);
            return ApiResponse.error("更新角色失败: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/libraries")
    public ApiResponse<Void> assignLibraries(@PathVariable Long id,
                                              @RequestBody Map<String, List<Long>> body) {
        try {
            List<Long> libraryIds = body.get("libraryIds");
            if (libraryIds == null) {
                return ApiResponse.badRequest("规则库ID列表不能为空");
            }
            userService.assignLibrariesToUser(id, libraryIds);
            return ApiResponse.success("规则库分配成功", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to assign libraries", e);
            return ApiResponse.error("分配规则库失败: " + e.getMessage());
        }
    }

    @PostMapping
    public ApiResponse<UserDTO> createUser(@RequestBody Map<String, String> body) {
        try {
            String email = body.get("email");
            String password = body.get("password");
            String name = body.get("name");
            String role = body.get("role");
            if (email == null || email.isBlank() || password == null || password.isBlank()) {
                return ApiResponse.badRequest("账号和密码不能为空");
            }
            UserDTO user = userService.createUser(email, password, name, role != null ? role : "user");
            return ApiResponse.success("用户创建成功", user);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create user", e);
            return ApiResponse.error("创建用户失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/libraries")
    public ApiResponse<List<Long>> getUserLibraries(@PathVariable Long id) {
        try {
            List<Long> libraryIds = userService.getAssignedLibraryIds(id);
            return ApiResponse.success(libraryIds);
        } catch (Exception e) {
            log.error("Failed to get user libraries", e);
            return ApiResponse.error("获取用户规则库失败: " + e.getMessage());
        }
    }
}
