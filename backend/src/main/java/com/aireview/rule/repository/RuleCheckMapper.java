package com.aireview.rule.repository;

import com.aireview.rule.entity.RuleCheck;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RuleCheckMapper extends BaseMapper<RuleCheck> {

    @Select("SELECT * FROM rule_checks WHERE rule_id = #{ruleId} AND is_active = true ORDER BY display_order ASC, id ASC")
    List<RuleCheck> findActiveByRuleId(@Param("ruleId") Long ruleId);

    @Select("<script>"
            + "SELECT * FROM rule_checks WHERE is_active = true AND rule_id IN "
            + "<foreach collection='ruleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY rule_id ASC, display_order ASC, id ASC"
            + "</script>")
    List<RuleCheck> findActiveByRuleIds(@Param("ruleIds") List<Long> ruleIds);
}
