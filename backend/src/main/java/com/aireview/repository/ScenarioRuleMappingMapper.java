package com.aireview.repository;

import com.aireview.entity.ScenarioRuleMapping;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ScenarioRuleMappingMapper extends BaseMapper<ScenarioRuleMapping> {

    @Delete("DELETE FROM scenario_rule_mapping WHERE scenario_id = #{scenarioId}")
    int deleteByScenarioId(Long scenarioId);

    @Select("SELECT rule_id FROM scenario_rule_mapping WHERE scenario_id = #{scenarioId}")
    List<Long> findRuleIdsByScenarioId(Long scenarioId);
}
