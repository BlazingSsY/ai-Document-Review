package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleCheckDTO;
import com.aireview.dto.RuleContentUpdateRequest;
import com.aireview.dto.RuleDTO;
import com.aireview.dto.RuleMetadataUpdateRequest;
import com.aireview.dto.RuleUploadConflictDTO;
import com.aireview.entity.SarRule;
import com.aireview.entity.SarRuleCheck;
import com.aireview.entity.SarRuleFolder;
import com.aireview.repository.SarRuleCheckMapper;
import com.aireview.repository.SarRuleFolderMapper;
import com.aireview.repository.SarRuleMapper;
import com.aireview.repository.SarUserRuleAssignmentMapper;
import com.aireview.util.MultiRuleParser;
import com.aireview.util.RuleMetadata;
import com.aireview.util.RuleParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SAR 侧规则服务。与 {@link RuleService} 结构对称，不同之处：
 *
 * <ul>
 *   <li>写入 {@code sar_rules} / {@code sar_rule_checks} 而不是 chunk 表；</li>
 *   <li>认真持久化 rule_checks——这是 SAR 管线的核心数据（原子检查项）。</li>
 * </ul>
 *
 * 上传文件落盘到同一个 {@code rules-dir}，因为这只是源文件备份，与是哪条管线无关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SarRuleService {

    private final SarRuleMapper sarRuleMapper;
    private final SarRuleCheckMapper sarRuleCheckMapper;
    private final SarRuleFolderMapper sarRuleFolderMapper;
    private final SarUserRuleAssignmentMapper sarUserRuleAssignmentMapper;

    @Value("${file.rules-dir}")
    private String rulesDir;

    public List<RuleDTO> uploadRuleAll(MultipartFile file, Long creatorId, Long libraryId) throws IOException {
        return uploadRuleAll(file, creatorId, libraryId, null);
    }

    public List<RuleDTO> uploadRuleAll(MultipartFile file, Long creatorId, Long libraryId, Long folderId) throws IOException {
        return uploadRuleAll(file, creatorId, libraryId, folderId, false);
    }

    public List<RuleDTO> uploadRuleAll(MultipartFile file, Long creatorId, Long libraryId,
                                       Long folderId, boolean replaceExisting) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("File name is required");
        }

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return importRuleContent(originalFilename, content, creatorId, libraryId, folderId, true, replaceExisting);
    }

    public List<RuleDTO> importRuleContent(String originalFilename, String content,
                                           Long creatorId, Long libraryId,
                                           boolean persistSourceFile) throws IOException {
        return importRuleContent(originalFilename, content, creatorId, libraryId, null, persistSourceFile);
    }

    public List<RuleDTO> importRuleContent(String originalFilename, String content,
                                           Long creatorId, Long libraryId, Long folderId,
                                           boolean persistSourceFile) throws IOException {
        return importRuleContent(originalFilename, content, creatorId, libraryId, folderId, persistSourceFile, false);
    }

    public List<RuleDTO> importRuleContent(String originalFilename, String content,
                                           Long creatorId, Long libraryId, Long folderId,
                                           boolean persistSourceFile, boolean replaceExisting) throws IOException {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("File name is required");
        }

        String fileType = RuleParser.detectFileType(originalFilename);
        if ("unknown".equals(fileType)) {
            throw new IllegalArgumentException("Unsupported rule file format. Only .md and .json are supported.");
        }

        List<String> errors = RuleParser.validate(content, fileType);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Rule validation failed: " + String.join("; ", errors));
        }

        if (persistSourceFile) {
            Path uploadDir = Path.of(rulesDir);
            Files.createDirectories(uploadDir);
            String savedFileName = UUID.randomUUID() + "_" + originalFilename;
            Path savedPath = uploadDir.resolve(savedFileName);
            Files.writeString(savedPath, content, StandardCharsets.UTF_8);
        }

        List<MultiRuleParser.ParsedRule> parsed = MultiRuleParser.parse(originalFilename, fileType, content);
        List<SarRule> existingRules = findExistingRulesInScope(libraryId, folderId);
        Map<String, SarRule> existingByCode = indexByRuleCode(existingRules);
        Map<String, SarRule> existingByName = indexByRuleName(existingRules);
        Set<Long> updatedExistingIds = new HashSet<>();
        List<RuleDTO> out = new ArrayList<>();
        for (MultiRuleParser.ParsedRule pr : parsed) {
            SarRule rule = findRuleToUpdate(pr, existingByCode, existingByName, updatedExistingIds);
            boolean update = rule != null;
            if (!update) {
                rule = new SarRule();
            }
            applyParsedRule(rule, pr, originalFilename, creatorId, libraryId, folderId);
            if (update) {
                sarRuleMapper.updateById(rule);
                sarRuleCheckMapper.delete(new LambdaQueryWrapper<SarRuleCheck>().eq(SarRuleCheck::getRuleId, rule.getId()));
                updatedExistingIds.add(rule.getId());
            } else {
                sarRuleMapper.insert(rule);
            }
            persistChecks(rule.getId(), pr.getChecks());
            out.add(toDTO(rule));
        }
        log.info("SAR rule file '{}' upserted into {} rule row(s) (creator={}, library={}, folder={}, replaceExistingIgnored={})",
                originalFilename, out.size(), creatorId, libraryId, folderId, replaceExisting);
        return out;
    }

    private List<SarRule> findExistingRulesInScope(Long libraryId, Long folderId) {
        LambdaQueryWrapper<SarRule> query = new LambdaQueryWrapper<>();
        query.eq(SarRule::getIsValid, true)
                .select(SarRule::getId, SarRule::getRuleName, SarRule::getRuleCode, SarRule::getSourceFile,
                        SarRule::getLibraryId, SarRule::getFolderId, SarRule::getUpdatedAt);
        if (libraryId == null) {
            query.isNull(SarRule::getLibraryId);
        } else {
            query.eq(SarRule::getLibraryId, libraryId);
        }
        if (folderId == null) {
            query.isNull(SarRule::getFolderId);
        } else {
            query.eq(SarRule::getFolderId, folderId);
        }
        query.orderByAsc(SarRule::getId);
        return sarRuleMapper.selectList(query);
    }

    private Map<String, SarRule> indexByRuleCode(List<SarRule> rules) {
        Map<String, SarRule> out = new LinkedHashMap<>();
        for (SarRule rule : rules) {
            String key = normalizeKey(rule.getRuleCode());
            if (key != null) out.putIfAbsent(key, rule);
        }
        return out;
    }

    private Map<String, SarRule> indexByRuleName(List<SarRule> rules) {
        Map<String, SarRule> out = new LinkedHashMap<>();
        for (SarRule rule : rules) {
            String key = normalizeKey(rule.getRuleName());
            if (key != null) out.putIfAbsent(key, rule);
        }
        return out;
    }

    private SarRule findRuleToUpdate(MultiRuleParser.ParsedRule parsed,
                                     Map<String, SarRule> existingByCode,
                                     Map<String, SarRule> existingByName,
                                     Set<Long> updatedExistingIds) {
        RuleMetadata meta = parsed.getMetadata();
        SarRule match = null;
        if (meta != null) {
            match = existingByCode.get(normalizeKey(meta.getRuleCode()));
        }
        if (match == null) {
            match = existingByName.get(normalizeKey(parsed.getName()));
        }
        return match != null && !updatedExistingIds.contains(match.getId()) ? match : null;
    }

    private void applyParsedRule(SarRule rule, MultiRuleParser.ParsedRule pr, String sourceFile,
                                 Long creatorId, Long libraryId, Long folderId) {
        rule.setRuleName(pr.getName());
        rule.setFileType(pr.getFileType());
        rule.setContent(pr.getContent());
        rule.setCreatorId(creatorId);
        rule.setLibraryId(libraryId);
        rule.setFolderId(folderId);
        rule.setUpdatedAt(LocalDateTime.now());
        rule.setIsValid(true);
        rule.setSourceFile(sourceFile);
        RuleMetadata meta = pr.getMetadata();
        rule.setRuleCode(meta == null ? null : meta.getRuleCode());
        rule.setRuleType(meta == null ? null : meta.getRuleType());
        rule.setDocumentType(meta == null ? null : meta.getDocumentType());
        rule.setSections(meta == null ? null : emptyToNull(meta.getSections()));
        rule.setKeywords(meta == null ? null : emptyToNull(meta.getKeywords()));
        rule.setDescription(pr.getDescription());
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public List<RuleUploadConflictDTO> findUploadConflicts(String sourceFile, Long libraryId, Long folderId) {
        if (sourceFile == null || sourceFile.isBlank()) return List.of();
        LambdaQueryWrapper<SarRule> query = new LambdaQueryWrapper<>();
        query.eq(SarRule::getSourceFile, sourceFile)
                .select(SarRule::getId, SarRule::getRuleName, SarRule::getRuleCode, SarRule::getSourceFile,
                        SarRule::getLibraryId, SarRule::getFolderId, SarRule::getUpdatedAt)
                .orderByAsc(SarRule::getId);
        if (libraryId == null) {
            query.isNull(SarRule::getLibraryId);
        } else {
            query.eq(SarRule::getLibraryId, libraryId);
        }
        if (folderId == null) {
            query.isNull(SarRule::getFolderId);
        } else {
            query.eq(SarRule::getFolderId, folderId);
        }
        return sarRuleMapper.selectList(query).stream()
                .map(rule -> new RuleUploadConflictDTO(
                        rule.getId(),
                        rule.getRuleName(),
                        rule.getRuleCode(),
                        rule.getSourceFile(),
                        rule.getLibraryId(),
                        rule.getFolderId(),
                        rule.getUpdatedAt()))
                .toList();
    }

    private void persistChecks(Long ruleId, List<MultiRuleParser.ParsedCheck> checks) {
        if (ruleId == null || checks == null || checks.isEmpty()) return;
        for (MultiRuleParser.ParsedCheck parsed : checks) {
            SarRuleCheck check = new SarRuleCheck();
            check.setRuleId(ruleId);
            check.setCheckCode(parsed.getCheckCode());
            check.setCheckType(parsed.getCheckType());
            check.setQuestion(parsed.getQuestion());
            check.setPassCriteria(parsed.getPassCriteria());
            check.setCategory(parsed.getCategory());
            check.setEvidenceRequired(parsed.getEvidenceRequired());
            check.setDisplayOrder(parsed.getDisplayOrder());
            check.setIsActive(true);
            sarRuleCheckMapper.insert(check);
        }
    }

    public RuleDTO uploadRule(MultipartFile file, Long creatorId, Long libraryId) throws IOException {
        List<RuleDTO> all = uploadRuleAll(file, creatorId, libraryId, null);
        return all.isEmpty() ? null : all.get(0);
    }

    private static <T> List<T> emptyToNull(List<T> list) {
        return list == null || list.isEmpty() ? null : list;
    }

    public PageResponse<RuleDTO> listRules(int page, int size, Long userId, String role, Long libraryId) {
        return listRules(page, size, userId, role, libraryId, null, false);
    }

    /**
     * @param folderId      仅返回该文件夹下的规则（为 null 时不按文件夹过滤，除非 uncategorized=true）
     * @param uncategorized 为 true 时只返回未分类规则（folder_id IS NULL），优先级高于 folderId
     */
    public PageResponse<RuleDTO> listRules(int page, int size, Long userId, String role,
                                           Long libraryId, Long folderId, boolean uncategorized) {
        Page<SarRule> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<SarRule> query = new LambdaQueryWrapper<>();

        if ("user".equals(role)) {
            List<Long> assignedLibraryIds = sarUserRuleAssignmentMapper.findLibraryIdsByUserId(userId);
            if (assignedLibraryIds.isEmpty()) {
                return PageResponse.of(List.of(), 0, page, size);
            }
            query.in(SarRule::getLibraryId, assignedLibraryIds);
        }
        if (libraryId != null) {
            query.eq(SarRule::getLibraryId, libraryId);
        }
        if (uncategorized) {
            query.isNull(SarRule::getFolderId);
        } else if (folderId != null) {
            query.eq(SarRule::getFolderId, folderId);
        }
        query.select(
                SarRule::getId,
                SarRule::getRuleName,
                SarRule::getFileType,
                SarRule::getCreatorId,
                SarRule::getLibraryId,
                SarRule::getFolderId,
                SarRule::getUpdatedAt,
                SarRule::getIsValid,
                SarRule::getRuleCode,
                SarRule::getRuleType,
                SarRule::getDocumentType,
                SarRule::getSections,
                SarRule::getKeywords,
                SarRule::getDescription,
                SarRule::getSourceFile);
        query.orderByDesc(SarRule::getUpdatedAt);

        Page<SarRule> result = sarRuleMapper.selectPage(pageParam, query);
        List<RuleDTO> records = result.getRecords().stream().map(this::toSummaryDTO).toList();

        return PageResponse.of(records, result.getTotal(), page, size);
    }

    public RuleDTO getRuleById(Long id) {
        SarRule rule = sarRuleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("SAR rule not found: " + id);
        }
        return toDTO(rule);
    }

    public void deleteRule(Long id) {
        SarRule rule = sarRuleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("SAR rule not found: " + id);
        }
        sarRuleMapper.deleteById(id);
        log.info("SAR rule deleted: {}", id);
    }

    public List<SarRule> getRulesByScenarioId(Long scenarioId) {
        List<Long> ids = sarRuleMapper.findIdsByScenarioId(scenarioId);
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        List<SarRule> rules = sarRuleMapper.selectBatchIds(ids);
        // 停用文件夹中的规则在审查时整组排除；未分类（folder_id 为 null）恒保留。
        Set<Long> disabled = disabledFolderIds();
        if (disabled.isEmpty()) return rules;
        return rules.stream()
                .filter(r -> r.getFolderId() == null || !disabled.contains(r.getFolderId()))
                .collect(Collectors.toList());
    }

    private Set<Long> disabledFolderIds() {
        LambdaQueryWrapper<SarRuleFolder> q = new LambdaQueryWrapper<>();
        q.eq(SarRuleFolder::getEnabled, false).select(SarRuleFolder::getId);
        return sarRuleFolderMapper.selectList(q).stream()
                .map(SarRuleFolder::getId)
                .collect(Collectors.toSet());
    }

    public RuleDTO updateMetadata(Long id, RuleMetadataUpdateRequest req) {
        SarRule rule = sarRuleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("SAR rule not found: " + id);
        }
        LocalDateTime originalUpdatedAt = rule.getUpdatedAt();
        if (req.getRuleName() != null && !req.getRuleName().isBlank()) rule.setRuleName(req.getRuleName().trim());
        if (req.getRuleCode() != null)     rule.setRuleCode(blankToNull(req.getRuleCode()));
        if (req.getRuleType() != null)     rule.setRuleType(blankToNull(req.getRuleType()));
        if (req.getDocumentType() != null) rule.setDocumentType(blankToNull(req.getDocumentType()));
        if (req.getSections() != null)     rule.setSections(emptyToNull(req.getSections()));
        if (req.getKeywords() != null)     rule.setKeywords(emptyToNull(req.getKeywords()));
        if (req.getDescription() != null)  rule.setDescription(blankToNull(req.getDescription()));
        sarRuleMapper.updateById(rule);
        // Metadata edits should not affect list ordering. updateById triggers the
        // global updatedAt auto-fill, so restore the prior sort timestamp.
        sarRuleMapper.update(null, new LambdaUpdateWrapper<SarRule>()
                .eq(SarRule::getId, id)
                .set(SarRule::getUpdatedAt, originalUpdatedAt));
        rule.setUpdatedAt(originalUpdatedAt);
        log.info("SAR rule {} metadata updated: code={}, type={}, sections={}, keywords={}",
                id, rule.getRuleCode(), rule.getRuleType(), rule.getSections(),
                rule.getKeywords());
        return toDTO(rule);
    }

    /**
     * 编辑规则内容：正文（content）与原子检查项（sar_rule_checks）。content 为 null 不改；
     * checks 为 null 不改，非 null（含空列表）整体替换该规则的检查项。
     */
    public RuleDTO updateContent(Long id, RuleContentUpdateRequest req) {
        SarRule rule = sarRuleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("SAR rule not found: " + id);
        }
        if (req.getContent() != null) {
            rule.setContent(req.getContent());
        }
        rule.setUpdatedAt(LocalDateTime.now());
        sarRuleMapper.updateById(rule);

        if (req.getChecks() != null) {
            sarRuleCheckMapper.delete(new LambdaQueryWrapper<SarRuleCheck>().eq(SarRuleCheck::getRuleId, id));
            int order = 1;
            for (RuleCheckDTO c : req.getChecks()) {
                SarRuleCheck check = new SarRuleCheck();
                check.setRuleId(id);
                String code = blankToNull(c.getCheckCode());
                check.setCheckCode(code != null ? code
                        : (rule.getRuleCode() != null && !rule.getRuleCode().isBlank() ? rule.getRuleCode() : "R")
                          + "-C" + String.format("%03d", order));
                String type = blankToNull(c.getCheckType());
                check.setCheckType(type != null ? type : "presence");
                check.setQuestion(c.getQuestion());
                check.setPassCriteria(c.getPassCriteria());
                check.setCategory(blankToNull(c.getCategory()));
                check.setEvidenceRequired(c.getEvidenceRequired() == null ? Boolean.TRUE : c.getEvidenceRequired());
                check.setDisplayOrder(c.getDisplayOrder() != null ? c.getDisplayOrder() : order);
                check.setIsActive(true);
                sarRuleCheckMapper.insert(check);
                order++;
            }
        }
        log.info("SAR rule {} content updated (checks: {})", id,
                req.getChecks() == null ? "unchanged" : req.getChecks().size());
        return toDTO(rule);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private RuleDTO toSummaryDTO(SarRule rule) {
        RuleDTO dto = new RuleDTO();
        dto.setId(rule.getId());
        dto.setRuleName(rule.getRuleName());
        dto.setFileType(rule.getFileType());
        dto.setCreatorId(rule.getCreatorId());
        dto.setLibraryId(rule.getLibraryId());
        dto.setFolderId(rule.getFolderId());
        dto.setUpdatedAt(rule.getUpdatedAt());
        dto.setIsValid(rule.getIsValid());
        dto.setDescription(rule.getDescription());
        dto.setSourceFile(rule.getSourceFile());
        dto.setRuleCode(rule.getRuleCode());
        dto.setRuleType(rule.getRuleType());
        dto.setDocumentType(rule.getDocumentType());
        dto.setSections(rule.getSections());
        dto.setKeywords(rule.getKeywords());

        String content = rule.getContent();
        if (content != null && !content.isBlank()) {
            boolean anyMetaInDb = dto.getRuleCode() != null || dto.getRuleType() != null
                    || (dto.getSections() != null && !dto.getSections().isEmpty())
                    || (dto.getKeywords() != null && !dto.getKeywords().isEmpty());
            if (!anyMetaInDb) {
                RuleMetadata meta = RuleMetadata.parse(content, rule.getFileType());
                if (dto.getRuleCode() == null) dto.setRuleCode(meta.getRuleCode());
                if (dto.getRuleType() == null) dto.setRuleType(meta.getRuleType());
                if (dto.getDocumentType() == null) dto.setDocumentType(meta.getDocumentType());
                if (dto.getSections() == null && meta.getSections() != null && !meta.getSections().isEmpty()) {
                    dto.setSections(meta.getSections());
                }
                if (dto.getKeywords() == null && meta.getKeywords() != null && !meta.getKeywords().isEmpty()) {
                    dto.setKeywords(meta.getKeywords());
                }
            }
        }
        return dto;
    }

    private RuleDTO toDTO(SarRule rule) {
        RuleDTO dto = toSummaryDTO(rule);

        String content = rule.getContent();
        if (content == null || content.isBlank()) {
            content = readContentFromDisk(rule.getRuleName(), rule.getFileType());
            if (content != null && !content.isBlank()) {
                SarRule update = new SarRule();
                update.setId(rule.getId());
                update.setContent(content);
                update.setUpdatedAt(rule.getUpdatedAt());
                sarRuleMapper.updateById(update);
                log.info("Recovered and persisted content for SAR rule id={} name='{}'",
                        rule.getId(), rule.getRuleName());
            }
        }
        dto.setContent(content);
        if (content != null && !content.isBlank()) {
            boolean anyMetaInDb = dto.getRuleCode() != null || dto.getRuleType() != null
                    || (dto.getSections() != null && !dto.getSections().isEmpty())
                    || (dto.getKeywords() != null && !dto.getKeywords().isEmpty());
            if (!anyMetaInDb) {
                RuleMetadata meta = RuleMetadata.parse(content, rule.getFileType());
                if (dto.getRuleCode() == null)   dto.setRuleCode(meta.getRuleCode());
                if (dto.getRuleType() == null)   dto.setRuleType(meta.getRuleType());
                if (dto.getDocumentType() == null) dto.setDocumentType(meta.getDocumentType());
                if (dto.getSections() == null && meta.getSections() != null && !meta.getSections().isEmpty())
                    dto.setSections(meta.getSections());
                if (dto.getKeywords() == null && meta.getKeywords() != null && !meta.getKeywords().isEmpty())
                    dto.setKeywords(meta.getKeywords());
            }
        }
        dto.setChecks(sarRuleCheckMapper.findActiveByRuleId(rule.getId()).stream()
                .map(this::toCheckDTO)
                .toList());
        return dto;
    }

    private RuleCheckDTO toCheckDTO(SarRuleCheck check) {
        RuleCheckDTO dto = new RuleCheckDTO();
        dto.setId(check.getId());
        dto.setRuleId(check.getRuleId());
        dto.setCheckCode(check.getCheckCode());
        dto.setCheckType(check.getCheckType());
        dto.setQuestion(check.getQuestion());
        dto.setPassCriteria(check.getPassCriteria());
        dto.setCategory(check.getCategory());
        dto.setEvidenceRequired(check.getEvidenceRequired());
        dto.setDisplayOrder(check.getDisplayOrder());
        dto.setIsActive(check.getIsActive());
        return dto;
    }

    private String readContentFromDisk(String ruleName, String fileType) {
        if (ruleName == null || fileType == null) return null;
        try {
            Path dir = Path.of(rulesDir);
            if (!Files.exists(dir)) return null;
            String expectedSuffix = "_" + ruleName + "." + fileType;
            Optional<Path> match = Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(expectedSuffix))
                    .findFirst();
            if (match.isPresent()) {
                String content = Files.readString(match.get(), StandardCharsets.UTF_8);
                log.info("Read SAR rule content from disk: {}", match.get().getFileName());
                return content;
            }
        } catch (Exception e) {
            log.warn("Failed to read SAR rule content from disk for '{}': {}", ruleName, e.getMessage());
        }
        return null;
    }
}
