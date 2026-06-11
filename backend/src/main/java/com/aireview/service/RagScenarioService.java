package com.aireview.service;

import com.aireview.dto.ScenarioCreateRequest;
import com.aireview.dto.ScenarioDTO;
import com.aireview.dto.PageResponse;
import com.aireview.entity.RagScenario;
import com.aireview.entity.RagScenarioLibraryMapping;
import com.aireview.repository.RagScenarioMapper;
import com.aireview.repository.RagScenarioLibraryMappingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RAG 侧场景服务。与 {@link ScenarioService} 结构对称，仅注入的 mapper 不同。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagScenarioService {

    private final RagScenarioMapper ragScenarioMapper;
    private final RagScenarioLibraryMappingMapper ragScenarioLibraryMappingMapper;

    @Transactional
    public ScenarioDTO createScenario(ScenarioCreateRequest request, Long creatorId) {
        RagScenario scenario = new RagScenario();
        scenario.setName(request.getName());
        scenario.setDescription(request.getDescription());
        scenario.setCreatorId(creatorId);
        ragScenarioMapper.insert(scenario);

        if (request.getLibraryIds() != null) {
            for (Long libId : request.getLibraryIds()) {
                ragScenarioLibraryMappingMapper.insert(new RagScenarioLibraryMapping(scenario.getId(), libId));
            }
        }

        log.info("RAG scenario created: {} with {} libraries by user {}",
                scenario.getName(),
                request.getLibraryIds() == null ? 0 : request.getLibraryIds().size(),
                creatorId);
        return toDTO(scenario, request.getLibraryIds());
    }

    public ScenarioDTO getScenarioById(Long id) {
        RagScenario scenario = ragScenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("RAG scenario not found: " + id);
        }
        List<Long> libraryIds = ragScenarioLibraryMappingMapper.findLibraryIdsByScenarioId(id);
        return toDTO(scenario, libraryIds);
    }

    public PageResponse<ScenarioDTO> listScenarios(int page, int size, Long creatorId) {
        Page<RagScenario> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<RagScenario> query = new LambdaQueryWrapper<>();
        if (creatorId != null) {
            query.eq(RagScenario::getCreatorId, creatorId);
        }

        Page<RagScenario> result = ragScenarioMapper.selectPage(pageParam, query);
        List<ScenarioDTO> records = result.getRecords().stream().map(s -> {
            List<Long> libraryIds = ragScenarioLibraryMappingMapper.findLibraryIdsByScenarioId(s.getId());
            return toDTO(s, libraryIds);
        }).toList();

        return PageResponse.of(records, result.getTotal(), page, size);
    }

    @Transactional
    public ScenarioDTO updateScenario(Long id, ScenarioCreateRequest request, Long userId) {
        RagScenario scenario = ragScenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("RAG scenario not found: " + id);
        }
        if (!scenario.getCreatorId().equals(userId)) {
            throw new IllegalArgumentException("You can only update your own scenarios");
        }

        scenario.setName(request.getName());
        scenario.setDescription(request.getDescription());
        ragScenarioMapper.updateById(scenario);

        ragScenarioLibraryMappingMapper.deleteByScenarioId(id);
        if (request.getLibraryIds() != null) {
            for (Long libId : request.getLibraryIds()) {
                ragScenarioLibraryMappingMapper.insert(new RagScenarioLibraryMapping(id, libId));
            }
        }

        log.info("RAG scenario updated: {}", id);
        return toDTO(scenario, request.getLibraryIds());
    }

    @Transactional
    public void deleteScenario(Long id, Long userId) {
        RagScenario scenario = ragScenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("RAG scenario not found: " + id);
        }
        if (!scenario.getCreatorId().equals(userId)) {
            throw new IllegalArgumentException("You can only delete your own scenarios");
        }

        ragScenarioLibraryMappingMapper.deleteByScenarioId(id);
        ragScenarioMapper.deleteById(id);
        log.info("RAG scenario deleted: {}", id);
    }

    private ScenarioDTO toDTO(RagScenario scenario, List<Long> libraryIds) {
        ScenarioDTO dto = new ScenarioDTO();
        dto.setId(scenario.getId());
        dto.setName(scenario.getName());
        dto.setDescription(scenario.getDescription());
        dto.setCreatorId(scenario.getCreatorId());
        dto.setLibraryIds(libraryIds);
        return dto;
    }
}
