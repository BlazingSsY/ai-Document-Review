package com.aireview.repository;

import com.aireview.entity.UserRuleAssignment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserRuleAssignmentMapper extends BaseMapper<UserRuleAssignment> {

    @Select("SELECT library_id FROM user_library_assignment WHERE user_id = #{userId}")
    List<Long> findLibraryIdsByUserId(Long userId);

    @Delete("DELETE FROM user_library_assignment WHERE user_id = #{userId}")
    void deleteByUserId(Long userId);
}
