package com.aireview.repository;

import com.aireview.entity.Rule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RuleMapper extends BaseMapper<Rule> {

    @Select("SELECT r.* FROM rules r INNER JOIN scenario_rule_mapping srm ON r.id = srm.rule_id WHERE srm.scenario_id = #{scenarioId} AND r.is_valid = true")
    List<Rule> findByScenarioId(Long scenarioId);
}
