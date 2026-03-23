package com.aireview.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

@Data
public class AssignRulesRequest {

    @NotNull(message = "规则ID列表不能为空")
    private List<Long> ruleIds;
}
