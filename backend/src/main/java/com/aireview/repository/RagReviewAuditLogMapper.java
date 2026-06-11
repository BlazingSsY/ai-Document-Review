package com.aireview.repository;

import com.aireview.entity.RagReviewAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RagReviewAuditLogMapper extends BaseMapper<RagReviewAuditLog> {

    @Select("SELECT * FROM rag_review_audit_logs WHERE task_id = #{taskId} ORDER BY created_at ASC, id ASC")
    List<RagReviewAuditLog> findByTaskId(@Param("taskId") String taskId);
}
