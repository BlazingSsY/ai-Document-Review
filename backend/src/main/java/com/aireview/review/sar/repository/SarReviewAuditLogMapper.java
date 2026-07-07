package com.aireview.review.sar.repository;

import com.aireview.review.sar.entity.SarReviewAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SarReviewAuditLogMapper extends BaseMapper<SarReviewAuditLog> {

    @Select("SELECT * FROM sar_review_audit_logs WHERE task_id = #{taskId} ORDER BY created_at ASC, id ASC")
    List<SarReviewAuditLog> findByTaskId(@Param("taskId") String taskId);
}
