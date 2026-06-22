package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleCheckDTO;
import com.aireview.dto.RuleContentUpdateRequest;
import com.aireview.dto.RuleDTO;
import com.aireview.dto.RuleMetadataUpdateRequest;
import com.aireview.dto.RuleUploadConflictDTO;
import com.aireview.entity.Rule;
import com.aireview.entity.RuleCheck;
import com.aireview.entity.RuleFolder;
import com.aireview.repository.RuleCheckMapper;
import com.aireview.repository.RuleFolderMapper;
import com.aireview.repository.RuleMapper;
import com.aireview.util.MultiRuleParser;
import com.aireview.util.RuleMetadata;
import com.aireview.util.RuleParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleService {

    private final RuleMapper ruleMapper;
    private final RuleCheckMapper ruleCheckMapper;
    private final RuleFolderMapper ruleFolderMapper;
    private final com.aireview.repository.UserRuleAssignmentMapper userRuleAssignmentMapper;

    @Value("${file.rules-dir}")
    private String rulesDir;

    /**
     * Upload and parse a rule file (.md or .json).
     *
     * Behaviour:
     *   - For .md files and single-rule .json files, exactly one {@link Rule} row is created.
     *   - For .json files containing a multi-rule container (e.g. prompts.json's
     *     {@code section_prompts}), one row per sub-rule is created. The uploaded file is
     *     still saved to disk once.
     *
     * The returned list always contains at least one entry. Callers can use
     * {@link #uploadRule(MultipartFile, Long, Long)} for the single-rule legacy signature.
     */
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
            // Persist original file once to disk (handy for debugging / re-export).
            Path uploadDir = Path.of(rulesDir);
            Files.createDirectories(uploadDir);
            String savedFileName = UUID.randomUUID() + "_" + originalFilename;
            Path savedPath = uploadDir.resolve(savedFileName);
            Files.writeString(savedPath, content, StandardCharsets.UTF_8);
        }

        // Split into one-or-more parsed rules.
        List<MultiRuleParser.ParsedRule> parsed = MultiRuleParser.parse(originalFilename, fileType, content);
        handleUploadConflicts(originalFilename, libraryId, folderId, replaceExisting);
        List<RuleDTO> out = new ArrayList<>();
        for (MultiRuleParser.ParsedRule pr : parsed) {
            Rule rule = new Rule();
            rule.setRuleName(pr.getName());
            rule.setFileType(pr.getFileType());
            rule.setContent(pr.getContent());
            rule.setCreatorId(creatorId);
            rule.setLibraryId(libraryId);
            rule.setFolderId(folderId);
            rule.setUpdatedAt(LocalDateTime.now());
            rule.setIsValid(true);
            rule.setSourceFile(originalFilename);
            RuleMetadata meta = pr.getMetadata();
            if (meta != null) {
                rule.setRuleCode(meta.getRuleCode());
                rule.setRuleType(meta.getRuleType());
                rule.setDocumentType(meta.getDocumentType());
                rule.setSections(emptyToNull(meta.getSections()));
                rule.setKeywords(emptyToNull(meta.getKeywords()));
            }
            rule.setDescription(pr.getDescription());
            ruleMapper.insert(rule);
            persistChecks(rule.getId(), pr.getChecks());
            out.add(toDTO(rule));
        }
        log.info("Rule file '{}' expanded into {} rule row(s) (creator={}, library={})",
                originalFilename, out.size(), creatorId, libraryId);
        return out;
    }

    public List<RuleUploadConflictDTO> findUploadConflicts(String sourceFile, Long libraryId, Long folderId) {
        if (sourceFile == null || sourceFile.isBlank()) return List.of();
        LambdaQueryWrapper<Rule> query = new LambdaQueryWrapper<>();
        query.eq(Rule::getSourceFile, sourceFile)
                .select(Rule::getId, Rule::getRuleName, Rule::getRuleCode, Rule::getSourceFile,
                        Rule::getLibraryId, Rule::getFolderId, Rule::getUpdatedAt)
                .orderByAsc(Rule::getId);
        if (libraryId == null) {
            query.isNull(Rule::getLibraryId);
        } else {
            query.eq(Rule::getLibraryId, libraryId);
        }
        if (folderId == null) {
            query.isNull(Rule::getFolderId);
        } else {
            query.eq(Rule::getFolderId, folderId);
        }
        return ruleMapper.selectList(query).stream()
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

    private void handleUploadConflicts(String sourceFile, Long libraryId, Long folderId, boolean replaceExisting) {
        List<RuleUploadConflictDTO> conflicts = findUploadConflicts(sourceFile, libraryId, folderId);
        if (conflicts.isEmpty()) return;
        if (!replaceExisting) {
            throw new IllegalArgumentException("规则文件已存在，请选择保留已有规则或用新文件替换");
        }
        for (RuleUploadConflictDTO conflict : conflicts) {
            ruleMapper.deleteById(conflict.getId());
        }
        log.info("Replaced {} existing rule(s) from sourceFile={} libraryId={} folderId={}",
                conflicts.size(), sourceFile, libraryId, folderId);
    }

    private void persistChecks(Long ruleId, List<MultiRuleParser.ParsedCheck> checks) {
        if (ruleId == null || checks == null || checks.isEmpty()) return;
        for (MultiRuleParser.ParsedCheck parsed : checks) {
            RuleCheck check = new RuleCheck();
            check.setRuleId(ruleId);
            check.setCheckCode(parsed.getCheckCode());
            check.setCheckType(parsed.getCheckType());
            check.setQuestion(parsed.getQuestion());
            check.setPassCriteria(parsed.getPassCriteria());
            check.setCategory(parsed.getCategory());
            check.setEvidenceRequired(parsed.getEvidenceRequired());
            check.setDisplayOrder(parsed.getDisplayOrder());
            check.setIsActive(true);
            ruleCheckMapper.insert(check);
        }
    }

    /** Back-compatible single-rule upload used by callers that don't yet expect multi-rule responses. */
    public RuleDTO uploadRule(MultipartFile file, Long creatorId, Long libraryId) throws IOException {
        List<RuleDTO> all = uploadRuleAll(file, creatorId, libraryId, null);
        return all.isEmpty() ? null : all.get(0);
    }

    private static <T> List<T> emptyToNull(List<T> list) {
        return list == null || list.isEmpty() ? null : list;
    }

    /**
     * List rules with pagination, role-based filtering.
     * supervisor/admin: see all rules.
     * user: see only assigned rules.
     */
    public PageResponse<RuleDTO> listRules(int page, int size, Long userId, String role, Long libraryId) {
        return listRules(page, size, userId, role, libraryId, null, false);
    }

    /**
     * @param folderId      仅返回该文件夹下的规则（为 null 时不按文件夹过滤，除非 uncategorized=true）
     * @param uncategorized 为 true 时只返回未分类规则（folder_id IS NULL），优先级高于 folderId
     */
    public PageResponse<RuleDTO> listRules(int page, int size, Long userId, String role,
                                           Long libraryId, Long folderId, boolean uncategorized) {
        Page<Rule> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Rule> query = new LambdaQueryWrapper<>();

        if ("user".equals(role)) {
            List<Long> assignedLibraryIds = userRuleAssignmentMapper.findLibraryIdsByUserId(userId);
            if (assignedLibraryIds.isEmpty()) {
                return PageResponse.of(List.of(), 0, page, size);
            }
            query.in(Rule::getLibraryId, assignedLibraryIds);
        }
        if (libraryId != null) {
            query.eq(Rule::getLibraryId, libraryId);
        }
        if (uncategorized) {
            query.isNull(Rule::getFolderId);
        } else if (folderId != null) {
            query.eq(Rule::getFolderId, folderId);
        }
        query.select(
                Rule::getId,
                Rule::getRuleName,
                Rule::getFileType,
                Rule::getCreatorId,
                Rule::getLibraryId,
                Rule::getFolderId,
                Rule::getUpdatedAt,
                Rule::getIsValid,
                Rule::getRuleCode,
                Rule::getRuleType,
                Rule::getDocumentType,
                Rule::getSections,
                Rule::getKeywords,
                Rule::getDescription,
                Rule::getSourceFile);
        query.orderByDesc(Rule::getUpdatedAt);

        Page<Rule> result = ruleMapper.selectPage(pageParam, query);
        // The list endpoint deliberately returns summaries only. Returning rule
        // bodies and every atomic check here caused large payloads, N+1 queries,
        // and long main-thread renders in the rule-library page.
        List<RuleDTO> records = result.getRecords().stream().map(this::toSummaryDTO).toList();

        return PageResponse.of(records, result.getTotal(), page, size);
    }

    /**
     * Get a rule by ID.
     */
    public RuleDTO getRuleById(Long id) {
        Rule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("Rule not found: " + id);
        }
        return toDTO(rule);
    }

    /**
     * Delete a rule by ID.
     */
    public void deleteRule(Long id) {
        Rule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("Rule not found: " + id);
        }
        ruleMapper.deleteById(id);
        log.info("Rule deleted: {}", id);
    }

    /**
     * Get all rules associated with a scenario.
     *
     * Loads IDs via the join query, then materialises full entities through
     * {@code selectBatchIds(...)} so MyBatis-Plus's {@code autoResultMap=true} kicks in
     * and the JSONB {@code sections}/{@code keywords} columns are deserialised via their
     * per-field typeHandlers. Without this two-step lookup, plain {@code @Select} SQL
     * returns null for those columns and the dispatcher mis-classifies every rule as global.
     */
    public List<Rule> getRulesByScenarioId(Long scenarioId) {
        List<Long> ids = ruleMapper.findIdsByScenarioId(scenarioId);
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        List<Rule> rules = ruleMapper.selectBatchIds(ids);
        // 停用文件夹中的规则在审查时整组排除；未分类（folder_id 为 null）恒保留。
        Set<Long> disabled = disabledFolderIds();
        if (disabled.isEmpty()) return rules;
        return rules.stream()
                .filter(r -> r.getFolderId() == null || !disabled.contains(r.getFolderId()))
                .collect(Collectors.toList());
    }

    private Set<Long> disabledFolderIds() {
        LambdaQueryWrapper<RuleFolder> q = new LambdaQueryWrapper<>();
        q.eq(RuleFolder::getEnabled, false).select(RuleFolder::getId);
        return ruleFolderMapper.selectList(q).stream()
                .map(RuleFolder::getId)
                .collect(Collectors.toSet());
    }

    /**
     * Update editable metadata on a rule. Null fields are ignored; an empty list explicitly
     * clears that field. The rule's {@code content} is never modified here.
     */
    public RuleDTO updateMetadata(Long id, RuleMetadataUpdateRequest req) {
        Rule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("Rule not found: " + id);
        }
        if (req.getRuleName() != null && !req.getRuleName().isBlank()) rule.setRuleName(req.getRuleName().trim());
        if (req.getRuleCode() != null)     rule.setRuleCode(blankToNull(req.getRuleCode()));
        if (req.getRuleType() != null)     rule.setRuleType(blankToNull(req.getRuleType()));
        if (req.getDocumentType() != null) rule.setDocumentType(blankToNull(req.getDocumentType()));
        if (req.getSections() != null)     rule.setSections(emptyToNull(req.getSections()));
        if (req.getKeywords() != null)     rule.setKeywords(emptyToNull(req.getKeywords()));
        if (req.getDescription() != null)  rule.setDescription(blankToNull(req.getDescription()));
        rule.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(rule);
        log.info("Rule {} metadata updated: code={}, type={}, sections={}, keywords={}",
                id, rule.getRuleCode(), rule.getRuleType(), rule.getSections(),
                rule.getKeywords());
        return toDTO(rule);
    }

    /**
     * 编辑规则内容：正文（content）与原子检查项（rule_checks）。content 为 null 不改；
     * checks 为 null 不改，非 null（含空列表）整体替换该规则的检查项。
     */
    public RuleDTO updateContent(Long id, RuleContentUpdateRequest req) {
        Rule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("Rule not found: " + id);
        }
        if (req.getContent() != null) {
            rule.setContent(req.getContent());
        }
        rule.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(rule);

        if (req.getChecks() != null) {
            ruleCheckMapper.delete(new LambdaQueryWrapper<RuleCheck>().eq(RuleCheck::getRuleId, id));
            int order = 1;
            for (RuleCheckDTO c : req.getChecks()) {
                RuleCheck check = new RuleCheck();
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
                ruleCheckMapper.insert(check);
                order++;
            }
        }
        log.info("Rule {} content updated (checks: {})", id,
                req.getChecks() == null ? "unchanged" : req.getChecks().size());
        return toDTO(rule);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private RuleDTO toSummaryDTO(Rule rule) {
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

        // Keep legacy metadata visible without sending the raw content to the UI.
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

    private RuleDTO toDTO(Rule rule) {
        RuleDTO dto = toSummaryDTO(rule);

        // Use DB content if present; otherwise fall back to reading the file from disk.
        // This handles rules that were uploaded before the raw-content storage fix.
        String content = rule.getContent();
        if (content == null || content.isBlank()) {
            content = readContentFromDisk(rule.getRuleName(), rule.getFileType());
            if (content != null && !content.isBlank()) {
                // Persist the recovered content so future reads are fast
                Rule update = new Rule();
                update.setId(rule.getId());
                update.setContent(content);
                update.setUpdatedAt(rule.getUpdatedAt()); // keep original updatedAt
                ruleMapper.updateById(update);
                log.info("Recovered and persisted content for rule id={} name='{}'",
                        rule.getId(), rule.getRuleName());
            }
        }
        dto.setContent(content);
        // Backfill from content frontmatter if DB columns are still empty (handles rules
        // uploaded before the columns existed). These backfills are NOT persisted here —
        // they only enrich the DTO so the listing UI can show something useful.
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
        dto.setChecks(ruleCheckMapper.findActiveByRuleId(rule.getId()).stream()
                .map(this::toCheckDTO)
                .toList());
        return dto;
    }

    private RuleCheckDTO toCheckDTO(RuleCheck check) {
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

    /**
     * Try to read rule content from the saved file on disk.
     * Files are saved as "{UUID}_{ruleName}.{fileType}" under rulesDir.
     */
    private String readContentFromDisk(String ruleName, String fileType) {
        if (ruleName == null || fileType == null) return null;
        try {
            Path dir = Path.of(rulesDir);
            if (!Files.exists(dir)) return null;

            // Saved filename ends with "_{ruleName}.{fileType}"
            String expectedSuffix = "_" + ruleName + "." + fileType;
            Optional<Path> match = Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(expectedSuffix))
                    .findFirst();

            if (match.isPresent()) {
                String content = Files.readString(match.get(), StandardCharsets.UTF_8);
                log.info("Read rule content from disk: {}", match.get().getFileName());
                return content;
            }
        } catch (Exception e) {
            log.warn("Failed to read rule content from disk for '{}': {}", ruleName, e.getMessage());
        }
        return null;
    }
}
