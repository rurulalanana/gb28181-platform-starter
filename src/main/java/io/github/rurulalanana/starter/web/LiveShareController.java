package io.github.rurulalanana.starter.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 直播分享页的免登录接口。
 *
 * <pre>
 * GET /api/v1/share/live?serial=&lt;设备编码&gt;&amp;code=&lt;通道编码&gt;
 * </pre>
 *
 * <h3>为什么必须免登录</h3>
 * 「分享」的语义就是给<b>不登录平台的人</b>看一路直播。分享页
 * {@code /play?serial=…&code=…} 由外部人员直接打开，既没有账号也不带 token ——
 * 这个接口要是要求登录，分享链接对任何人都无效。
 *
 * <h3>为什么单独一个 Controller 而不复用 {@code /stream/start}</h3>
 * <ul>
 *   <li>{@code stream/start} 返回 {@code StreamVo}，里面有 SSRC、callId、RTP 端口、
 *       内部会话键等信令细节。免登录接口不该把这些暴露出去 ——
 *       拿到分享链接的人只需要「一个能播的地址」。</li>
 *   <li>出参字段是给分享页量身定的（通道名、在线状态、三种播放地址），
 *       前端不必再调第二、第三个接口拼数据。</li>
 * </ul>
 *
 * <h3>安全边界（明确写在这里，便于后续收紧）</h3>
 * 本接口按 LiveGBS 的分享语义实现：<b>知道设备编码 + 通道编码即可观看</b>。
 * 20 位国标编码本身有一定「猜不到」的属性，但它不是授权凭证 ——
 * 若将来需要管控，正确做法是在 URL 上加签名参数（可复用
 * {@code ShareTokenService} 的 HMAC 方案），而不是把登录校验加回来。
 *
 * <p>副作用与登录态点播一致：首次访问会向设备发 INVITE 拉起一路流，
 * 页面关闭后由空闲巡检回收（45 秒）。
 */
