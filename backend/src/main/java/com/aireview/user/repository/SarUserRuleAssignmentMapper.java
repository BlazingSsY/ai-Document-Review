package com.aireview.user.repository;

import com.aireview.user.entity.SarUserRuleAssignment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SarUserRuleAssignmentMapper extends BaseMapper<SarUserRuleAssignment> {

    @Select("SELECT library_id FROM sar_user_library_assignment WHERE user_id = #{userId}")
    List<Long> findLibraryIdsByUserId(Long userId);

    @Delete("DELETE FROM sar_user_library_assignment WHERE user_id = #{userId}")
    void deleteByUserId(Long userId);
}
