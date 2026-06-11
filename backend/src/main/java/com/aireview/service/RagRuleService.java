package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleCheckDTO;
import com.aireview.dto.RuleDTO;
import com.aireview.dto.RuleMetadataUpdateRequest;
import com.aireview.entity.RagRule;
import com.aireview.entity.RagRuleCheck;
import com.aireview.repository.RagRuleCheckMapper;
import com.aireview.repository.RagRuleMapper;
import com.aireview.repository.RagUserRuleAssignmentMapper;
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
import java.util.UUID;

/**
 * RAG 侧规则服务。与 {@link RuleService} 结构对称，不同之处：
 *
 * <ul>
 *   <li>写入 {@code rag_rules} / {@code rag_rule_checks} 而不是 chunk 表；</li>
 *   <li>认真持久化 rule_checks——这是 RAG 管线的核心数据（原子检查项）。</li>
 * </ul>
 *
 * 上传文件落盘到同一个 {@code rules-dir}，因为这只是源文件备份，与是哪条管线无关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRuleService {

    private final RagRuleMapper ragRuleMapper;
    private final RagRuleCheckMapper ragRuleCheckMapper;
    private final RagUserRuleAssignmentMapper ragUserRuleAssignmentMapper;

    @Value("${file.rules-dir}")
    private String rulesDir;

    public List<RuleDTO> uploadRuleAll(MultipartFile file, Long creatorId, Long libraryId) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("File name is required");
        }

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return importRuleContent(originalFilename, content, creatorId, libraryId, true);
    }

    public List<RuleDTO> importRuleContent(String originalFilename, String content,
                                           Long creatorId, Long libraryId,
                                           boolean persistSourceFile) throws IOException {
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
        List<RuleDTO> out = new ArrayList<>();
        for (MultiRuleParser.ParsedRule pr : parsed) {
            RagRule rule = new RagRule();
            rule.setRuleName(pr.getName());
            rule.setFileType(pr.getFileType());
            rule.setContent(pr.getContent());
            rule.setCreatorId(creatorId);
            rule.setLibraryId(libraryId);
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
            ragRuleMapper.insert(rule);
            persistChecks(rule.getId(), pr.getChecks());
            out.add(toDTO(rule));
        }
        log.info("RAG rule file '{}' expanded into {} rule row(s) (creator={}, library={})",
                originalFilename, out.size(), creatorId, libraryId);
        return out;
    }

    private void persistChecks(Long ruleId, List<MultiRuleParser.ParsedCheck> checks) {
        if (ruleId == null || checks == null || checks.isEmpty()) return;
        for (MultiRuleParser.ParsedCheck parsed : checks) {
            RagRuleCheck check = new RagRuleCheck();
            check.setRuleId(ruleId);
            check.setCheckCode(parsed.getCheckCode());
            check.setCheckType(parsed.getCheckType());
            check.setQuestion(parsed.getQuestion());
            check.setPassCriteria(parsed.getPassCriteria());
            check.setCategory(parsed.getCategory());
            check.setEvidenceRequired(parsed.getEvidenceRequired());
            check.setDisplayOrder(parsed.getDisplayOrder());
            check.setIsActive(true);
            ragRuleCheckMapper.insert(check);
        }
    }

    public RuleDTO uploadRule(MultipartFile file, Long creatorId, Long libraryId) throws IOException {
        List<RuleDTO> all = uploadRuleAll(file, creatorId, libraryId);
        return all.isEmpty() ? null : all.get(0);
    }

    private static <T> List<T> emptyToNull(List<T> list) {
        return list == null || list.isEmpty() ? null : list;
    }

    public PageResponse<RuleDTO> listRules(int page, int size, Long userId, String role, Long libraryId) {
        Page<RagRule> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<RagRule> query = new LambdaQueryWrapper<>();

        if ("user".equals(role)) {
            List<Long> assignedLibraryIds = ragUserRuleAssignmentMapper.findLibraryIdsByUserId(userId);
            if (assignedLibraryIds.isEmpty()) {
                return PageResponse.of(List.of(), 0, page, size);
            }
            query.in(RagRule::getLibraryId, assignedLibraryIds);
        }
        if (libraryId != null) {
            query.eq(RagRule::getLibraryId, libraryId);
        }
        query.select(
                RagRule::getId,
                RagRule::getRuleName,
                RagRule::getFileType,
                RagRule::getCreatorId,
                RagRule::getLibraryId,
                RagRule::getUpdatedAt,
                RagRule::getIsValid,
                RagRule::getRuleCode,
                RagRule::getRuleType,
                RagRule::getDocumentType,
                RagRule::getSections,
                RagRule::getKeywords,
                RagRule::getDescription,
                RagRule::getSourceFile);
        query.orderByDesc(RagRule::getUpdatedAt);

        Page<RagRule> result = ragRuleMapper.selectPage(pageParam, query);
        List<RuleDTO> records = result.getRecords().stream().map(this::toSummaryDTO).toList();

        return PageResponse.of(records, result.getTotal(), page, size);
    }

    public RuleDTO getRuleById(Long id) {
        RagRule rule = ragRuleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("RAG rule not found: " + id);
        }
        return toDTO(rule);
    }

    public void deleteRule(Long id) {
        RagRule rule = ragRuleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("RAG rule not found: " + id);
        }
        ragRuleMapper.deleteById(id);
        log.info("RAG rule deleted: {}", id);
    }

    public List<RagRule> getRulesByScenarioId(Long scenarioId) {
        List<Long> ids = ragRuleMapper.findIdsByScenarioId(scenarioId);
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        return ragRuleMapper.selectBatchIds(ids);
    }

    public RuleDTO updateMetadata(Long id, RuleMetadataUpdateRequest req) {
        RagRule rule = ragRuleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("RAG rule not found: " + id);
        }
        if (req.getRuleName() != null && !req.getRuleName().isBlank()) rule.setRuleName(req.getRuleName().trim());
        if (req.getRuleCode() != null)     rule.setRuleCode(blankToNull(req.getRuleCode()));
        if (req.getRuleType() != null)     rule.setRuleType(blankToNull(req.getRuleType()));
        if (req.getDocumentType() != null) rule.setDocumentType(blankToNull(req.getDocumentType()));
        if (req.getSections() != null)     rule.setSections(emptyToNull(req.getSections()));
        if (req.getKeywords() != null)     rule.setKeywords(emptyToNull(req.getKeywords()));
        if (req.getDescription() != null)  rule.setDescription(blankToNull(req.getDescription()));
        rule.setUpdatedAt(LocalDateTime.now());
        ragRuleMapper.updateById(rule);
        log.info("RAG rule {} metadata updated: code={}, type={}, sections={}, keywords={}",
                id, rule.getRuleCode(), rule.getRuleType(), rule.getSections(),
                rule.getKeywords());
        return toDTO(rule);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private RuleDTO toSummaryDTO(RagRule rule) {
        RuleDTO dto = new RuleDTO();
        dto.setId(rule.getId());
        dto.setRuleName(rule.getRuleName());
        dto.setFileType(rule.getFileType());
        dto.setCreatorId(rule.getCreatorId());
        dto.setLibraryId(rule.getLibraryId());
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

    private RuleDTO toDTO(RagRule rule) {
        RuleDTO dto = toSummaryDTO(rule);

        String content = rule.getContent();
        if (content == null || content.isBlank()) {
            content = readContentFromDisk(rule.getRuleName(), rule.getFileType());
            if (content != null && !content.isBlank()) {
                RagRule update = new RagRule();
                update.setId(rule.getId());
                update.setContent(content);
                update.setUpdatedAt(rule.getUpdatedAt());
                ragRuleMapper.updateById(update);
                log.info("Recovered and persisted content for RAG rule id={} name='{}'",
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
        dto.setChecks(ragRuleCheckMapper.findActiveByRuleId(rule.getId()).stream()
                .map(this::toCheckDTO)
                .toList());
        return dto;
    }

    private RuleCheckDTO toCheckDTO(RagRuleCheck check) {
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
                log.info("Read RAG rule content from disk: {}", match.get().getFileName());
                return content;
            }
        } catch (Exception e) {
            log.warn("Failed to read RAG rule content from disk for '{}': {}", ruleName, e.getMessage());
        }
        return null;
    }
}
