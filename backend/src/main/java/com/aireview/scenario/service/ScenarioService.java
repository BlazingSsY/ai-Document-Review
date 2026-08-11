package com.aireview.scenario.service;

import com.aireview.scenario.dto.ScenarioCreateRequest;
import com.aireview.scenario.dto.ScenarioDTO;
import com.aireview.common.dto.PageResponse;
import com.aireview.scenario.entity.Scenario;
import com.aireview.scenario.entity.ScenarioLibraryMapping;
import com.aireview.scenario.repository.ScenarioMapper;
import com.aireview.scenario.repository.ScenarioLibraryMappingMapper;
import com.aireview.user.entity.User;
import com.aireview.user.repository.UserMapper;
import com.aireview.user.repository.UserRuleAssignmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioService {

    private final ScenarioMapper scenarioMapper;
    private final ScenarioLibraryMappingMapper scenarioLibraryMappingMapper;
    private final UserMapper userMapper;
    private final UserRuleAssignmentMapper userRuleAssignmentMapper;

    @Transactional
    public ScenarioDTO createScenario(ScenarioCreateRequest request, Long creatorId) {
        List<Long> libraryIds = request.getLibraryIds() == null ? List.of() : request.getLibraryIds();
        requireLibrariesAssigned(creatorId, libraryIds);
        Scenario scenario = new Scenario();
        scenario.setName(request.getName());
        scenario.setDescription(request.getDescription());
        scenario.setCreatorId(creatorId);
        scenarioMapper.insert(scenario);

        if (!libraryIds.isEmpty()) {
            for (Long libId : libraryIds) {
                scenarioLibraryMappingMapper.insert(new ScenarioLibraryMapping(scenario.getId(), libId));
            }
        }

        log.info("Scenario created: {} with {} libraries by user {}",
                scenario.getName(), libraryIds.size(), creatorId);
        return toDTO(scenario, libraryIds);
    }

    public ScenarioDTO getScenarioById(Long id, Long userId) {
        Scenario scenario = scenarioMapper.selectById(id);
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario not found: " + id);
        }
        requireOwner(scenario, userId);
        List<Long> libraryIds = scenarioLibraryMappingMapper.findLibraryIdsByScenarioId(id);
        requireLibrariesAssigned(userId, libraryIds);
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
        requireOwner(scenario, userId);
        List<Long> libraryIds = request.getLibraryIds() == null ? List.of() : request.getLibraryIds();
        requireLibrariesAssigned(userId, libraryIds);

        scenario.setName(request.getName());
        scenario.setDescription(request.getDescription());
        scenarioMapper.updateById(scenario);

        scenarioLibraryMappingMapper.deleteByScenarioId(id);
        if (!libraryIds.isEmpty()) {
            for (Long libId : libraryIds) {
                scenarioLibraryMappingMapper.insert(new ScenarioLibraryMapping(id, libId));
            }
        }

        log.info("Scenario updated: {}", id);
        return toDTO(scenario, libraryIds);
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

    /** 发起或重审前重新检查，防止规则库撤权后旧场景继续绕过授权。 */
    public void validateScenarioForReview(Long scenarioId, Long userId) {
        Scenario scenario = scenarioMapper.selectById(scenarioId);
        if (scenario == null) throw new IllegalArgumentException("审查场景不存在");
        requireOwner(scenario, userId);
        requireLibrariesAssigned(userId,
                scenarioLibraryMappingMapper.findLibraryIdsByScenarioId(scenarioId));
    }

    private void requireOwner(Scenario scenario, Long userId) {
        if (!scenario.getCreatorId().equals(userId)) {
            throw new IllegalArgumentException("只能使用自己创建的审查场景");
        }
    }

    private void requireLibrariesAssigned(Long userId, List<Long> libraryIds) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new IllegalArgumentException("用户不存在");
        if ("supervisor".equals(user.getRole())) return;
        HashSet<Long> assigned = new HashSet<>(userRuleAssignmentMapper.findLibraryIdsByUserId(userId));
        if (!assigned.containsAll(libraryIds)) {
            throw new IllegalArgumentException("场景包含当前账号未获授权的规则库，请重新配置场景");
        }
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
