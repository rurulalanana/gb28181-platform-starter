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

    /**
     * 权限点清单（角色展开后的并集）。
     *
     * <p>前端用它决定「哪些按钮画、哪些菜单出现」，<b>不再猜角色名</b>。
     * 拥有 {@code *} 的角色（admin）在这里是全部编码，
     * 所以前端只需 {@code permissions.includes('device:write')}。
     *
     * <p>注意这只是<b>界面显隐依据</b>，不是安全边界 ——
     * 真正的拦截在后端 {@code WritePermissionInterceptor}，
     * 前端隐藏按钮只是为了不让人点了才看到 403。
     */
    private List<String> permissions;

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
