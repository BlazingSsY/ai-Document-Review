package com.aireview.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单位（成员的归属组织）。
 *
 * <p>做成独立表而不是 users 上的一个字符串字段：单位名靠人工填写时，「一所」「一 所」
 * 「第一研究所」很容易写成几种，看板按单位统计就会裂成好几条，改名还要批量更新每一行。
 * 有独立记录后，成员引用 id，改名只动一处，Excel 导入遇到新单位也能自动建档。
 */
@Data
@TableName("units")
public class Unit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 单位编号/简称，可空，便于与既有台账对齐。 */
    private String code;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
