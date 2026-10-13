package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 用户信息。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserVo {

    private Long id;
    private String username;
    private String nickname;

    /** 角色（逗号分隔的原始值）。 */
    private String role;

    private Boolean enable;
    private String phoneNumber;
    private String email;
    private String description;
    private Boolean lock;
    private Boolean hasAllChannel;

    /** 最近登录时间。 */
    private String lastLoginAt;

    private String createdAt;
}
