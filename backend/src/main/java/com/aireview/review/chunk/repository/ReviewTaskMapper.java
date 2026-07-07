package com.aireview.review.chunk.repository;

import com.aireview.common.persistence.SimpleJsonbTypeHandler;
import com.aireview.review.chunk.entity.ReviewTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    /**
     * 详情首屏专用：在 SQL 层用 JSONB 投影把最大的 originalSources / chunkResults 两个字段剥掉，
     * 避免把整条 ~2MB 的 ai_result 读出并反序列化（实测全量物化 ~3s，投影后 ~0.5s）。
     * 其它列由 MyBatis-Plus 按驼峰自动映射，ai_result 仍走 JSONB 类型处理器。
     */
    @Results({
        @Result(property = "aiResult", column = "ai_result", typeHandler = SimpleJsonbTypeHandler.class)
    })
    @Select("SELECT id, user_id, file_name, file_path, scenario_id, selected_model, status, "
            + "(ai_result - 'originalSources' - 'chunkResults') AS ai_result, "
            + "created_at, updated_at, fail_reason, problem_count "
            + "FROM review_tasks WHERE id = #{taskId}")
    ReviewTask selectLightById(@Param("taskId") String taskId);

    /**
     * 溯源原文按需专用：只取 originalSources / chunkResults 两个大字段，不反序列化其它内容。
     */
    @Results({
        @Result(property = "aiResult", column = "ai_result", typeHandler = SimpleJsonbTypeHandler.class)
    })
    @Select("SELECT id, user_id, file_path, "
            + "jsonb_build_object('originalSources', ai_result->'originalSources', "
            + "'chunkResults', ai_result->'chunkResults') AS ai_result "
            + "FROM review_tasks WHERE id = #{taskId}")
    ReviewTask selectSourcesById(@Param("taskId") String taskId);
}
