package com.aireview.repository;

import com.aireview.entity.Rule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RuleMapper extends BaseMapper<Rule> {

    @Select("SELECT r.* FROM rules r INNER JOIN scenario_library_mapping slm ON r.library_id = slm.library_id WHERE slm.scenario_id = #{scenarioId} AND r.is_valid = true")
    List<Rule> findByScenarioId(Long scenarioId);
}
