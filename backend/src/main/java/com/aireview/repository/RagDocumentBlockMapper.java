package com.aireview.repository;

import com.aireview.entity.RagDocumentBlock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RagDocumentBlockMapper extends BaseMapper<RagDocumentBlock> {

    @Delete("DELETE FROM rag_document_blocks WHERE task_id = #{taskId}")
    void deleteByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM rag_document_blocks WHERE task_id = #{taskId} ORDER BY chapter_index ASC, block_index ASC, id ASC")
    List<RagDocumentBlock> findByTaskId(@Param("taskId") String taskId);
}
