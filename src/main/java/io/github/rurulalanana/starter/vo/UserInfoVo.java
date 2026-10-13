package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 当前登录用户信息。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserInfoVo {

    private Long id;

    /** 用户名。 */
    private String name;

    private String nickname;

    /** 角色列表。 */
    private List<String> roles;

    private String phoneNumber;
    private String email;
    private String description;

    /** 是否可见全部通道。 */
    private Boolean hasAllChannel;

    /** 当前 Token。 */
    private String token;

    /** 登录时间。 */
    private String loginAt;
}
