package com.aireview.scenario.service;

import com.aireview.scenario.entity.SarScenario;
import com.aireview.scenario.repository.SarScenarioLibraryMappingMapper;
import com.aireview.scenario.repository.SarScenarioMapper;
import com.aireview.user.repository.SarUserRuleAssignmentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SarScenarioServiceTest {

    @Mock private SarScenarioMapper scenarioMapper;
    @Mock private SarScenarioLibraryMappingMapper scenarioLibraryMappingMapper;
    @Mock private SarUserRuleAssignmentMapper userRuleAssignmentMapper;

    @InjectMocks private SarScenarioService service;

    @Test
    void assignedUserCanUseScenarioOnlyWhenAllLibrariesAreAssigned() {
        when(scenarioMapper.selectById(10L)).thenReturn(scenario(10L, 99L));
        when(scenarioLibraryMappingMapper.findLibraryIdsByScenarioId(10L))
                .thenReturn(List.of(1L, 2L));
        when(userRuleAssignmentMapper.findLibraryIdsByUserId(7L))
                .thenReturn(List.of(1L, 2L, 3L));

        assertThatCode(() -> service.requireReviewAccess(10L, 7L, "user"))
                .doesNotThrowAnyException();
    }

    @Test
    void assignedUserCannotUseScenarioWithUnassignedLibrary() {
        when(scenarioMapper.selectById(10L)).thenReturn(scenario(10L, 99L));
        when(scenarioLibraryMappingMapper.findLibraryIdsByScenarioId(10L))
                .thenReturn(List.of(1L, 2L));
        when(userRuleAssignmentMapper.findLibraryIdsByUserId(7L))
                .thenReturn(List.of(1L));

        assertThatThrownBy(() -> service.requireReviewAccess(10L, 7L, "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all rule libraries");
    }

    @Test
    void creatorAndAdminCanUseExistingScenario() {
        when(scenarioMapper.selectById(10L)).thenReturn(scenario(10L, 7L));
        assertThatCode(() -> service.requireReviewAccess(10L, 7L, "user"))
                .doesNotThrowAnyException();

        when(scenarioMapper.selectById(11L)).thenReturn(scenario(11L, 99L));
        assertThatCode(() -> service.requireReviewAccess(11L, 7L, "admin"))
                .doesNotThrowAnyException();
    }

    private SarScenario scenario(Long id, Long creatorId) {
        SarScenario scenario = new SarScenario();
        scenario.setId(id);
        scenario.setCreatorId(creatorId);
        return scenario;
    }
}
