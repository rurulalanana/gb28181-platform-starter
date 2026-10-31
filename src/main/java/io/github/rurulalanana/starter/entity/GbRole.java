package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色。
 *
 * <p>对应表 {@code gb_role}；权限点单独存 {@code gb_role_permission}。
 *
 * <p>{@code code} 是主键（业务编码），不是自增 ID —— 因为
 * {@code gb_user.role} 里存的就是逗号分隔的编码，用编码直接关联可以省掉
 * 一层「编码 → ID → 权限」的翻译，登录时也不会因为角色被改名而失联。
 */
@Data
@TableName("gb_role")
public class GbRole implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色编码（小写，如 admin / operator / patrol）。 */
    @TableId
    private String code;

    private String name;

    private String description;

    /**
     * 是否内置角色。
     *
     * <p>内置角色不可删除；{@code admin} 还不可编辑权限
     * （见 {@code RoleService#save}），避免把最后一个管理入口改没。
     */
    private Boolean builtin;

    /** 是否启用。停用后该角色不再贡献任何权限，但用户数据保留。 */
    private Boolean enable;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
