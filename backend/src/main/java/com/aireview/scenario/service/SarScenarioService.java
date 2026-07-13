package com.aireview.scenario.service;

import com.aireview.scenario.dto.ScenarioCreateRequest;
import com.aireview.scenario.dto.ScenarioDTO;
import com.aireview.common.dto.PageResponse;
import com.aireview.scenario.entity.SarScenario;
import com.aireview.scenario.entity.SarScenarioLibraryMapping;
import com.aireview.scenario.repository.SarScenarioMapper;
import com.aireview.scenario.repository.SarScenarioLibraryMappingMapper;
import com.aireview.user.repository.SarUserRuleAssignmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * SAR 侧场景服务。与 {@link ScenarioService} 结构对称，仅注入的 mapper 不同。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SarScenarioService {

    private final SarScenarioMapper sarScenarioMapper;
    private final SarScenarioLibraryMappingMapper sarScenarioLibraryMappingMapper;
    private final SarUserRuleAssignmentMapper sarUserRuleAssignmentMapper;

    public void requireReviewAccess(Long scenarioId, Long userId, String role) {
        if (scenarioId == null) {
            throw new IllegalArgumentException("SAR scenario is required");
        }
        SarScenario scenario = sarScenarioMapper.selectById(scenarioId);
        if (scenario == null) {
            throw new IllegalArgumentException("SAR scenario not found: " + scenarioId);
        }
        String normalizedRole = role == null ? "user" : role.trim().toLowerCase(Locale.ROOT);
        if ("admin".equals(normalizedRole) || scenario.getCreatorId().equals(userId)) return;

        List<Long> scenarioLibraryIds = sarScenarioLibraryMappingMapper.findLibraryIdsByScenarioId(scenarioId);
        List<Long> assignedLibraryIds = sarUserRuleAssignmentMapper.findLibraryIdsByUserId(userId);
        if (scenarioLibraryIds == null || scenarioLibraryIds.isEmpty()
                || assignedLibraryIds == null || !assignedLibraryIds.containsAll(scenarioLibraryIds)) {
            throw new IllegalArgumentException("You do not have access to all rule libraries in this SAR scenario");
        }
    }

    @Transactional
    public ScenarioDTO createScenario(ScenarioCreateRequest request, Long creatorId) {
        SarScenario scenario = new SarScenario();
        scenario.setName(request.getName());
        scenario.setDescription(request.getDescription());
        scenario.setCreatorId(creatorId);
        sarScenarioMapper.insert(scenario);

        if (request.getLibraryIds() != null) {
            for (Long libId : request.getLibraryIds()) {
                sarScenarioLibraryMappingMapper.insert(new SarScenarioLibraryMapping(scenario.getId(), libId));
            }
        }

        log.info("SAR scenario created: {} with {} libraries by user {}",
                scenario.getName(),
                request.getLibraryIds() == null ? 0 : request.getLibraryIds().size(),
                creatorId);
        return toDTO(scenario, request.getLibraryIds());
    }

    public ScenarioDTO getScenarioById(Long id) {
        SarScenario scenario = sarScenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("SAR scenario not found: " + id);
        }
        List<Long> libraryIds = sarScenarioLibraryMappingMapper.findLibraryIdsByScenarioId(id);
        return toDTO(scenario, libraryIds);
    }

    public PageResponse<ScenarioDTO> listScenarios(int page, int size, Long creatorId) {
        Page<SarScenario> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<SarScenario> query = new LambdaQueryWrapper<>();
        if (creatorId != null) {
            query.eq(SarScenario::getCreatorId, creatorId);
        }

        Page<SarScenario> result = sarScenarioMapper.selectPage(pageParam, query);
        List<ScenarioDTO> records = result.getRecords().stream().map(s -> {
            List<Long> libraryIds = sarScenarioLibraryMappingMapper.findLibraryIdsByScenarioId(s.getId());
            return toDTO(s, libraryIds);
        }).toList();

        return PageResponse.of(records, result.getTotal(), page, size);
    }

    @Transactional
    public ScenarioDTO updateScenario(Long id, ScenarioCreateRequest request, Long userId) {
        SarScenario scenario = sarScenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("SAR scenario not found: " + id);
        }
        if (!scenario.getCreatorId().equals(userId)) {
            throw new IllegalArgumentException("You can only update your own scenarios");
        }

        scenario.setName(request.getName());
        scenario.setDescription(request.getDescription());
        sarScenarioMapper.updateById(scenario);

        sarScenarioLibraryMappingMapper.deleteByScenarioId(id);
        if (request.getLibraryIds() != null) {
            for (Long libId : request.getLibraryIds()) {
                sarScenarioLibraryMappingMapper.insert(new SarScenarioLibraryMapping(id, libId));
            }
        }

        log.info("SAR scenario updated: {}", id);
        return toDTO(scenario, request.getLibraryIds());
    }

    @Transactional
    public void deleteScenario(Long id, Long userId) {
        SarScenario scenario = sarScenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("SAR scenario not found: " + id);
        }
        if (!scenario.getCreatorId().equals(userId)) {
            throw new IllegalArgumentException("You can only delete your own scenarios");
        }

        sarScenarioLibraryMappingMapper.deleteByScenarioId(id);
        sarScenarioMapper.deleteById(id);
        log.info("SAR scenario deleted: {}", id);
    }

    private ScenarioDTO toDTO(SarScenario scenario, List<Long> libraryIds) {
        ScenarioDTO dto = new ScenarioDTO();
        dto.setId(scenario.getId());
        dto.setName(scenario.getName());
        dto.setDescription(scenario.getDescription());
        dto.setCreatorId(scenario.getCreatorId());
        dto.setLibraryIds(libraryIds);
        return dto;
    }
}
