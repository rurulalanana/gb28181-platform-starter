package io.github.rurulalanana.starter.dto;

import io.github.rurulalanana.starter.vo.MenuConfigVo;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * 菜单结构种子同步请求。
 *
 * <p>由前端在每次启动时提交：{@code version} 与库里记录的一致就直接返回，
 * 不一致才把缺失的目录/菜单行补写进 {@code gb_menu}。
 * 这样「代码里新增了一个页面」能自动出现在菜单里，而运维删掉的行又不会被每次重启种回来。
 *
 * <p>为什么由前端提交而不是后端内置：组件是构建产物（{@code views/Xxx.vue}），
 * 清单只有前端知道；后端内置一份就会出现「两边对不上」的漂移。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class MenuSyncRequest {

    /** 前端种子版本（routes.js 里改菜单时递增）。 */
    private String version;

    /** 目录与菜单行（不含按钮，按钮由后端按 Permissions 清单补齐）。 */
    private List<MenuConfigVo.ItemVo> items;
}
