package com.aireview.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public class SecurityUtils {

    public static String getRoleFromAuthentication(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5).toLowerCase())
                .findFirst()
                .orElse("user");
    }

    public static Long getUserId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }
}
