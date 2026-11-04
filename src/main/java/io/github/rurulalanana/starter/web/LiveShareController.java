package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.PlayUrls;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.dto.DeviceRef;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.entity.GbStream;
import io.github.rurulalanana.starter.service.ControlService;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.StreamService;
import io.github.rurulalanana.starter.vo.LiveShareVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 直播分享页的免登录接口。
 *
 * <p>分享的语义就是给不登录平台的人看直播，所以这一组必须免登录；
 * 也所以不能复用 {@code /stream/start} —— 那个返回 {@code StreamVo}，
 * 带 SSRC / callId / RTP 端口等信令细节，不该暴露给拿到分享链接的人。
 *
 * <p><b>安全边界</b>：知道设备编码 + 通道编码即可观看。国标编码不是授权凭证，
 * 将来要管控应在 URL 上加签名（可复用 {@code ShareTokenService} 的 HMAC），
 * 而不是把登录校验加回来。
 *
 * <p>副作用：首次访问会发 INVITE 拉起一路流，页面关闭后由空闲巡检回收。
 */
@Slf4j
@RestController
@Tag(name = "直播分享", description = "免登录的分享播放页数据")
@RequestMapping("/api/v1/share")
@RequiredArgsConstructor
public class LiveShareController {

    private final DeviceService deviceService;
    private final StreamService streamService;
    private final ControlService controlService;
    private final Gb28181MediaPlatform platform;

    /**
     * 取分享页所需的通道信息与播放地址。
     *
     * <p>点播失败不报错：通道不存在 / 设备离线 / 媒体服务未就绪都通过 {@code notice}
     * 回给前端，页面照常打开。分享页给外部人员看，返 4xx/5xx 只会变成一段英文错误页。
     */
    @Operation(summary = "取分享页所需的通道信息与播放地址")
    @GetMapping("/live")
    public ApiResponse<LiveShareVo> live(@RequestParam String serial,
                                         @RequestParam(required = false) String code) {
        if (serial == null || serial.isBlank()) {
            throw BizException.badRequest("缺少参数 serial（设备编码）");
        }
        String deviceId = serial.trim();
        String channelId = DeviceRef.channelOf(deviceId, code);
        LiveShareVo vo = new LiveShareVo();
        vo.setDeviceId(deviceId);
        vo.setChannelId(channelId);

        GbChannel channel = deviceService.findChannel(deviceId, channelId);
        if (channel == null) {
            vo.setName(channelId);
            vo.setNotice("通道不存在或尚未同步到平台");
            return ApiResponse.ok(vo);
        }
        vo.setName(displayName(channel));
        /*
         * 云台类型要下发：分享页据此决定是否显示控制浮层。
         * 0（未知）与 3（固定枪机）不能转，显示了只会让人反复点。
         */
        vo.setPtzType(channel.getPtzType());
        GbDevice device = deviceService.findDevice(deviceId);
        boolean online = device != null && Boolean.TRUE.equals(device.getOnline());
        vo.setDeviceOnline(online);
        vo.setStatus(channel.getStatus());
        if (!online) {
            vo.setNotice("设备当前不在线，无法拉流");
            return ApiResponse.ok(vo);
        }
        if (streamService.mediaService() == null) {
            vo.setNotice("媒体服务未启动");
            return ApiResponse.ok(vo);
        }

        /*
         * 点播 + 组地址。
         *
         * 已有会话时 startLive 会复用（内部「先关旧会话再建」的语义见
         * StreamService#startLive），因此刷新分享页不会反复向设备发 INVITE
         * 之外的额外压力。
         */
        try {
            GbStream stream = streamService.startLive(deviceId, channelId);
            RtpSession session = streamService.session(deviceId, channelId);
            PlayUrls urls = session == null
                    ? platform.mediaServer().playUrls(stream.getDeviceId(), stream.getChannelId())
                    : platform.mediaServer().playUrls(session);
            if (urls != null) {
                vo.setWsFlv(urls.getWsFlv());
                vo.setFlv(urls.getHttpFlv());
                vo.setHls(urls.getHls());
                vo.setRtmp(urls.getRtmp());
                vo.setRtsp(urls.getRtsp());
            }
            vo.setStreaming(session != null && RtpSession.ONLINE.equals(session.getState()));
            if (urls == null) {
                vo.setNotice("暂时拿不到播放地址，请稍后重试");
            }
        } catch (Exception e) {
            /*
             * 点播被拒是常态（设备通道被占用、下载中、SIP 超时…），
             * 记 WARN 而不记 ERROR：分享页会把它显示成提示文字，不是平台故障。
             */
            log.warn("分享页点播失败: {}/{} {}", deviceId, channelId, e.getMessage());
            vo.setNotice("点播失败：" + rootMessage(e));
        }
        return ApiResponse.ok(vo);
    }

