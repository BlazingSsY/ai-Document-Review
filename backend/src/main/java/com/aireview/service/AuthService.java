package com.aireview.service;

import com.aireview.config.JwtTokenProvider;
import com.aireview.dto.LoginRequest;
import com.aireview.dto.RegisterRequest;
import com.aireview.entity.User;
import com.aireview.repository.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public Map<String, String> register(RegisterRequest request) {
        User existing = userMapper.findByEmail(request.getEmail());
        if (existing != null) {
            throw new IllegalArgumentException("该邮箱已注册");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName() != null ? request.getName() : request.getEmail().split("@")[0]);
        user.setRole("user");
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);

        log.info("User registered successfully: {}", request.getEmail());
        return generateTokens(user);
    }

    public Map<String, String> login(LoginRequest request) {
        User user = userMapper.findByEmail(request.getEmail());
        if (user == null) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        log.info("User logged in: {}", request.getEmail());
        return generateTokens(user);
    }

    public Map<String, String> refresh(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid or expired refresh token");
        }

        String tokenType = jwtTokenProvider.getTokenType(refreshToken);
        if (!"refresh".equals(tokenType)) {
            throw new IllegalArgumentException("Token is not a refresh token");
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        // Re-fetch user from DB to get current role
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }

        return generateTokens(user);
    }

    private Map<String, String> generateTokens(User user) {
        String role = user.getRole() != null ? user.getRole() : "user";
        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), role));
        tokens.put("refreshToken", jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail(), role));
        return tokens;
    }
}
