package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("rag_scenario_library_mapping")
public class RagScenarioLibraryMapping {

    private Long scenarioId;

    private Long libraryId;
}
