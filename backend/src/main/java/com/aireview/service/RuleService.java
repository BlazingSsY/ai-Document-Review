package com.aireview.service;

import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleDTO;
import com.aireview.entity.Rule;
import com.aireview.repository.RuleMapper;
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
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleService {

    private final RuleMapper ruleMapper;
    private final com.aireview.repository.UserRuleAssignmentMapper userRuleAssignmentMapper;

    @Value("${file.rules-dir}")
    private String rulesDir;

    /**
     * Upload and parse a rule file (.md or .json).
     *
     * @param file      the uploaded rule file
     * @param creatorId the user ID of the uploader
     * @return the created rule DTO
     */
    public RuleDTO uploadRule(MultipartFile file, Long creatorId, Long libraryId) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("File name is required");
        }

        String fileType = RuleParser.detectFileType(originalFilename);
        if ("unknown".equals(fileType)) {
            throw new IllegalArgumentException("Unsupported rule file format. Only .md and .json are supported.");
        }

        // Read file content
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);

        // Validate rule content
        List<String> errors = RuleParser.validate(content, fileType);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Rule validation failed: " + String.join("; ", errors));
        }

        // Save file to disk
        Path uploadDir = Path.of(rulesDir);
        Files.createDirectories(uploadDir);
        String savedFileName = UUID.randomUUID() + "_" + originalFilename;
        Path savedPath = uploadDir.resolve(savedFileName);
        Files.write(savedPath, file.getBytes());

        // Parse and store rule content
        String parsedContent = RuleParser.parseContent(content, fileType);

        Rule rule = new Rule();
        rule.setRuleName(originalFilename.replaceAll("\\.[^.]+$", ""));
        rule.setFileType(fileType);
        rule.setContent(parsedContent);
        rule.setCreatorId(creatorId);
        rule.setLibraryId(libraryId);
        rule.setUpdatedAt(LocalDateTime.now());
        rule.setIsValid(true);
        ruleMapper.insert(rule);

        log.info("Rule uploaded: {} by user {}", originalFilename, creatorId);
        return toDTO(rule);
    }

    /**
     * List rules with pagination, role-based filtering.
     * supervisor/admin: see all rules.
     * user: see only assigned rules.
     */
    public PageResponse<RuleDTO> listRules(int page, int size, Long userId, String role, Long libraryId) {
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
        query.orderByDesc(Rule::getUpdatedAt);

        Page<Rule> result = ruleMapper.selectPage(pageParam, query);
        List<RuleDTO> records = result.getRecords().stream().map(this::toDTO).toList();

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
     * Get all rules associated with a scenario.
     */
    public List<Rule> getRulesByScenarioId(Long scenarioId) {
        return ruleMapper.findByScenarioId(scenarioId);
    }

    private RuleDTO toDTO(Rule rule) {
        RuleDTO dto = new RuleDTO();
        dto.setId(rule.getId());
        dto.setRuleName(rule.getRuleName());
        dto.setFileType(rule.getFileType());
        dto.setContent(rule.getContent());
        dto.setCreatorId(rule.getCreatorId());
        dto.setLibraryId(rule.getLibraryId());
        dto.setUpdatedAt(rule.getUpdatedAt());
        dto.setIsValid(rule.getIsValid());
        return dto;
    }
}
