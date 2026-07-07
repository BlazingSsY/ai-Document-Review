package com.aireview.rule.repository;

import com.aireview.rule.entity.Rule;
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

    /**
     * Resolve a rule id by its (manually-assigned) rule_code. Used to load the editable
     * built-in "基础文字质量审查" rule (rule_code = R-BASIC-QUALITY) regardless of which
     * scenario/library is selected, so its UI-edited preface and checks feed the always-on
     * injection in {@code ReviewService}. Returns the lowest id if duplicates exist; null
     * when no valid rule carries that code (caller falls back to the hard-coded default).
     */
    @Select("SELECT id FROM rules WHERE rule_code = #{ruleCode} AND is_valid = true "
            + "ORDER BY id ASC LIMIT 1")
    Long findIdByRuleCode(@Param("ruleCode") String ruleCode);
}
