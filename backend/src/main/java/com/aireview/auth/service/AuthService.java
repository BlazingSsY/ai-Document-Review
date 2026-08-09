package com.aireview.auth.service;

import com.aireview.auth.security.JwtTokenProvider;
import com.aireview.auth.dto.LoginRequest;
import com.aireview.auth.dto.RegisterRequest;
import com.aireview.user.entity.User;
import com.aireview.user.repository.UserMapper;
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

    /**
     * 登录。两条路径：
     * <ul>
     *   <li>带 unitId → 成员登录，按「单位 + 用户名」定位（用户名仅在单位内唯一）；</li>
     *   <li>不带 unitId → 存量平台账号，按邮箱定位（admin_root 等不属于任何单位）。</li>
     * </ul>
     *
     * <p>两条路径失败时返回同一句提示：区分「用户不存在」和「密码错误」会把账号是否
     * 存在泄露给探测者。
     */
    public Map<String, String> login(LoginRequest request) {
        String identifier = request.getEmail() == null ? "" : request.getEmail().trim();
        User user = request.getUnitId() != null
                ? userMapper.findByUnitAndUsername(request.getUnitId(), identifier)
                : userMapper.findByEmail(identifier);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        log.info("User logged in: id={}, unit={}", user.getId(), user.getUnitId());
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
        // 导入的成员没有邮箱，token 的 email 声明退而用用户名，保证声明非空。
        String subject = user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail() : user.getUsername();
        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", jwtTokenProvider.generateAccessToken(user.getId(), subject, role));
        tokens.put("refreshToken", jwtTokenProvider.generateRefreshToken(user.getId(), subject, role));
        // 前端据此在首次登录时强制跳转改密：统一初始密码在改掉之前，任何知道规则的人
        // 都能登进别人的账号，所以这个标志必须随登录结果一起返回，不能等用户自己去改。
        tokens.put("mustChangePassword", String.valueOf(Boolean.TRUE.equals(user.getMustChangePassword())));
        return tokens;
    }
}
