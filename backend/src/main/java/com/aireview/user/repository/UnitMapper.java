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

    /** 包含根节点本身的完整下级单位集合。 */
    @Select("""
            WITH RECURSIVE unit_tree AS (
                SELECT id FROM units WHERE id = #{rootId}
                UNION ALL
                SELECT child.id
                FROM units child
                JOIN unit_tree parent ON child.parent_id = parent.id
            )
            SELECT id FROM unit_tree
            """)
    List<Long> findSubtreeIds(@Param("rootId") Long rootId);

    @Select("SELECT COUNT(*) FROM units WHERE parent_id = #{unitId}")
    long countChildren(@Param("unitId") Long unitId);
}
