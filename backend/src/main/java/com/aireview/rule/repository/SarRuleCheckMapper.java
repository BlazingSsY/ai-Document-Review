package com.aireview.rule.repository;

import com.aireview.rule.entity.SarRuleCheck;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SarRuleCheckMapper extends BaseMapper<SarRuleCheck> {

    @Select("SELECT * FROM sar_rule_checks WHERE rule_id = #{ruleId} AND is_active = true ORDER BY display_order ASC, id ASC")
    List<SarRuleCheck> findActiveByRuleId(@Param("ruleId") Long ruleId);

    @Select("<script>"
            + "SELECT * FROM sar_rule_checks WHERE is_active = true AND rule_id IN "
            + "<foreach collection='ruleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY rule_id ASC, display_order ASC, id ASC"
            + "</script>")
    List<SarRuleCheck> findActiveByRuleIds(@Param("ruleIds") List<Long> ruleIds);
}
