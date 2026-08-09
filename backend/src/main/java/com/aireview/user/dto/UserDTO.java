package com.aireview.user.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserDTO {
    private Long id;
    private String email;
    private String name;
    private String role;
    private LocalDateTime createdAt;

    /** 所属单位 id / 名称。存量的平台账号无单位，两者均为 null。 */
    private Long unitId;
    private String unitName;

    /** 登录名（成员姓名），单位内唯一。 */
    private String username;

    /**
     * 脱敏后的身份证号，如 {@code 110101********1234}。
     *
     * <p>这里放脱敏值而不是明文：身份证号只在后端用于查重与导入校验，前端没有任何场景
     * 需要完整号码。查重在服务端用明文比对即可，明文因此不必离开后端，也不进日志。
     */
    private String idCardMasked;

    private Boolean mustChangePassword;
}
