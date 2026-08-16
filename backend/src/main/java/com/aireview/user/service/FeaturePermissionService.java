package com.aireview.user.service;

import com.aireview.review.feature.ReviewFeature;
import com.aireview.review.feature.ReviewFeatureRegistry;
import com.aireview.user.dto.SystemFeatureDTO;
import com.aireview.user.entity.User;
import com.aireview.user.entity.UserFeatureAssignment;
import com.aireview.user.repository.UserFeatureAssignmentMapper;
import com.aireview.user.repository.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 功能权限的唯一判定入口。角色只决定“能不能管理”，具体业务功能必须显式授权；
 * 平台管理员（内部兼容角色 supervisor）始终拥有全部功能。
 */
@Service
@RequiredArgsConstructor
public class FeaturePermissionService {

    private final UserMapper userMapper;
    private final UserFeatureAssignmentMapper assignmentMapper;
    private final ReviewFeatureRegistry reviewFeatureRegistry;

    public List<SystemFeatureDTO> listAllFeatures() {
        return reviewFeatureRegistry.allFeatures().stream()
                .filter(ReviewFeature::enabled)
                .map(feature -> new SystemFeatureDTO(
                        reviewFeatureRegistry.permissionCode(feature),
                        feature.displayName(), feature.description()))
                .toList();
    }

    public List<String> getFeatureCodes(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return List.of();
        if (MemberService.ROLE_SUPERVISOR.equals(user.getRole())) {
            return listAllFeatures().stream().map(SystemFeatureDTO::getCode).toList();
        }
        return assignmentMapper.findFeatureCodesByUserId(userId);
    }

    public boolean hasFeature(Long userId, String featureCode) {
        if (userId == null || !featureCodes().contains(featureCode)) return false;
        User user = userMapper.selectById(userId);
        if (user == null) return false;
        return MemberService.ROLE_SUPERVISOR.equals(user.getRole())
                || assignmentMapper.exists(userId, featureCode);
    }

    public boolean hasAnyFeature(Long userId) {
        if (userId == null) return false;
        User user = userMapper.selectById(userId);
        if (user == null) return false;
        if (MemberService.ROLE_SUPERVISOR.equals(user.getRole())) return true;
        Set<String> available = featureCodes();
        return assignmentMapper.findFeatureCodesByUserId(userId).stream().anyMatch(available::contains);
    }

    public void requireFeature(Long userId, String featureCode) {
        if (!hasFeature(userId, featureCode)) {
            throw new SecurityException("当前账号未获授权使用该功能");
        }
    }

    public String featureForReviewCategory(String reviewCategory) {
        return reviewFeatureRegistry.permissionCode(
                reviewFeatureRegistry.requireEnabled(reviewCategory));
    }

    public boolean includesSharedRuleLibraryFeature(Collection<String> featureCodes) {
        if (featureCodes == null || featureCodes.isEmpty()) return false;
        return reviewFeatureRegistry.allFeatures().stream()
                .filter(ReviewFeature::enabled)
                .filter(ReviewFeature::usesSharedRuleLibraries)
                .map(reviewFeatureRegistry::permissionCode)
                .anyMatch(featureCodes::contains);
    }

    /** 保存前先归一化并拒绝客户端伪造的功能编号。 */
    @Transactional
    public void replaceAssignments(Long userId, List<String> requestedCodes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (requestedCodes != null) {
            for (String raw : requestedCodes) {
                String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
                if (!featureCodes().contains(code)) {
                    throw new IllegalArgumentException("未知功能权限：" + raw);
                }
                normalized.add(code);
            }
        }
        assignmentMapper.deleteByUserId(userId);
        for (String code : normalized) {
            assignmentMapper.insert(new UserFeatureAssignment(userId, code));
        }
    }

    private Set<String> featureCodes() {
        return reviewFeatureRegistry.enabledPermissionCodes();
    }
}
