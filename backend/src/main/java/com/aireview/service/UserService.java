package com.aireview.service;

import com.aireview.dto.ChangePasswordRequest;
import com.aireview.dto.PageResponse;
import com.aireview.dto.UserDTO;
import com.aireview.entity.User;
import com.aireview.entity.UserRuleAssignment;
import com.aireview.repository.UserMapper;
import com.aireview.repository.UserRuleAssignmentMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final UserRuleAssignmentMapper userRuleAssignmentMapper;
    private final PasswordEncoder passwordEncoder;

    public UserDTO getUserById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return toDTO(user);
    }

    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("旧密码错误");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(user);
        log.info("Password changed for user: {}", user.getEmail());
    }

    public UserDTO createUser(String email, String password, String name, String role) {
        User existing = userMapper.findByEmail(email);
        if (existing != null) {
            throw new IllegalArgumentException("该账号已存在: " + email);
        }
        if (!"admin".equals(role) && !"user".equals(role)) {
            role = "user";
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setName(name != null && !name.isBlank() ? name : email.split("@")[0]);
        user.setRole(role);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        log.info("User created by admin: {} with role {}", email, role);
        return toDTO(user);
    }

    public PageResponse<UserDTO> listAllUsers(int page, int size) {
        Page<User> pageParam = new Page<>(page, size);
        Page<User> result = userMapper.selectPage(pageParam, null);
        List<UserDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    public void updateUserRole(Long targetUserId, String role, Long operatorId) {
        if (targetUserId.equals(operatorId)) {
            throw new IllegalArgumentException("不能修改自己的角色");
        }

        User target = userMapper.selectById(targetUserId);
        if (target == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        if ("supervisor".equals(target.getRole())) {
            throw new IllegalArgumentException("不能修改项目主管的角色");
        }

        if (!"admin".equals(role) && !"user".equals(role)) {
            throw new IllegalArgumentException("角色只能设置为 admin 或 user");
        }

        target.setRole(role);
        userMapper.updateById(target);
        log.info("User {} role changed to {} by operator {}", targetUserId, role, operatorId);
    }

    @Transactional
    public void assignLibrariesToUser(Long userId, List<Long> libraryIds) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        userRuleAssignmentMapper.deleteByUserId(userId);
        for (Long libId : libraryIds) {
            userRuleAssignmentMapper.insert(new UserRuleAssignment(userId, libId));
        }
        log.info("Assigned {} rule libraries to user {}", libraryIds.size(), userId);
    }

    public List<Long> getAssignedLibraryIds(Long userId) {
        return userRuleAssignmentMapper.findLibraryIdsByUserId(userId);
    }

    private UserDTO toDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setName(user.getName() != null ? user.getName() : user.getEmail().split("@")[0]);
        dto.setRole(user.getRole() != null ? user.getRole() : "user");
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }
}
