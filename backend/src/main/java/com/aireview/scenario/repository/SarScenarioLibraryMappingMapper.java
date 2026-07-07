package com.aireview.scenario.repository;

import com.aireview.scenario.entity.SarScenarioLibraryMapping;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SarScenarioLibraryMappingMapper extends BaseMapper<SarScenarioLibraryMapping> {

    @Delete("DELETE FROM sar_scenario_library_mapping WHERE scenario_id = #{scenarioId}")
    int deleteByScenarioId(Long scenarioId);

    @Select("SELECT library_id FROM sar_scenario_library_mapping WHERE scenario_id = #{scenarioId}")
    List<Long> findLibraryIdsByScenarioId(Long scenarioId);
}
