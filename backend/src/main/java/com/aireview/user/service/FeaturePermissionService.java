package com.aireview.user.service;

import com.aireview.review.core.ReviewCategory;
import com.aireview.user.dto.SystemFeatureDTO;
import com.aireview.user.entity.User;
import com.aireview.user.entity.UserFeatureAssignment;
import com.aireview.user.repository.UserFeatureAssignmentMapper;
import com.aireview.user.repository.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 功能权限的唯一判定入口。角色只决定“能不能管理”，具体业务功能必须显式授权；
 * 平台管理员（内部兼容角色 supervisor）始终拥有全部功能。
 */
@Service
@RequiredArgsConstructor
public class FeaturePermissionService {

    public static final String ENV_TEST_OUTLINE_REVIEW = "ENV_TEST_OUTLINE_REVIEW";

    private static final List<SystemFeatureDTO> FEATURES = List.of(
            new SystemFeatureDTO(
                    ENV_TEST_OUTLINE_REVIEW,
                    "环境试验大纲审查",
                    "使用全文逐章审查场景和规则库，对环境试验大纲发起、查看及重审任务")
    );
    private static final Set<String> FEATURE_CODES = Set.of(ENV_TEST_OUTLINE_REVIEW);

    private final UserMapper userMapper;
    private final UserFeatureAssignmentMapper assignmentMapper;

    public List<SystemFeatureDTO> listAllFeatures() {
        return FEATURES;
    }

    public List<String> getFeatureCodes(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return List.of();
        if (MemberService.ROLE_SUPERVISOR.equals(user.getRole())) {
            return FEATURES.stream().map(SystemFeatureDTO::getCode).toList();
        }
        return assignmentMapper.findFeatureCodesByUserId(userId);
    }

    public boolean hasFeature(Long userId, String featureCode) {
        if (userId == null || !FEATURE_CODES.contains(featureCode)) return false;
        User user = userMapper.selectById(userId);
        if (user == null) return false;
        return MemberService.ROLE_SUPERVISOR.equals(user.getRole())
                || assignmentMapper.exists(userId, featureCode);
    }

    public void requireFeature(Long userId, String featureCode) {
        if (!hasFeature(userId, featureCode)) {
            throw new SecurityException("当前账号未获授权使用该功能");
        }
    }

    public String featureForReviewCategory(String reviewCategory) {
        String normalized = ReviewCategory.normalize(reviewCategory);
        if (ReviewCategory.ENV_TEST_OUTLINE.equals(normalized)) {
            return ENV_TEST_OUTLINE_REVIEW;
        }
        throw new IllegalArgumentException("尚未配置该审查类别的功能权限：" + normalized);
    }

    /** 保存前先归一化并拒绝客户端伪造的功能编号。 */
    @Transactional
    public void replaceAssignments(Long userId, List<String> requestedCodes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (requestedCodes != null) {
            for (String raw : requestedCodes) {
                String code = raw == null ? "" : raw.trim().toUpperCase();
                if (!FEATURE_CODES.contains(code)) {
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
}
