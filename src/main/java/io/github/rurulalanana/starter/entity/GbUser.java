package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 平台用户。
 *
 * <p>对应表 {@code gb_user}。
 */
@Data
@TableName("gb_user")
public class GbUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 密文。 */
    private String password;

    private String nickname;

    /** 角色，多角色以逗号分隔。 */
    private String role;

    private String phoneNumber;
    private String email;
    private String description;

    private Boolean enable;

    /** 是否关联所有通道。 */
    private Boolean hasAllChannel;

    private String creator;

    /** 是否因多次登录失败被锁定。 */
    private Boolean lock;

    private LocalDateTime lastLoginAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
