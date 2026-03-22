package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.LoginRequest;
import com.aireview.dto.RegisterRequest;
import com.aireview.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
