package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("scenario_rule_mapping")
public class ScenarioRuleMapping {

    private Long scenarioId;

    private Long ruleId;
}