    /**
     * 分享页的云台控制（免登录）。
     *
     * <p>允许分享页转云台是产品决策：分享出去的典型场景就是「帮我看一下这个点位」，
     * 不能转的话对方只能看到一片天空。因为免登录，必须比登录版多两道保护：
     * 指令白名单（防故意传奇怪动作）、每通道限流（云台是机械结构，会被真搞坏）。
     */
    @Operation(summary = "分享页的云台控制（免登录）")
    @PostMapping("/ptz")
    public ApiResponse<Void> ptz(@RequestParam String serial,
                                 @RequestParam(required = false) String code,
                                 @RequestParam String command,
                                 @RequestParam(defaultValue = "129") Integer speed) {
        if (serial == null || serial.isBlank()) {
            throw BizException.badRequest("缺少参数 serial");
        }
        String deviceId = serial.trim();
        String channelId = DeviceRef.channelOf(deviceId, code);
        String cmd = command == null ? "" : command.trim().toLowerCase();
        if (!PTZ_COMMANDS.contains(cmd)) {
            throw BizException.badRequest("不支持的云台指令：" + command);
        }
        /*
         * 限流按「通道 + 动作」分别计时，不能只按通道：前端「按住转向、松开停止」的序列是
         * left → stop，两者必须都能立刻发出；共用一个计时器会把 stop 挡掉，镜头就一直转。
         */
        String gate = channelId + "#" + cmd;
        long now = System.currentTimeMillis();
        Long prev = PTZ_LAST_AT.get(gate);
        if (prev != null && now - prev < PTZ_MIN_INTERVAL_MS) {
            // 静默丢弃而不是报错：按住方向键时前端会持续发送，报错会刷满控制台
            return ApiResponse.ok();
        }
        /*
         * 固定枪机直接拒：前端已按 ptzType 隐藏了控制浮层，但那是展示层的约束，
         * 这个接口免登录，谁都能直接 POST。0（未知）不拦：老通道可能没上报过，拦了会误伤。
         */
        GbChannel ch = deviceService.findChannel(deviceId, channelId);
        if (ch != null && ch.getPtzType() != null && ch.getPtzType() == 3) {
            throw BizException.badRequest("该通道是固定枪机，不支持云台控制");
        }
        PTZ_LAST_AT.put(gate, now);
        // 防内存无界增长：访问过的通道会一直留在表里，定容清理即可
        if (PTZ_LAST_AT.size() > 512) {
            long deadline = now - PTZ_MIN_INTERVAL_MS * 10L;
            PTZ_LAST_AT.entrySet().removeIf(e -> e.getValue() < deadline);
        }
        try {
            controlService.ptz(channelId, cmd, speed);
        } catch (Exception e) {
            log.warn("分享页云台指令失败: {} {} {}", channelId, cmd, e.getMessage());
            throw e;
        }
        return ApiResponse.ok();
    }

    /** 分享页允许的云台动作（与 {@code ControlService#ptz} 的 switch 分支一一对应）。 */
    private static final Set<String> PTZ_COMMANDS = Set.of(
            "up", "down", "left", "right",
            "upleft", "upright", "downleft", "downright",
            "zoomin", "zoomout", "stop"
    );

    /**
     * 同一「通道 + 动作」的最小间隔（毫秒）。
     *
     * <p>取 120ms：前端按住方向键约 150ms 续发一次，正常操作不受影响；
     * 脚本狂刷会被压到 8 次/秒以内。只防恶意/失控，不是性能开关。
     */
    private static final long PTZ_MIN_INTERVAL_MS = 120L;

    /** 最近一次云台指令时间：key = {@code 通道编码#动作}。 */
    private final Map<String, Long> PTZ_LAST_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /** 通道展示名：自定义名 → 目录原始名 → 通道编码（分享页只有一行标题，留白不如退编码）。 */
    private static String displayName(GbChannel ch) {
        if (ch.getCustomName() != null && !ch.getCustomName().isBlank()) {
            return ch.getCustomName().trim();
        }
        if (ch.getName() != null && !ch.getName().isBlank()) {
            return ch.getName().trim();
        }
        return ch.getChannelId();
    }

    /** 取最内层异常的消息：SIP 层会套多层包装，外层那句往往只有「点播失败」。 */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }
}
