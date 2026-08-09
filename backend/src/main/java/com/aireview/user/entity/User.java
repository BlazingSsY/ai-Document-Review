package com.aireview.user.entity;

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

    /** 所属单位。存量账号（如 admin_root）无单位，为 null。 */
    private Long unitId;

    /** 登录名，取成员姓名；在单位内唯一，跨单位允许重名（登录时先选单位）。 */
    private String username;

    /**
     * 身份证号——成员的唯一编码，全平台不允许重复。
     *
     * <p>属于个人敏感信息：只在后端用于查重与导入校验，对外一律经 UserDTO 脱敏，
     * 明文不进日志、不出接口。
     */
    private String idCard;

    /** 首次登录（或管理员重置密码后）必须改密。 */
    private Boolean mustChangePassword;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
