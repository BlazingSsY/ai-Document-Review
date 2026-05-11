package com.aireview.repository;

import com.aireview.entity.Rule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RuleMapper extends BaseMapper<Rule> {

    /**
     * Fetch only the IDs of rules associated with the given scenario. Plain SELECT — no
     * entity columns are returned so MyBatis-Plus's auto result map (and the JSONB
     * typeHandlers configured on {@link Rule#sections} / {@link Rule#keywords}) is not
     * needed here.
     *
     * Callers should pass the returned IDs to {@code selectBatchIds(...)} so the entity
     * is loaded through {@code @TableName(autoResultMap=true)} — that's the only path
     * where MyBatis-Plus honours the per-field typeHandlers.
     */
    @Select("SELECT r.id FROM rules r "
            + "INNER JOIN scenario_library_mapping slm ON r.library_id = slm.library_id "
            + "WHERE slm.scenario_id = #{scenarioId} AND r.is_valid = true")
    List<Long> findIdsByScenarioId(@Param("scenarioId") Long scenarioId);
}
