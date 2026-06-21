package com.aireview.repository;

import com.aireview.entity.SarRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SarRuleMapper extends BaseMapper<SarRule> {

    @Select("SELECT r.id FROM sar_rules r "
            + "INNER JOIN sar_scenario_library_mapping slm ON r.library_id = slm.library_id "
            + "WHERE slm.scenario_id = #{scenarioId} AND r.is_valid = true")
    List<Long> findIdsByScenarioId(@Param("scenarioId") Long scenarioId);
}
