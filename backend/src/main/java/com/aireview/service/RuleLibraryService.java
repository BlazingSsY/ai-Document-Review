package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleLibraryDTO;
import com.aireview.entity.Rule;
import com.aireview.entity.RuleLibrary;
import com.aireview.repository.RuleLibraryMapper;
import com.aireview.repository.RuleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleLibraryService {

    private final RuleLibraryMapper ruleLibraryMapper;
    private final RuleMapper ruleMapper;
    private final com.aireview.repository.UserRuleAssignmentMapper userRuleAssignmentMapper;

    public RuleLibraryDTO createLibrary(String name, String description, Long creatorId) {
        RuleLibrary lib = new RuleLibrary();
        lib.setName(name);
        lib.setDescription(description);
        lib.setCreatorId(creatorId);
        lib.setCreatedAt(LocalDateTime.now());
        lib.setUpdatedAt(LocalDateTime.now());
        ruleLibraryMapper.insert(lib);
        log.info("Rule library created: {} by user {}", name, creatorId);
        return toDTO(lib);
    }

    public RuleLibraryDTO updateLibrary(Long id, String name, String description) {
        RuleLibrary lib = ruleLibraryMapper.selectById(id);
        if (lib == null) {
            throw new IllegalArgumentException("规则库不存在");
        }
        lib.setName(name);
        lib.setDescription(description);
        lib.setUpdatedAt(LocalDateTime.now());
        ruleLibraryMapper.updateById(lib);
        return toDTO(lib);
    }

    public void deleteLibrary(Long id) {
        RuleLibrary lib = ruleLibraryMapper.selectById(id);
        if (lib == null) {
            throw new IllegalArgumentException("规则库不存在");
        }
        ruleLibraryMapper.deleteById(id);
        log.info("Rule library deleted: {}", id);
    }

    public PageResponse<RuleLibraryDTO> listLibraries(int page, int size, Long userId, String role) {
        Page<RuleLibrary> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<RuleLibrary> query = new LambdaQueryWrapper<>();
        if ("user".equals(role)) {
            List<Long> assignedIds = userRuleAssignmentMapper.findLibraryIdsByUserId(userId);
            if (assignedIds.isEmpty()) {
                return PageResponse.of(List.of(), 0, page, size);
            }
            query.in(RuleLibrary::getId, assignedIds);
        }
        query.orderByDesc(RuleLibrary::getUpdatedAt);
        Page<RuleLibrary> result = ruleLibraryMapper.selectPage(pageParam, query);
        List<RuleLibraryDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    public List<RuleLibraryDTO> listAllLibraries() {
        LambdaQueryWrapper<RuleLibrary> query = new LambdaQueryWrapper<>();
        query.orderByDesc(RuleLibrary::getUpdatedAt);
        return ruleLibraryMapper.selectList(query).stream().map(this::toDTO).toList();
    }

    private RuleLibraryDTO toDTO(RuleLibrary lib) {
        RuleLibraryDTO dto = new RuleLibraryDTO();
        dto.setId(lib.getId());
        dto.setName(lib.getName());
        dto.setDescription(lib.getDescription());
        dto.setCreatorId(lib.getCreatorId());
        dto.setCreatedAt(lib.getCreatedAt());
        dto.setUpdatedAt(lib.getUpdatedAt());

        LambdaQueryWrapper<Rule> countQuery = new LambdaQueryWrapper<>();
        countQuery.eq(Rule::getLibraryId, lib.getId());
        dto.setRuleCount(Math.toIntExact(ruleMapper.selectCount(countQuery)));

        return dto;
    }
}
