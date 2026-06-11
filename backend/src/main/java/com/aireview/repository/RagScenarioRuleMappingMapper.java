package com.aireview.repository;

import com.aireview.entity.RagScenarioRuleMapping;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RagScenarioRuleMappingMapper extends BaseMapper<RagScenarioRuleMapping> {

    @Delete("DELETE FROM rag_scenario_rule_mapping WHERE scenario_id = #{scenarioId}")
    int deleteByScenarioId(Long scenarioId);

    @Select("SELECT rule_id FROM rag_scenario_rule_mapping WHERE scenario_id = #{scenarioId}")
    List<Long> findRuleIdsByScenarioId(Long scenarioId);
}
