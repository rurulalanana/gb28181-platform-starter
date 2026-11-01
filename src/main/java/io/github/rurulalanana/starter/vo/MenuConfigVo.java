package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 菜单树（{@code gb_menu} 全量行）。
 *
 * <p>{@code items} 里<b>目录(D)、菜单(M)、按钮(B) 混在一起</b>，靠
 * {@code parentKey} 自关联。前端两种用法：
 * <ul>
 *   <li>侧栏与路由：取 D/M 行，按 {@code parentKey} 分组；</li>
 *   <li>「菜单管理」页：三种行都要，按 {@code parentKey} 拼成三层树。</li>
 * </ul>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MenuConfigVo {

    private List<ItemVo> items;

    /** 一个菜单节点。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemVo {
        private String menuKey;
        private String parentKey;
        /** D 目录 / M 菜单 / B 按钮。 */
        private String menuType;
        private String title;
        private String icon;
        private Integer sortOrder;
        private Boolean hidden;
        /** 路由地址（M 行）。 */
        private String path;
        /** 组件路径（M 行），如 {@code views/UserView.vue}。 */
        private String component;
        /** 权限标识（M/B 行）。 */
        private String perms;
        /** 0 正常 / 1 停用。 */
        private Integer status;
    }
}
