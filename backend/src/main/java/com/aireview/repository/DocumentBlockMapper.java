package com.aireview.repository;

import com.aireview.entity.DocumentBlock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentBlockMapper extends BaseMapper<DocumentBlock> {

    @Delete("DELETE FROM document_blocks WHERE task_id = #{taskId}")
    void deleteByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM document_blocks WHERE task_id = #{taskId} ORDER BY chapter_index ASC, block_index ASC, id ASC")
    List<DocumentBlock> findByTaskId(@Param("taskId") String taskId);
}
