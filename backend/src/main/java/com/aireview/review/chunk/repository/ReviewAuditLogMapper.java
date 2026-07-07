package com.aireview.review.chunk.repository;

import com.aireview.review.chunk.entity.ReviewAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReviewAuditLogMapper extends BaseMapper<ReviewAuditLog> {

    @Select("SELECT * FROM review_audit_logs WHERE task_id = #{taskId} ORDER BY created_at ASC, id ASC")
    List<ReviewAuditLog> findByTaskId(@Param("taskId") String taskId);
}
