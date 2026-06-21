package com.aireview.repository;

import com.aireview.entity.SarDocumentBlock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SarDocumentBlockMapper extends BaseMapper<SarDocumentBlock> {

    @Delete("DELETE FROM sar_document_blocks WHERE task_id = #{taskId}")
    void deleteByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM sar_document_blocks WHERE task_id = #{taskId} ORDER BY chapter_index ASC, block_index ASC, id ASC")
    List<SarDocumentBlock> findByTaskId(@Param("taskId") String taskId);
}
