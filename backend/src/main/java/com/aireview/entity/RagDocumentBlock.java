package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rag_document_blocks")
public class RagDocumentBlock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private String blockId;

    private String blockType;

    private Integer chapterIndex;

    private Integer blockIndex;

    private String sectionPath;

    private String startNodeId;

    private String endNodeId;

    private String textContent;

    private String textHash;

    private String embeddingModel;

    /**
     * pgvector text representation used only by the JDBC vector repository.
     * The physical database column is named embedding and has type vector.
     */
    @TableField(exist = false)
    private String embeddingVector;

    private Integer embeddingDimension;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
