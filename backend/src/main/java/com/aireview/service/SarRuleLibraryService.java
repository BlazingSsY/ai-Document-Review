package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleLibraryDTO;
import com.aireview.entity.SarRule;
import com.aireview.entity.SarRuleLibrary;
import com.aireview.repository.SarRuleLibraryMapper;
import com.aireview.repository.SarRuleMapper;
import com.aireview.repository.SarUserRuleAssignmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SAR 侧规则库服务。与 {@link RuleLibraryService} 结构对称，仅注入的 mapper 不同——
 * 物理隔离要求两侧的库 / 规则 / 用户分配各自走自己的表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SarRuleLibraryService {

    private final SarRuleLibraryMapper sarRuleLibraryMapper;
    private final SarRuleMapper sarRuleMapper;
    private final SarUserRuleAssignmentMapper sarUserRuleAssignmentMapper;

    public RuleLibraryDTO createLibrary(String name, String description, Long creatorId) {
        SarRuleLibrary lib = new SarRuleLibrary();
        lib.setName(name);
        lib.setDescription(description);
        lib.setCreatorId(creatorId);
        lib.setCreatedAt(LocalDateTime.now());
        lib.setUpdatedAt(LocalDateTime.now());
        sarRuleLibraryMapper.insert(lib);
        log.info("SAR rule library created: {} by user {}", name, creatorId);
        return toDTO(lib);
    }

    public RuleLibraryDTO updateLibrary(Long id, String name, String description) {
        SarRuleLibrary lib = sarRuleLibraryMapper.selectById(id);
        if (lib == null) {
            throw new IllegalArgumentException("SAR 规则库不存在");
        }
        lib.setName(name);
        lib.setDescription(description);
        lib.setUpdatedAt(LocalDateTime.now());
        sarRuleLibraryMapper.updateById(lib);
        return toDTO(lib);
    }

    public void deleteLibrary(Long id) {
        SarRuleLibrary lib = sarRuleLibraryMapper.selectById(id);
        if (lib == null) {
            throw new IllegalArgumentException("SAR 规则库不存在");
        }
        sarRuleLibraryMapper.deleteById(id);
        log.info("SAR rule library deleted: {}", id);
    }

    public PageResponse<RuleLibraryDTO> listLibraries(int page, int size, Long userId, String role) {
        Page<SarRuleLibrary> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<SarRuleLibrary> query = new LambdaQueryWrapper<>();
        if ("user".equals(role)) {
            List<Long> assignedIds = sarUserRuleAssignmentMapper.findLibraryIdsByUserId(userId);
            if (assignedIds.isEmpty()) {
                return PageResponse.of(List.of(), 0, page, size);
            }
            query.in(SarRuleLibrary::getId, assignedIds);
        }
        query.orderByDesc(SarRuleLibrary::getUpdatedAt);
        Page<SarRuleLibrary> result = sarRuleLibraryMapper.selectPage(pageParam, query);
        List<RuleLibraryDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    public List<RuleLibraryDTO> listAllLibraries() {
        LambdaQueryWrapper<SarRuleLibrary> query = new LambdaQueryWrapper<>();
        query.orderByDesc(SarRuleLibrary::getUpdatedAt);
        return sarRuleLibraryMapper.selectList(query).stream().map(this::toDTO).toList();
    }

    private RuleLibraryDTO toDTO(SarRuleLibrary lib) {
        RuleLibraryDTO dto = new RuleLibraryDTO();
        dto.setId(lib.getId());
        dto.setName(lib.getName());
        dto.setDescription(lib.getDescription());
        dto.setCreatorId(lib.getCreatorId());
        dto.setCreatedAt(lib.getCreatedAt());
        dto.setUpdatedAt(lib.getUpdatedAt());

        LambdaQueryWrapper<SarRule> countQuery = new LambdaQueryWrapper<>();
        countQuery.eq(SarRule::getLibraryId, lib.getId());
        dto.setRuleCount(Math.toIntExact(sarRuleMapper.selectCount(countQuery)));

        return dto;
    }
}
