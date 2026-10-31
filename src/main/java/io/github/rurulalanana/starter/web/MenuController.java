package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.service.MenuService;
import io.github.rurulalanana.starter.vo.DeletedVo;
import io.github.rurulalanana.starter.vo.MenuConfigVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜单外观配置接口。
 *
 * <pre>
 * GET  /api/v1/menu/config    取全部覆盖值（侧栏渲染用，任何登录用户可读）
 * POST /api/v1/menu/save      保存一行覆盖（D 目录 / M 菜单项，同一张表）
 * POST /api/v1/menu/reset     恢复某一项/某个分组的默认外观
 * POST /api/v1/menu/resetall  清空全部覆盖
 * </pre>
 *
 * <p><b>{@code /config} 不要求额外权限</b>：每个登录用户渲染侧栏都要读它，
 * 而它只包含「菜单叫什么名字」这类无敏感信息。写接口要求
 * {@code system:write}，因为改的是全站导航。
 *
 * <p>本接口<b>不参与授权判断</b>：菜单项是否可见由前端 {@code meta.perm}
 * 决定，服务端不下发、也无法放宽权限。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /** 全部覆盖值。 */
    @GetMapping("/config")
    public ApiResponse<MenuConfigVo> config() {
        StpUtil.checkLogin();
        return ApiResponse.ok(menuService.config());
    }

    /**
     * 保存一行覆盖。
     *
     * <p>字段全部可选：不传 / 传空 = 不覆盖（用代码默认值）。
     * {@code menu_type=D} 时 {@code title} 必填。
     */
    @PostMapping("/save")
    public ApiResponse<Void> save(@RequestParam(value = "menu_key") String menuKey,
                                  @RequestParam(value = "parent_key", required = false) String parentKey,
                                  @RequestParam(value = "menu_type", required = false) String menuType,
                                  @RequestParam(required = false) String title,
                                  @RequestParam(required = false) String icon,
                                  @RequestParam(value = "sort_order", required = false) Integer sortOrder,
                                  @RequestParam(required = false) Boolean hidden) {
        StpUtil.checkPermission(Permissions.SYSTEM_WRITE);
        menuService.save(menuKey, parentKey, menuType, title, icon, sortOrder, hidden);
        return ApiResponse.ok();
    }

    /** 恢复默认：删掉该项的覆盖行（目录与菜单项同走这里）。 */
    @PostMapping("/reset")
    public ApiResponse<Void> reset(@RequestParam(value = "menu_key") String menuKey) {
        StpUtil.checkPermission(Permissions.SYSTEM_WRITE);
        if (menuKey == null || menuKey.isBlank()) {
            throw BizException.badRequest("menu_key 不能为空");
        }
        menuService.reset(menuKey);
        return ApiResponse.ok();
    }

    /** 清空全部覆盖。 */
    @PostMapping("/resetall")
    public ApiResponse<DeletedVo> resetAll() {
        StpUtil.checkPermission(Permissions.SYSTEM_WRITE);
        return ApiResponse.ok(DeletedVo.of(menuService.resetAll()));
    }
}
