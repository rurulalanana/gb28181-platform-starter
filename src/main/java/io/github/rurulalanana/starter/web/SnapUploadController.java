package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.config.DahuaProperties;
import io.github.rurulalanana.starter.service.SnapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设备主动上传抓图的接收端点。
 *
 * <h3>为什么这条比「平台向设备要图」更好</h3>
 * <pre>
 *   平台要图：平台 → 设备（SDK 会话往返）→ 返回 JPEG   每次都要一次完整交互
 *   设备上传：设备检测到事件 → 直接 POST 一张 JPEG 上来  平台只需落盘
 * </pre>
 * 报警联动抓图（人形、越界、动检）本来就是设备主动产生的，
 * 让它推上来既省一次往返，也不需要平台为每台设备轮询。
 *
 * <h3>鉴权为什么走 URL 令牌而不是请求头</h3>
 * 大华设备的 HTTP 上传配置<b>只能填一个 URL</b>，不支持自定义请求头。
 * 所以令牌只能放在 query 里。这是设备侧限制，不是设计选择。
 *
 * <p>因此本端点做了三件事来限制风险：
 * <ol>
 *   <li>默认<b>关闭</b>，配置 {@code dahua.snap-upload.enabled=true} 才启用；</li>
 *   <li>令牌为空时拒绝启动（不允许「无令牌开放写入口」）；</li>
 *   <li>只接收图片字节，落盘到固定的快照目录，<b>不接收文件名</b> ——
 *       文件名由平台生成（设备传来的文件名可能带路径穿越）。</li>
 * </ol>
 *
 * <p>本端点<b>不走 Sa-Token</b>：设备没有登录态。它必须在
 * {@code SaTokenConfiguration} 的白名单里，鉴权由本节点的令牌校验负责。
 */
@Slf4j
@RestController
@Tag(name = "设备抓图直传", description = "大华设备主动上传报警抓图")
@RequestMapping("/api/v1/dahua/snapupload")
@RequiredArgsConstructor
public class SnapUploadController {

    private final DahuaProperties props;
    private final SnapService snapService;

    /**
     * 接收一张设备上传的抓图。
     *
     * <p>设备侧地址示例：
     * <pre>http://平台:48088/api/v1/dahua/snapupload?token=xxx&amp;sn=CG0F442PAJ81C73&amp;channel=1</pre>
     *
     * @param token   上传令牌（与配置一致）
     * @param sn      设备序列号（作为快照归属的设备编号）
     * @param channel 通道号，缺省 1
     * @param body    JPEG 原始字节（Content-Type: image/jpeg 或 application/octet-stream）
     */
    @Operation(summary = "设备上传抓图")
    @PostMapping(consumes = {"image/jpeg", "application/octet-stream", "*/*"})
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam String token,
            @RequestParam String sn,
            @RequestParam(defaultValue = "1") Integer channel,
            @RequestBody byte[] body) {
        DahuaProperties.SnapUpload cfg = props.getSnapUpload();
        if (!cfg.isEnabled()) {
            throw BizException.badRequest("抓图直传未开启（dahua.snap-upload.enabled=false）");
        }
        // 令牌必须非空且完全一致。空令牌直接拒绝 —— 不能因为「没配」就放行
        if (cfg.getToken() == null || cfg.getToken().isBlank()) {
            log.error("抓图直传已开启但未配置令牌，拒绝接收（请设置 GB_SNAP_TOKEN）");
            throw BizException.forbidden("抓图直传未配置令牌，服务端拒绝接收");
        }
        if (!cfg.getToken().equals(token)) {
            log.warn("抓图直传令牌不匹配: sn={} 来源令牌长度={}", sn, token == null ? 0 : token.length());
            throw BizException.forbidden("令牌无效");
        }
        if (body == null || body.length == 0) {
            throw BizException.badRequest("上传内容为空");
        }
        if (body.length > cfg.getMaxBytes()) {
            throw BizException.badRequest("图片超过上限 " + (cfg.getMaxBytes() / 1048576) + " MB");
        }
        // JPEG 必须从 FFD8 开始：不是图片就拒掉，避免这条口子被当成通用文件上传
        if (body.length < 2 || (body[0] & 0xFF) != 0xFF || (body[1] & 0xFF) != 0xD8) {
            throw BizException.badRequest("内容不是 JPEG 图片");
        }

        String deviceId = sn == null || sn.isBlank() ? "unknown" : sn.trim();
        // 通道号是设备侧口径（从 1 起），转成平台通道编码 <sn>-<n>
        String code = deviceId + "-" + Math.max(1, channel);
        String url = snapService.saveBytes(deviceId, code, body);
        if (url == null) {
            throw BizException.badRequest("快照保存失败");
        }
        log.info("收到设备直传抓图: sn={} channel={} size={} -> {}", deviceId, channel, body.length, url);
        return ApiResponse.ok(Map.of(
                "saved", true,
                "deviceId", deviceId,
                "channelId", code,
                "size", body.length,
                "url", url));
    }
}
