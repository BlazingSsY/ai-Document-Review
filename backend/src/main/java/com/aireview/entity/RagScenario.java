package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("rag_scenarios")
public class RagScenario {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Long creatorId;
}
