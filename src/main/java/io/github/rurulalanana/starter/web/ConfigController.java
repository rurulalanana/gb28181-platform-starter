package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.service.RuntimeConfigApplier;
import io.github.rurulalanana.starter.service.RuntimeConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运行时配置接口（{@code /api/v1/config/*}）。改完立即生效，不重启：语义是「数据库优先、yml 兜底」
 * ——库里没有的 key 用 yml；保存过就写库并覆盖 yml；reset 则删除覆盖回落 yml。
 *
 * <p>只暴露运维参数（保留天数、间隔、阈值、分片时长）。端口、路径、数据库连接等决定进程装配的配置
 * 仍在 application.yml：放进来只会造成「界面显示改了却实际没生效」的误解。
 */
@Slf4j
@RestController
@Tag(name = "运行时配置", description = "可热更新的运维参数")
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final RuntimeConfigService config;
    private final RuntimeConfigApplier applier;

    @Operation(summary = "全部配置项（含默认值、取值范围、说明、是否已被覆盖）")
    @GetMapping("/list")
    public ApiResponse<List<RuntimeConfigService.Item>> list() {
        StpUtil.checkLogin();
        return ApiResponse.ok(config.list());
    }

    /** 保存一项配置（立即生效）。值非法时返回 400 并带上具体原因（{@code ConfigKeys.Def#validate}），不静默截断。 */
    @Operation(summary = "保存一项配置（立即生效）")
    @PostMapping("/save")
    public ApiResponse<RuntimeConfigService.Item> save(@RequestParam String key,
                                                       @RequestParam String value) {
        StpUtil.checkLogin();
        String applied = config.set(key, value);
        // 需要主动推送到媒体层的项（分片时长、无人观看时长）
        applier.apply(key);
        for (RuntimeConfigService.Item item : config.list()) {
            if (item.getKey().equals(key)) {
                log.info("配置已保存并生效: {} = {}", key, applied);
                return ApiResponse.ok(item);
            }
        }
        return ApiResponse.ok(null);
    }

    /** 恢复一项为默认值：删除数据库覆盖，回落 application.yml。 */
    @Operation(summary = "恢复一项为默认值（删除数据库覆盖，回落 application.yml）")
    @PostMapping("/reset")
    public ApiResponse<RuntimeConfigService.Item> reset(@RequestParam String key) {
        StpUtil.checkLogin();
        String applied = config.reset(key);
        applier.apply(key);
        for (RuntimeConfigService.Item item : config.list()) {
            if (item.getKey().equals(key)) {
                log.info("配置已重置为默认值: {} = {}", key, applied);
                return ApiResponse.ok(item);
            }
        }
        return ApiResponse.ok(null);
    }

    /**
     * 批量保存（一次提交整页改动）。前端循环发 N 个单项请求时，中途某项失败会让前面的已生效、
     * 界面却提示「保存失败」，用户不知道存进去了哪些。整体先校验再落库：任何一项非法就整体拒绝，
     * 半套配置生效比全都不生效更难排查。
     */
    @Operation(summary = "批量保存（一次提交整页改动）")
    @PostMapping("/save-batch")
    public ApiResponse<java.util.Map<String, String>> saveBatch(
            @org.springframework.web.bind.annotation.RequestBody
            java.util.Map<String, String> body) {
        StpUtil.checkLogin();
        java.util.Map<String, String> applied = config.setBatch(body);
        // 需要推送到媒体层的项（分片时长、无人观看时长）逐个应用
        for (String key : applied.keySet()) {
            applier.apply(key);
        }
        log.info("配置批量保存成功：{} 项", applied.size());
        return ApiResponse.ok(applied);
    }
}
