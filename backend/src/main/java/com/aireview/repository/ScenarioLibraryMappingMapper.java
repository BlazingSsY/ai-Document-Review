package com.aireview.repository;

import com.aireview.entity.ScenarioLibraryMapping;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ScenarioLibraryMappingMapper extends BaseMapper<ScenarioLibraryMapping> {

    @Delete("DELETE FROM scenario_library_mapping WHERE scenario_id = #{scenarioId}")
    int deleteByScenarioId(Long scenarioId);

    @Select("SELECT library_id FROM scenario_library_mapping WHERE scenario_id = #{scenarioId}")
    List<Long> findLibraryIdsByScenarioId(Long scenarioId);
}
