package com.aireview.auth.controller;

import com.aireview.common.dto.ApiResponse;
import com.aireview.auth.dto.LoginRequest;
import com.aireview.auth.dto.RegisterRequest;
import com.aireview.auth.service.AuthService;
import com.aireview.user.entity.Unit;
import com.aireview.user.repository.UnitMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UnitMapper unitMapper;

    /**
     * 登录页的单位下拉。
     *
     * <p>必须是免认证接口——用户要先选单位才能登录，此时手里还没有 token。只返回
     * id 与名称，不含成员数等任何内部信息。
     */
    @GetMapping("/units")
    public ApiResponse<List<Map<String, Object>>> loginUnits() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Unit unit : unitMapper.findAllOrdered()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", unit.getId());
            item.put("name", unit.getName());
            out.add(item);
        }
        return ApiResponse.success(out);
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        try {
            Map<String, String> tokens = authService.register(request);
            return ApiResponse.success("Registration successful", tokens);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Registration failed", e);
            return ApiResponse.error("Registration failed: " + e.getMessage());
        }
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody LoginRequest request) {
        try {
            Map<String, String> tokens = authService.login(request);
            return ApiResponse.success("Login successful", tokens);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Login failed", e);
            return ApiResponse.error("Login failed: " + e.getMessage());
        }
    }

    @PostMapping("/refresh")
    public ApiResponse<Map<String, String>> refresh(@RequestBody Map<String, String> request) {
        try {
            String refreshToken = request.get("refreshToken");
            if (refreshToken == null || refreshToken.isBlank()) {
                return ApiResponse.badRequest("Refresh token is required");
            }
            Map<String, String> tokens = authService.refresh(refreshToken);
            return ApiResponse.success("Token refreshed", tokens);
        } catch (IllegalArgumentException e) {
            return ApiResponse.unauthorized(e.getMessage());
        } catch (Exception e) {
            log.error("Token refresh failed", e);
            return ApiResponse.error("Token refresh failed: " + e.getMessage());
        }
    }
}
