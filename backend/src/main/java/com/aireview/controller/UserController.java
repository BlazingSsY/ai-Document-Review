package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.ChangePasswordRequest;
import com.aireview.dto.UserDTO;
import com.aireview.service.UserService;
import com.aireview.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResponse<UserDTO> getCurrentUser(Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            UserDTO user = userService.getUserById(userId);
            return ApiResponse.success(user);
        } catch (Exception e) {
            log.error("Failed to get user profile", e);
            return ApiResponse.error("获取用户信息失败: " + e.getMessage());
        }
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                             Authentication authentication) {
        try {
            Long userId = SecurityUtils.getUserId(authentication);
            userService.changePassword(userId, request);
            return ApiResponse.success("密码修改成功", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to change password", e);
            return ApiResponse.error("密码修改失败: " + e.getMessage());
        }
    }
}
