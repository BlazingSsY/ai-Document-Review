package com.aireview.auth.security;

import com.alibaba.fastjson2.JSON;
import com.aireview.common.dto.ApiResponse;
import com.aireview.user.service.FeaturePermissionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** 服务端功能开关，防止仅靠隐藏前端菜单被直接调用接口绕过。 */
@Component
@RequiredArgsConstructor
public class FeatureAuthorizationFilter extends OncePerRequestFilter {

    private static final List<String> ENV_REVIEW_PATHS = List.of(
            "/api/v1/reviews",
            "/api/v1/scenarios",
            "/api/v1/rules",
            "/api/v1/rule-libraries",
            "/api/v1/sar/reviews",
            "/api/v1/sar/scenarios",
            "/api/v1/sar/rules",
            "/api/v1/sar/rule-libraries"
    );

    private final FeaturePermissionService featurePermissionService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return ENV_REVIEW_PATHS.stream().noneMatch(
                prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Long userId
                && !featurePermissionService.hasFeature(
                        userId, FeaturePermissionService.ENV_TEST_OUTLINE_REVIEW)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSON.toJSONString(
                    ApiResponse.error(403, "当前账号未获授权使用环境试验大纲审查功能")));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
