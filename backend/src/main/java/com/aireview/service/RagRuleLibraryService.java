package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleLibraryDTO;
import com.aireview.entity.RagRule;
import com.aireview.entity.RagRuleLibrary;
import com.aireview.repository.RagRuleLibraryMapper;
import com.aireview.repository.RagRuleMapper;
import com.aireview.repository.RagUserRuleAssignmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 侧规则库服务。与 {@link RuleLibraryService} 结构对称，仅注入的 mapper 不同——
 * 物理隔离要求两侧的库 / 规则 / 用户分配各自走自己的表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRuleLibraryService {

    private final RagRuleLibraryMapper ragRuleLibraryMapper;
    private final RagRuleMapper ragRuleMapper;
    private final RagUserRuleAssignmentMapper ragUserRuleAssignmentMapper;

    public RuleLibraryDTO createLibrary(String name, String description, Long creatorId) {
        RagRuleLibrary lib = new RagRuleLibrary();
        lib.setName(name);
        lib.setDescription(description);
        lib.setCreatorId(creatorId);
        lib.setCreatedAt(LocalDateTime.now());
        lib.setUpdatedAt(LocalDateTime.now());
        ragRuleLibraryMapper.insert(lib);
        log.info("RAG rule library created: {} by user {}", name, creatorId);
        return toDTO(lib);
    }

    public RuleLibraryDTO updateLibrary(Long id, String name, String description) {
        RagRuleLibrary lib = ragRuleLibraryMapper.selectById(id);
        if (lib == null) {
            throw new IllegalArgumentException("RAG 规则库不存在");
        }
        lib.setName(name);
        lib.setDescription(description);
        lib.setUpdatedAt(LocalDateTime.now());
        ragRuleLibraryMapper.updateById(lib);
        return toDTO(lib);
    }

    public void deleteLibrary(Long id) {
        RagRuleLibrary lib = ragRuleLibraryMapper.selectById(id);
        if (lib == null) {
            throw new IllegalArgumentException("RAG 规则库不存在");
        }
        ragRuleLibraryMapper.deleteById(id);
        log.info("RAG rule library deleted: {}", id);
    }

    public PageResponse<RuleLibraryDTO> listLibraries(int page, int size, Long userId, String role) {
        Page<RagRuleLibrary> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<RagRuleLibrary> query = new LambdaQueryWrapper<>();
        if ("user".equals(role)) {
            List<Long> assignedIds = ragUserRuleAssignmentMapper.findLibraryIdsByUserId(userId);
            if (assignedIds.isEmpty()) {
                return PageResponse.of(List.of(), 0, page, size);
            }
            query.in(RagRuleLibrary::getId, assignedIds);
        }
        query.orderByDesc(RagRuleLibrary::getUpdatedAt);
        Page<RagRuleLibrary> result = ragRuleLibraryMapper.selectPage(pageParam, query);
        List<RuleLibraryDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    public List<RuleLibraryDTO> listAllLibraries() {
        LambdaQueryWrapper<RagRuleLibrary> query = new LambdaQueryWrapper<>();
        query.orderByDesc(RagRuleLibrary::getUpdatedAt);
        return ragRuleLibraryMapper.selectList(query).stream().map(this::toDTO).toList();
    }

    private RuleLibraryDTO toDTO(RagRuleLibrary lib) {
        RuleLibraryDTO dto = new RuleLibraryDTO();
        dto.setId(lib.getId());
        dto.setName(lib.getName());
        dto.setDescription(lib.getDescription());
        dto.setCreatorId(lib.getCreatorId());
        dto.setCreatedAt(lib.getCreatedAt());
        dto.setUpdatedAt(lib.getUpdatedAt());

        LambdaQueryWrapper<RagRule> countQuery = new LambdaQueryWrapper<>();
        countQuery.eq(RagRule::getLibraryId, lib.getId());
        dto.setRuleCount(Math.toIntExact(ragRuleMapper.selectCount(countQuery)));

        return dto;
    }
}
