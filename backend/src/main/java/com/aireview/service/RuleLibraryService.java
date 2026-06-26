package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleFolderDTO;
import com.aireview.dto.RuleLibraryDTO;
import com.aireview.entity.Rule;
import com.aireview.entity.RuleFolder;
import com.aireview.entity.RuleLibrary;
import com.aireview.repository.RuleFolderMapper;
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
    private final RuleFolderMapper ruleFolderMapper;
    private final com.aireview.repository.UserRuleAssignmentMapper userRuleAssignmentMapper;

    /**
     * 系统内置规则库名（schema.sql 播种，承载内置「基础文字质量审查」R-BASIC-QUALITY）。
     * 该库由全文质量检查内置注入、不走规则库/场景路由，故对用户列表隐藏，避免误删/误用。
     */
    private static final String BUILTIN_LIBRARY_NAME = "系统内置规则";

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
        query.ne(RuleLibrary::getName, BUILTIN_LIBRARY_NAME); // 隐藏系统内置规则库
        query.orderByDesc(RuleLibrary::getUpdatedAt);
        Page<RuleLibrary> result = ruleLibraryMapper.selectPage(pageParam, query);
        List<RuleLibraryDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    public List<RuleLibraryDTO> listAllLibraries() {
        LambdaQueryWrapper<RuleLibrary> query = new LambdaQueryWrapper<>();
        query.ne(RuleLibrary::getName, BUILTIN_LIBRARY_NAME); // 隐藏系统内置规则库
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

    // ===================== 二级文件夹 =====================

    public List<RuleFolderDTO> listFolders(Long libraryId) {
        LambdaQueryWrapper<RuleFolder> query = new LambdaQueryWrapper<>();
        query.eq(RuleFolder::getLibraryId, libraryId)
                .orderByAsc(RuleFolder::getId);
        return ruleFolderMapper.selectList(query).stream().map(this::toFolderDTO).toList();
    }

    public RuleFolderDTO createFolder(Long libraryId, String name, Long creatorId) {
        RuleFolder folder = new RuleFolder();
        folder.setLibraryId(libraryId);
        folder.setName(name);
        folder.setEnabled(true);
        folder.setCreatorId(creatorId);
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());
        ruleFolderMapper.insert(folder);
        log.info("Rule folder created: {} in library {}", name, libraryId);
        return toFolderDTO(folder);
    }

    public RuleFolderDTO updateFolder(Long folderId, String name, Boolean enabled) {
        RuleFolder folder = ruleFolderMapper.selectById(folderId);
        if (folder == null) {
            throw new IllegalArgumentException("文件夹不存在");
        }
        if (name != null && !name.isBlank()) folder.setName(name.trim());
        if (enabled != null) folder.setEnabled(enabled);
        folder.setUpdatedAt(LocalDateTime.now());
        ruleFolderMapper.updateById(folder);
        return toFolderDTO(folder);
    }

    /** 删除文件夹：连同其中所有规则一并删除（rule_checks 由外键级联清除），不保留未分类。 */
    public void deleteFolder(Long folderId) {
        RuleFolder folder = ruleFolderMapper.selectById(folderId);
        if (folder == null) {
            throw new IllegalArgumentException("文件夹不存在");
        }
        LambdaQueryWrapper<Rule> dq = new LambdaQueryWrapper<>();
        dq.eq(Rule::getFolderId, folderId);
        int removed = ruleMapper.delete(dq);
        ruleFolderMapper.deleteById(folderId);
        log.info("Rule folder deleted: {} (and {} rule(s) within it)", folderId, removed);
    }

    private RuleFolderDTO toFolderDTO(RuleFolder folder) {
        RuleFolderDTO dto = new RuleFolderDTO();
        dto.setId(folder.getId());
        dto.setLibraryId(folder.getLibraryId());
        dto.setName(folder.getName());
        dto.setEnabled(folder.getEnabled());
        dto.setCreatorId(folder.getCreatorId());
        dto.setCreatedAt(folder.getCreatedAt());
        dto.setUpdatedAt(folder.getUpdatedAt());
        LambdaQueryWrapper<Rule> countQuery = new LambdaQueryWrapper<>();
        countQuery.eq(Rule::getFolderId, folder.getId());
        dto.setRuleCount(Math.toIntExact(ruleMapper.selectCount(countQuery)));
        return dto;
    }
}
