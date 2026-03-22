package com.aireview.service;

import com.aireview.dto.ScenarioCreateRequest;
import com.aireview.dto.ScenarioDTO;
import com.aireview.dto.PageResponse;
import com.aireview.entity.Scenario;
import com.aireview.entity.ScenarioRuleMapping;
import com.aireview.repository.ScenarioMapper;
import com.aireview.repository.ScenarioRuleMappingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioService {

    private final ScenarioMapper scenarioMapper;
    private final ScenarioRuleMappingMapper scenarioRuleMappingMapper;

    /**
     * Create a new scenario with associated rules.
     */
    @Transactional
    public ScenarioDTO createScenario(ScenarioCreateRequest request, Long creatorId) {
        Scenario scenario = new Scenario();
        scenario.setName(request.getName());
        scenario.setDescription(request.getDescription());
        scenario.setCreatorId(creatorId);
        scenarioMapper.insert(scenario);

        // Create rule mappings
        if (request.getRuleIds() != null) {
            for (Long ruleId : request.getRuleIds()) {
                ScenarioRuleMapping mapping = new ScenarioRuleMapping(scenario.getId(), ruleId);
                scenarioRuleMappingMapper.insert(mapping);
            }
        }

        log.info("Scenario created: {} with {} rules by user {}",
                scenario.getName(), request.getRuleIds().size(), creatorId);
        return toDTO(scenario, request.getRuleIds());
    }

    /**
     * Get a scenario by ID with its associated rule IDs.
     */
    public ScenarioDTO getScenarioById(Long id) {
        Scenario scenario = scenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario not found: " + id);
        }
        List<Long> ruleIds = scenarioRuleMappingMapper.findRuleIdsByScenarioId(id);
        return toDTO(scenario, ruleIds);
    }

    /**
     * List scenarios with pagination.
     */
    public PageResponse<ScenarioDTO> listScenarios(int page, int size, Long creatorId) {
        Page<Scenario> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Scenario> query = new LambdaQueryWrapper<>();
        if (creatorId != null) {
            query.eq(Scenario::getCreatorId, creatorId);
        }

        Page<Scenario> result = scenarioMapper.selectPage(pageParam, query);
        List<ScenarioDTO> records = result.getRecords().stream().map(s -> {
            List<Long> ruleIds = scenarioRuleMappingMapper.findRuleIdsByScenarioId(s.getId());
            return toDTO(s, ruleIds);
        }).toList();

        return PageResponse.of(records, result.getTotal(), page, size);
    }

    /**
     * Update a scenario's basic info and rule associations.
     */
    @Transactional
    public ScenarioDTO updateScenario(Long id, ScenarioCreateRequest request, Long userId) {
        Scenario scenario = scenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario not found: " + id);
        }
        if (!scenario.getCreatorId().equals(userId)) {
            throw new IllegalArgumentException("You can only update your own scenarios");
        }

        scenario.setName(request.getName());
        scenario.setDescription(request.getDescription());
        scenarioMapper.updateById(scenario);

        // Re-create rule mappings
        scenarioRuleMappingMapper.deleteByScenarioId(id);
        if (request.getRuleIds() != null) {
            for (Long ruleId : request.getRuleIds()) {
                scenarioRuleMappingMapper.insert(new ScenarioRuleMapping(id, ruleId));
            }
        }

        log.info("Scenario updated: {}", id);
        return toDTO(scenario, request.getRuleIds());
    }

    /**
     * Delete a scenario and its rule mappings.
     */
    @Transactional
    public void deleteScenario(Long id, Long userId) {
        Scenario scenario = scenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario not found: " + id);
        }
        if (!scenario.getCreatorId().equals(userId)) {
            throw new IllegalArgumentException("You can only delete your own scenarios");
        }

        scenarioRuleMappingMapper.deleteByScenarioId(id);
        scenarioMapper.deleteById(id);
        log.info("Scenario deleted: {}", id);
    }

    private ScenarioDTO toDTO(Scenario scenario, List<Long> ruleIds) {
        ScenarioDTO dto = new ScenarioDTO();
        dto.setId(scenario.getId());
        dto.setName(scenario.getName());
        dto.setDescription(scenario.getDescription());
        dto.setCreatorId(scenario.getCreatorId());
        dto.setRuleIds(ruleIds);
        return dto;
    }
}
