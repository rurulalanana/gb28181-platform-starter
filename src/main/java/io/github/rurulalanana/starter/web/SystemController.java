package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.entity.GbUser;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.StreamService;
import io.github.rurulalanana.starter.service.UserService;
import io.github.rurulalanana.starter.vo.ServerInfoVo;
import io.github.rurulalanana.starter.vo.TokenVo;
import io.github.rurulalanana.starter.vo.UserInfoVo;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Enumeration;

/**
 * 系统接口。
 *
 * <pre>
 * POST /api/v1/login            登录
 * GET  /api/v1/logout           登出
 * POST /api/v1/modifypassword   修改密码
 * GET  /api/v1/userinfo         当前用户信息
 * GET  /api/v1/getserverinfo    平台运行信息
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SystemController {

    private final UserService userService;
    private final DeviceService deviceService;
    private final StreamService streamService;
    private final Gb28181MediaPlatform platform;
    private final Gb28181Properties props;

    /** 平台启动时间，用于计算运行时长。 */
    private final long startedAt = System.currentTimeMillis();

    // ==================== 登录 / 登出 ====================

    /** 登录请求体。 */
    @Data
    public static class LoginRequest {
        /** 用户名。 */
        private String username;
        /** 密码，MD5 密文（32 位，小写，无中划线）。 */
        private String password;
        /** 是否只获取 URLToken（不落库 session）。 */
        private Boolean urlTokenOnly;
        /** Token 超时（秒）。 */
        private Long tokenTimeout;
    }

    /**
     * 登录。
     *
     * <p>返回 {@code cookieToken} / {@code urlToken} / {@code tokenTimeout} 三个字段，
     * 同时把 Token 写入响应头 {@code Authorization}，便于前端直接使用。
     */
    @PostMapping("/login")
    public ApiResponse<TokenVo> login(@RequestBody LoginRequest req) {
        GbUser user = userService.authenticate(req.getUsername(), req.getPassword());
        if (Boolean.TRUE.equals(req.getUrlTokenOnly())) {
            // 只取 URLToken：不落库，仅返回 Token 值
            String token = StpUtil.createLoginSession(user.getId());
            return ApiResponse.ok(tokenPayload(token, StpUtil.getTokenTimeout()));
        }
        StpUtil.login(user.getId());
        userService.touchLogin(user.getId());
        return ApiResponse.ok(tokenPayload(StpUtil.getTokenValue(), StpUtil.getTokenTimeout()));
    }

    /** 登出。 */
    @GetMapping("/logout")
    public ApiResponse<Void> logout() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        return ApiResponse.ok();
    }

    /** 修改密码请求体。 */
    @Data
    public static class ModifyPasswordRequest {
        /** 旧密码，MD5 密文。 */
        private String oldpassword;
        /** 新密码，MD5 密文。 */
        private String newpassword;
    }

    /** 修改密码。 */
    @PostMapping("/modifypassword")
    public ApiResponse<TokenVo> modifyPassword(@RequestBody ModifyPasswordRequest req) {
        StpUtil.checkLogin();
        long userId = StpUtil.getLoginIdAsLong();
        userService.modifyPassword(userId, req.getOldpassword(), req.getNewpassword());
        // 改密后旧 Token 失效，需重新签发
        StpUtil.logout();
        StpUtil.login(userId);
        return ApiResponse.ok(tokenPayload(StpUtil.getTokenValue(), StpUtil.getTokenTimeout()));
    }

    // ==================== 信息查询 ====================

    /** 当前登录用户信息。 */
    @GetMapping("/userinfo")
    public ApiResponse<UserInfoVo> userInfo() {
        StpUtil.checkLogin();
        GbUser user = userService.findById(StpUtil.getLoginIdAsLong());
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        UserInfoVo vo = new UserInfoVo();
        vo.setId(user.getId());
        vo.setName(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRoles(user.getRole() == null ? null
                : Arrays.stream(user.getRole().split(",")).map(String::trim).toList());
        vo.setPhoneNumber(user.getPhoneNumber());
        vo.setEmail(user.getEmail());
        vo.setDescription(user.getDescription());
        vo.setHasAllChannel(user.getHasAllChannel());
        vo.setToken(StpUtil.getTokenValue());
        vo.setLoginAt(TimeFormats.toDisplay(user.getLastLoginAt()));
        return ApiResponse.ok(vo);
    }

    /** 平台运行信息。 */
    @GetMapping("/getserverinfo")
    public ApiResponse<ServerInfoVo> serverInfo() {
        long[] deviceStats = deviceService.deviceStats();
        long[] channelStats = deviceService.channelStats();
        ServerInfoVo vo = new ServerInfoVo();
        vo.setServer("gb28181-platform-starter");
        vo.setInterfaceVersion("1.0.0");
        vo.setApiAuth(true);
        vo.setServerTime(TimeFormats.toDisplay(LocalDateTime.now()));
        vo.setStartUpTime(TimeFormats.toDisplay(TimeFormats.ofEpochMilli(startedAt)));
        vo.setRunningTime(runningTime());
        vo.setDeviceTotal(deviceStats[0]);
        vo.setDeviceOnline(deviceStats[1]);
        vo.setChannelTotal(channelStats[0]);
        vo.setChannelOnline(channelStats[1]);
        vo.setChannelCount(channelStats[0]);
        vo.setStreamCount(streamService.sessionCount());
        vo.setPlatformId(props.getPlatformId());
        vo.setPreferStreamFmt("webrtc");
        vo.setVersionType("open-source");
        vo.setCopyrightText("gb28181-platform-starter");
        vo.setSip(advertisedSipAddress() + ":" + props.getSipPort());
        if (platform.mediaServer() != null) {
            vo.setMediaHttp(platform.mediaServer().getHttpPort());
            vo.setMediaRtsp(platform.mediaServer().getRtspPort());
            vo.setMediaRtmp(platform.mediaServer().getRtmpPort());
            vo.setMediaWebRtc(platform.mediaServer().getRtcPort());
        }
        vo.setHardware(hardwareInfo());
        return ApiResponse.ok(vo);
    }

    /**
     * 设备可达的 SIP 地址（界面展示用）。
     *
     * <p><b>不能直接展示 {@code gb28181.sip-host}</b>：它的默认值是
     * {@code 0.0.0.0} —— 那是「监听所有网卡」的绑定地址，不是一个可拨号的地址。
     * 设备实际收到的 From / Contact / Via 里填的是「通告地址」。
     * 把 {@code 0.0.0.0} 摆在「信令地址」这一栏，运维照抄进设备配置必然注册失败，
     * 而平台侧只会看到「设备从未注册」，排查方向直接跑偏。
     *
     * <p>解析顺序与信令库的通告地址推断保持一致：
     * 显式 {@code sip-advertised-ip} → 显式 {@code sip-host}（非通配地址时）→ 自动探测本机 IPv4。
     */
    private String advertisedSipAddress() {
        String advertised = props.getSipAdvertisedIp();
        if (StringUtils.hasText(advertised)) {
            return advertised.trim();
        }
        String host = props.getSipHost();
        if (StringUtils.hasText(host) && !isWildcardHost(host.trim())) {
            return host.trim();
        }
        try {
            // 不写死网卡名：找一个非回环、非通配的 IPv4 即可
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            log.debug("自动探测本机 IPv4 失败：{}", e.getMessage());
        }
        return host == null ? "" : host.trim();
    }

    /** 是否是「监听所有网卡」类的通配地址。 */
    private static boolean isWildcardHost(String host) {
        return "0.0.0.0".equals(host) || "::".equals(host)
                || "[::]".equals(host) || "*".equals(host);
    }

    /**
     * 重启服务。
     *
     * <p>仅做「优雅停机」——依赖外部进程管理器（systemd / Docker restart policy）
     * 拉起；若以 {@code java -jar} 裸跑且无守护进程，进程退出后不会自动重启，
     * 因此默认拒绝该操作，需显式开启 {@code gb28181.starter.allow-restart}。
     */
    @PostMapping("/restart")
    public ApiResponse<Void> restart(@RequestParam(defaultValue = "false") boolean force) {
        StpUtil.checkLogin();
        if (!force) {
            throw BizException.badRequest(
                    "出于安全考虑已禁用远程重启；确认进程有守护时会自动拉起后，传 force=true 再试");
        }
        log.warn("收到重启请求，即将退出 JVM（依赖外部守护进程拉起）");
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "gb28181-restart").start();
        return ApiResponse.ok();
    }

    // ==================== 内部 ====================

    private TokenVo tokenPayload(String token, long timeout) {
        TokenVo vo = new TokenVo();
        vo.setCookieToken(token);
        vo.setUrlToken(token);
        // 兼容字段
        vo.setToken(token);
        vo.setAuthToken(token);
        vo.setTokenTimeout(timeout);
        return vo;
    }

    private String runningTime() {
        Duration d = Duration.ofMillis(System.currentTimeMillis() - startedAt);
        return String.format("%d 天 %d 小时 %d 分 %d 秒",
                d.toDays(), d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart());
    }

    private String hardwareInfo() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long maxMb = rt.maxMemory() / 1024 / 1024;
        int cores = rt.availableProcessors();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return String.format("JVM 堆 %d/%d MB, CPU %d 核, JVM 运行 %d 秒", usedMb, maxMb, cores, uptime);
    }
}
