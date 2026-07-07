package com.aireview.scenario.repository;

import com.aireview.scenario.entity.SarScenarioRuleMapping;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SarScenarioRuleMappingMapper extends BaseMapper<SarScenarioRuleMapping> {

    @Delete("DELETE FROM sar_scenario_rule_mapping WHERE scenario_id = #{scenarioId}")
    int deleteByScenarioId(Long scenarioId);

    @Select("SELECT rule_id FROM sar_scenario_rule_mapping WHERE scenario_id = #{scenarioId}")
    List<Long> findRuleIdsByScenarioId(Long scenarioId);
}
