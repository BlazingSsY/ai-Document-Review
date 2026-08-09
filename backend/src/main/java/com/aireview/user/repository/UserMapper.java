package com.aireview.user.repository;

import com.aireview.user.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM users WHERE email = #{email}")
    User findByEmail(String email);

    /** 成员登录路径：单位内用户名唯一，(unitId, username) 即可定位到唯一账号。 */
    @Select("SELECT * FROM users WHERE unit_id = #{unitId} AND username = #{username}")
    User findByUnitAndUsername(@Param("unitId") Long unitId, @Param("username") String username);

    /** 身份证号查重。仅后端内部使用，结果不直接对外返回。 */
    @Select("SELECT * FROM users WHERE id_card = #{idCard}")
    User findByIdCard(@Param("idCard") String idCard);

    @Select("SELECT COUNT(*) FROM users WHERE unit_id = #{unitId}")
    long countByUnit(@Param("unitId") Long unitId);
}
