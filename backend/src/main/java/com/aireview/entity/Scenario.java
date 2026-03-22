package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("scenarios")
public class Scenario {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Long creatorId;
}
