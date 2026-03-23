package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;

    private String passwordHash;

    private String name;

    private String role;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
