package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 菜单覆盖配置。
 *
 * <p>{@code items} 里<b>目录与菜单项混在一起</b>（用 {@code menuType} 区分），
 * 与 {@code gb_menu} 一张表对应。前端按 {@code menuType} 分拣：
 * {@code D} 决定分组名与顺序，{@code M} 决定菜单项。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MenuConfigVo {

    private List<ItemVo> items;

    /** 一行覆盖（目录或菜单项）。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemVo {
        private String menuKey;
        private String parentKey;
        /** D 目录 / M 菜单。 */
        private String menuType;
        private String title;
        private String icon;
        private Integer sortOrder;
        private Boolean hidden;
    }
}
