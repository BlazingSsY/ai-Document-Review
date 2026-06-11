package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("rag_scenario_rule_mapping")
public class RagScenarioRuleMapping {

    private Long scenarioId;

    private Long ruleId;
}
