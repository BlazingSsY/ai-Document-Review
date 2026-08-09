package com.aireview.user.repository;

import com.aireview.user.entity.Unit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UnitMapper extends BaseMapper<Unit> {

    @Select("SELECT * FROM units WHERE name = #{name}")
    Unit findByName(@Param("name") String name);

    @Select("SELECT * FROM units ORDER BY name")
    List<Unit> findAllOrdered();
}
