package com.aireview.repository;

import com.aireview.config.SimpleJsonbTypeHandler;
import com.aireview.entity.RagReviewTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RagReviewTaskMapper extends BaseMapper<RagReviewTask> {

    /**
     * 详情首屏专用：在 SQL 层用 JSONB 投影剥掉 originalSources / chunkResults，
     * 避免反序列化整条 ai_result。其它列自动驼峰映射，ai_result 走 JSONB 类型处理器。
     */
    @Results({
        @Result(property = "aiResult", column = "ai_result", typeHandler = SimpleJsonbTypeHandler.class)
    })
    @Select("SELECT id, user_id, file_name, file_path, scenario_id, selected_model, status, "
            + "(ai_result - 'originalSources' - 'chunkResults') AS ai_result, "
            + "created_at, updated_at, fail_reason, problem_count "
            + "FROM rag_review_tasks WHERE id = #{taskId}")
    RagReviewTask selectLightById(@Param("taskId") String taskId);

    /**
     * 溯源原文按需专用：只取 chunkResults（RAG 的 originalSources 由文件重建，仅需 file_path）。
     */
    @Results({
        @Result(property = "aiResult", column = "ai_result", typeHandler = SimpleJsonbTypeHandler.class)
    })
    @Select("SELECT id, user_id, file_path, "
            + "jsonb_build_object('chunkResults', ai_result->'chunkResults') AS ai_result "
            + "FROM rag_review_tasks WHERE id = #{taskId}")
    RagReviewTask selectSourcesById(@Param("taskId") String taskId);
}
