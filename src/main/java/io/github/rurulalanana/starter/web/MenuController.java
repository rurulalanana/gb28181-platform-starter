package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.dto.MenuSaveRequest;
import io.github.rurulalanana.starter.dto.MenuSyncRequest;
import io.github.rurulalanana.starter.service.MenuService;
import io.github.rurulalanana.starter.vo.DeletedVo;
import io.github.rurulalanana.starter.vo.IdVo;
import io.github.rurulalanana.starter.vo.MenuConfigVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜单管理接口。
 *
 * <pre>
 * GET  /api/v1/menu/config   全部菜单节点（目录/菜单/按钮混排，任何登录用户可读）
 * POST /api/v1/menu/sync     补写缺失的目录/菜单行（前端启动时提交种子，版本一致即空操作）
 * POST /api/v1/menu/save     新增/修改一个节点
 * POST /api/v1/menu/delete   删除一个节点（cascade=true 连子节点）
 * </pre>
 *
 * <p><b>{@code /config} 不要求额外权限</b>：每个登录用户渲染侧栏、注册路由都要读它，
 * 而它只包含「菜单叫什么、指向哪个页面」。写接口要求 {@code menu:write}。
 *
 * <p><b>{@code /sync} 也开放给所有登录用户</b>：它是幂等的补齐动作，
 * 只在种子版本变化时生效一次（版本记在 {@code gb_menu_seed}）。若要求管理权限，
 * 普通账号首次打开页面就会因为「菜单还没种」而看到空白侧栏。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /** 全部菜单节点。 */
    @GetMapping("/config")
    public ApiResponse<MenuConfigVo> config() {
        StpUtil.checkLogin();
        return ApiResponse.ok(menuService.config());
    }

    /** 补齐缺失的目录/菜单行 + 按钮行。 */
    @PostMapping("/sync")
    public ApiResponse<DeletedVo> sync(@RequestBody MenuSyncRequest req) {
        StpUtil.checkLogin();
        return ApiResponse.ok(DeletedVo.of(menuService.sync(req)));
    }

    /** 新增或修改一个节点（{@code menuKey} 为空 = 新增）。 */
    @PostMapping("/save")
    public ApiResponse<IdVo> save(@RequestBody MenuSaveRequest req) {
        StpUtil.checkPermission(Permissions.MENU_WRITE);
        return ApiResponse.ok(IdVo.of(menuService.save(req)));
    }

    /** 删除一个节点；{@code cascade=true} 连同全部下级一起删除。 */
    @PostMapping("/delete")
    public ApiResponse<DeletedVo> delete(@RequestParam(value = "menu_key") String menuKey,
                                        @RequestParam(value = "cascade", required = false) Boolean cascade) {
        StpUtil.checkPermission(Permissions.MENU_WRITE);
        if (menuKey == null || menuKey.isBlank()) {
            throw BizException.badRequest("menu_key 不能为空");
        }
        return ApiResponse.ok(DeletedVo.of(menuService.delete(menuKey, Boolean.TRUE.equals(cascade))));
    }
}
