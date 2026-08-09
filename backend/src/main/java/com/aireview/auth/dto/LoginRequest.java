package com.aireview.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    /**
     * 成员登录填用户名（姓名），存量平台账号填邮箱。
     *
     * <p>字段名保留 {@code email} 是为了不破坏既有前端与接口契约；实际语义已是
     * 「登录标识」，配合 {@link #unitId} 决定按哪条路径查账号。
     */
    @NotBlank(message = "用户名/邮箱不能为空")
    private String email;

    /**
     * 所属单位。成员登录必填——用户名只在单位内唯一，跨单位允许重名，没有单位就
     * 定位不到唯一账号。存量的平台账号（如 admin_root）不属于任何单位，留空即可，
     * 此时按邮箱查。
     */
    private Long unitId;

    @NotBlank(message = "密码不能为空")
    private String password;
}
