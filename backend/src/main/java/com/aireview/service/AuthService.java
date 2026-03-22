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

    /**
     * Register a new user.
     *
     * @param request registration request with email and password
     * @return map containing access and refresh tokens
     */
    public Map<String, String> register(RegisterRequest request) {
        User existing = userMapper.findByEmail(request.getEmail());
        if (existing != null) {
            throw new IllegalArgumentException("Email already registered: " + request.getEmail());
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);

        log.info("User registered successfully: {}", request.getEmail());
        return generateTokens(user);
    }

    /**
     * Login with email and password.
     *
     * @param request login request
     * @return map containing access and refresh tokens
     */
    public Map<String, String> login(LoginRequest request) {
        User user = userMapper.findByEmail(request.getEmail());
        if (user == null) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        log.info("User logged in: {}", request.getEmail());
        return generateTokens(user);
    }

    /**
     * Refresh the access token using a valid refresh token.
     *
     * @param refreshToken the refresh token
     * @return map containing new access and refresh tokens
     */
    public Map<String, String> refresh(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid or expired refresh token");
        }

        String tokenType = jwtTokenProvider.getTokenType(refreshToken);
        if (!"refresh".equals(tokenType)) {
            throw new IllegalArgumentException("Token is not a refresh token");
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        String email = jwtTokenProvider.getEmailFromToken(refreshToken);

        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", jwtTokenProvider.generateAccessToken(userId, email));
        tokens.put("refreshToken", jwtTokenProvider.generateRefreshToken(userId, email));
        return tokens;
    }

    private Map<String, String> generateTokens(User user) {
        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail()));
        tokens.put("refreshToken", jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail()));
        return tokens;
    }
}
