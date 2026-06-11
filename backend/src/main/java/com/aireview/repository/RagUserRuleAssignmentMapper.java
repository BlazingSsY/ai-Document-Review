package com.aireview.repository;

import com.aireview.entity.RagUserRuleAssignment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RagUserRuleAssignmentMapper extends BaseMapper<RagUserRuleAssignment> {

    @Select("SELECT library_id FROM rag_user_library_assignment WHERE user_id = #{userId}")
    List<Long> findLibraryIdsByUserId(Long userId);

    @Delete("DELETE FROM rag_user_library_assignment WHERE user_id = #{userId}")
    void deleteByUserId(Long userId);
}
