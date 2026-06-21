package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("sar_scenario_library_mapping")
public class SarScenarioLibraryMapping {

    private Long scenarioId;

    private Long libraryId;
}
