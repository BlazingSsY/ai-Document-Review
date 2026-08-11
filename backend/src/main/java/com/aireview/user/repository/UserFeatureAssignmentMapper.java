package com.aireview.user.repository;

import com.aireview.user.entity.UserFeatureAssignment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserFeatureAssignmentMapper extends BaseMapper<UserFeatureAssignment> {

    @Select("SELECT feature_code FROM user_feature_assignment WHERE user_id = #{userId} ORDER BY feature_code")
    List<String> findFeatureCodesByUserId(Long userId);

    @Select("SELECT COUNT(*) > 0 FROM user_feature_assignment WHERE user_id = #{userId} AND feature_code = #{featureCode}")
    boolean exists(@Param("userId") Long userId, @Param("featureCode") String featureCode);

    @Delete("DELETE FROM user_feature_assignment WHERE user_id = #{userId}")
    void deleteByUserId(Long userId);
}