@Slf4j
@RestController
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
     * <p><b>不会因为点播失败而报错</b>：通道不存在、设备离线、媒体服务没起来
     * 这些情形都通过 {@code notice} 回给前端，页面照常打开、在播放区显示原因。
     * 分享页是给外部人员看的，返回 4xx/5xx 只会变成浏览器的一段英文错误页，
     * 什么信息都传达不了。
     *
     * @param serial 设备编码
     * @param code   通道编码；省略时视为设备直连（设备编码即通道编码）
     */
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
     * <pre>POST /api/v1/share/ptz?serial=&code=&command=&speed=</pre>
     *
     * <h3>为什么分享页允许控制云台</h3>
     * 对齐 LiveGBS 的 {@code play.html}：分享页带完整的方向/变倍控制。
     * 这是产品决策 —— 分享出去的往往就是「帮我看一下这个点位」，
     * 不能转云台的话对方只能看到一片天空或墙壁。
     *
     * <h3>因此必须加上登录版没有的两道保护</h3>
     * <ol>
     *   <li><b>指令白名单</b>：只放行 PTZ 的 11 个动作（4 向 + 4 斜向 + 2 变倍 + stop）。
     *       登录版靠 {@code ControlService} 的 switch default 抛异常兜底，
     *       但那是「意外传错」，这里要防的是「故意传别的」；</li>
     *   <li><b>每通道限流</b>：同一通道两次云台指令间隔不得小于
     *       {@link #PTZ_MIN_INTERVAL_MS}。没有它，一个脚本就能让镜头
     *       每秒转几百次 —— 云台是机械结构，会被真的搞坏。</li>
     * </ol>
     *
     * <p>不做「按通道授权」：分享链接本身就是凭证（见类注释的安全边界）。
     * 需要更细的管控时，正确做法是给链接加签名参数，而不是在这里加登录。
     *
     * @param serial  设备编码
     * @param code    通道编码；省略时视为设备直连
     * @param command 动作：{@code up/down/left/right/upleft/.../zoomin/zoomout/stop}
     * @param speed   速度 0~255，默认 129（与登录版一致）
     */
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
         * 限流：按「通道 + 动作」分别计时。
         *
         * <p>按动作而不是只按通道：前端「按住转向、松开停止」的序列是
         * {@code left → stop}，两者必须都能立刻发出；若共用一个计时器，
         * stop 会被 left 挡掉，镜头就会一直转下去 —— 这是最危险的失败模式。
         */
        String gate = channelId + "#" + cmd;
        long now = System.currentTimeMillis();
        Long prev = PTZ_LAST_AT.get(gate);
        if (prev != null && now - prev < PTZ_MIN_INTERVAL_MS) {
            // 静默丢弃而不是报错：按住方向键时前端会持续发送，报错会刷满控制台
            return ApiResponse.ok();
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
     * <p>取 120ms：前端按住方向键时约每 150ms 续发一次，
     * 正常操作不受影响；而脚本狂刷会被压到 8 次/秒以内，
     * 机械结构撑得住。这个值只防「恶意/失控」，不是性能开关。
     */
    private static final long PTZ_MIN_INTERVAL_MS = 120L;

    /** 最近一次云台指令时间：key = {@code 通道编码#动作}。 */
    private final Map<String, Long> PTZ_LAST_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 通道的展示名。
     *
     * <p>顺序：自定义名 → 目录原始名 → 通道编码。
     * 分享页顶部只有一行标题，退回编码总比留白强。
     */
    private static String displayName(GbChannel ch) {
        if (ch.getCustomName() != null && !ch.getCustomName().isBlank()) {
            return ch.getCustomName().trim();
        }
        if (ch.getName() != null && !ch.getName().isBlank()) {
            return ch.getName().trim();
        }
        return ch.getChannelId();
    }

    /**
     * 生成二维码 PNG（分享页的「扫码直播」）。
     *
     * <pre>GET /api/v1/share/qr?text=&lt;要编码的内容&gt;</pre>
     *
     * <h3>为什么由后端生成而不是前端引 JS 库</h3>
     * 前端引一个 QR 编码库要多打包 20~30KB，而本项目的主包已经很大；
     * 后端只需 zxing 的 {@code core}（约 590KB，且已有本地仓库），
     * 一次 HTTP 就拿到 PNG，前端还能直接用 {@code <img>} 缓存。
     *
     * <h3>为什么不用 {@code MatrixToImageWriter}</h3>
     * 那个类在 {@code zxing:javase} 里，会拖进 {@code jai-imageio} ——
     * 体积大，且它的 {@code ImageIO} 注册会和 JDK 自带的冲突。
     * 这里拿到 {@code BitMatrix} 后逐点写 {@code BufferedImage} 即可，
     * 逻辑十行，没有额外依赖。
     *
     * <h3>安全</h3>
     * 与分享接口同一个白名单（{@code /api/v1/share/**}）：二维码内容就是
     * 分享页地址，本来就要给外部看。但会限制 {@code text} 长度 ——
     * 无长度上限时一个恶意请求可以让服务端反复做高版本二维码编码（CPU 消耗），
     * 也保护内存。
     *
     * @param text 要编码的文本（分享页 URL）
     * @param size 边长（像素），默认 180；限制在 [80, 600]
     */
    @GetMapping(value = "/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@RequestParam String text,
                                     @RequestParam(defaultValue = "180") int size) {
        if (text == null || text.isBlank() || text.length() > MAX_QR_TEXT) {
            return ResponseEntity.badRequest().build();
        }
        int px = Math.max(80, Math.min(600, size));
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    text, BarcodeFormat.QR_CODE, px, px, QR_HINTS);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    img.setRGB(x, y, matrix.get(x, y) ? BLACK : WHITE);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            ImageIO.write(img, "png", out);
            return ResponseEntity.ok()
                    // 分享页地址变化不频繁，允许浏览器缓存一段时间，减少重复编码
                    .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                    .contentType(MediaType.IMAGE_PNG)
                    .body(out.toByteArray());
        } catch (WriterException e) {
            log.warn("二维码生成失败（内容长度 {}）：{}", text.length(), e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            log.warn("二维码输出失败：{}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 二维码最大编码长度：分享页 URL 不会超过这个数，超了就是恶意输入。 */
    private static final int MAX_QR_TEXT = 1024;

    /** 二维码配色：纯黑白，纠错等级 M（容量与容错的常见折中）。 */
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final Map<EncodeHintType, Object> QR_HINTS = Map.of(
            EncodeHintType.CHARACTER_SET, "UTF-8",
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN, 1
    );

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
