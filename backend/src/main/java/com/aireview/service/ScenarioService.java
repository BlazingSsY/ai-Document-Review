package com.aireview.service;

import com.aireview.dto.ScenarioCreateRequest;
import com.aireview.dto.ScenarioDTO;
import com.aireview.dto.PageResponse;
import com.aireview.entity.Scenario;
import com.aireview.entity.ScenarioLibraryMapping;
import com.aireview.repository.ScenarioMapper;
import com.aireview.repository.ScenarioLibraryMappingMapper;
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
    private final ScenarioLibraryMappingMapper scenarioLibraryMappingMapper;

    @Transactional
    public ScenarioDTO createScenario(ScenarioCreateRequest request, Long creatorId) {
        Scenario scenario = new Scenario();
        scenario.setName(request.getName());
        scenario.setDescription(request.getDescription());
        scenario.setCreatorId(creatorId);
        scenarioMapper.insert(scenario);

        if (request.getLibraryIds() != null) {
            for (Long libId : request.getLibraryIds()) {
                scenarioLibraryMappingMapper.insert(new ScenarioLibraryMapping(scenario.getId(), libId));
            }
        }

        log.info("Scenario created: {} with {} libraries by user {}",
                scenario.getName(), request.getLibraryIds().size(), creatorId);
        return toDTO(scenario, request.getLibraryIds());
    }

    public ScenarioDTO getScenarioById(Long id) {
        Scenario scenario = scenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario not found: " + id);
        }
        List<Long> libraryIds = scenarioLibraryMappingMapper.findLibraryIdsByScenarioId(id);
        return toDTO(scenario, libraryIds);
    }

    public PageResponse<ScenarioDTO> listScenarios(int page, int size, Long creatorId) {
        Page<Scenario> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Scenario> query = new LambdaQueryWrapper<>();
        if (creatorId != null) {
            query.eq(Scenario::getCreatorId, creatorId);
        }

        Page<Scenario> result = scenarioMapper.selectPage(pageParam, query);
        List<ScenarioDTO> records = result.getRecords().stream().map(s -> {
            List<Long> libraryIds = scenarioLibraryMappingMapper.findLibraryIdsByScenarioId(s.getId());
            return toDTO(s, libraryIds);
        }).toList();

        return PageResponse.of(records, result.getTotal(), page, size);
    }

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

        scenarioLibraryMappingMapper.deleteByScenarioId(id);
        if (request.getLibraryIds() != null) {
            for (Long libId : request.getLibraryIds()) {
                scenarioLibraryMappingMapper.insert(new ScenarioLibraryMapping(id, libId));
            }
        }

        log.info("Scenario updated: {}", id);
        return toDTO(scenario, request.getLibraryIds());
    }

    @Transactional
    public void deleteScenario(Long id, Long userId) {
        Scenario scenario = scenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario not found: " + id);
        }
        if (!scenario.getCreatorId().equals(userId)) {
            throw new IllegalArgumentException("You can only delete your own scenarios");
        }

        scenarioLibraryMappingMapper.deleteByScenarioId(id);
        scenarioMapper.deleteById(id);
        log.info("Scenario deleted: {}", id);
    }

    private ScenarioDTO toDTO(Scenario scenario, List<Long> libraryIds) {
        ScenarioDTO dto = new ScenarioDTO();
        dto.setId(scenario.getId());
        dto.setName(scenario.getName());
        dto.setDescription(scenario.getDescription());
        dto.setCreatorId(scenario.getCreatorId());
        dto.setLibraryIds(libraryIds);
        return dto;
    }
}
