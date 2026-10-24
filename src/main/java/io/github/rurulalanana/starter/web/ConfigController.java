package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.service.RuntimeConfigApplier;
import io.github.rurulalanana.starter.service.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运行时配置接口（{@code /api/v1/config/*}）。
 *
 * <p><b>这些配置改完立即生效，不需要重启</b>。语义是
 * 「数据库优先、application.yml 兜底」：
 * <ul>
 *   <li>表 {@code gb_config} 里没有的 key → 用 yml 的值；</li>
 *   <li>界面上保存过 → 写库并覆盖 yml，重启后仍是界面上设的值；</li>
 *   <li>{@code /reset} → 删除库里的覆盖，回落到 yml。</li>
 * </ul>
 *
 * <pre>
 * GET  /api/v1/config/list                  全部配置项及当前值
 * POST /api/v1/config/save                  保存一项（key + value）
 * POST /api/v1/config/reset                 恢复一项为默认值
 * </pre>
 *
 * <p><b>只暴露运维参数</b>（保留天数、间隔、阈值、分片时长）。
 * 端口、路径、数据库连接等决定进程装配的配置仍在 application.yml ——
 * 放进这里只会造成「界面显示改了却实际没生效」的误解。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final RuntimeConfigService config;
    private final RuntimeConfigApplier applier;

    /** 全部配置项（含默认值、取值范围、说明、是否已被覆盖）。 */
    @GetMapping("/list")
    public ApiResponse<List<RuntimeConfigService.Item>> list() {
        StpUtil.checkLogin();
        return ApiResponse.ok(config.list());
    }

    /**
     * 保存一项配置（立即生效）。
     *
     * <p>值非法时返回 400 并带上具体原因（由
     * {@code ConfigKeys.Def#validate} 抛出），而不是静默截断。
     */
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

    /** 恢复一项为默认值（删除数据库覆盖，回落 application.yml）。 */
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
     * 批量保存（一次提交整页改动）。
     *
     * <p><b>为什么需要</b>：配置页的常规交互是「改几项、最后点一次保存」。
     * 让前端循环发 N 个单项请求，一旦中途某项失败，前面的已经生效而界面
     * 提示「保存失败」，用户不知道到底存进去了哪些。
     *
     * <p>整体先校验再落库：任何一项非法就整体拒绝，不做部分成功 ——
     * 半套配置生效比全都不生效更难排查。
     *
     * @param body key → 期望值（JSON 对象）
     * @return 实际生效的 key → 规范化后的值
     */
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
