package io.github.rurulalanana.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台配置（对应 application.yml 的 {@code gb28181.*}）。
 */
@Data
@ConfigurationProperties(prefix = "gb28181")
public class Gb28181Properties {

    /** 平台 20 位编码。 */
    private String platformId = "34020000002000000001";

    /** SIP 信令监听地址与端口。 */
    private String sipHost = "0.0.0.0";
    private int sipPort = 5060;

    /**
     * SIP 对外通告地址（写入 INVITE 的 From / Contact）。
     *
     * <p>留空自动推断本机非回环 IPv4。平台在 NAT 后或多网卡时必须显式配置，
     * 否则设备收到回环地址的 Contact 无法回包，表现为「点播无响应」。
     */
    private String sipAdvertisedIp;

    /**
     * SIP 注册鉴权密码（平台密码，系统级统一密码）。
     *
     * <p>设置后设备 REGISTER 需通过 Digest 鉴权，密码错误返回 401。
     * 留空 = 不鉴权（内网联调方便，公网部署务必设置）。
     *
     * <p><b>不校验用户名</b>：SIP 的 Authorization.username 只是查密码用的键，
     * 各厂商填法不一（SIP 域 / 平台编码 / 设备编号），强制校验会让大量设备注册失败。
     * 因此这里是全局统一密码，所有设备共用。
     */
    private String sipPassword;

    /** 媒体 IP：SDP {@code c=} 行与播放地址主机，必须是设备可达地址；留空自动探测。 */
    private String mediaIp = "";

    /** 媒体服务端口。 */
    private int httpPort = 8081;
    private int rtmpPort = 11935;
    private int rtspPort = 18554;
    private int rtcPort = 18000;

    /**
     * WebRTC 对外通告 IP（ZLM {@code rtc.externIP}）；留空回退 {@link #mediaIp}。
     *
     * <p>服务器在 NAT 后时必须填公网 IP：ZLM 默认只下发本机网卡地址当 ICE 候选，
     * 浏览器拿到内网地址连不上，表现为 SDP 协商成功但画面始终 0x0。
     */
    private String rtcExternIp = "";

    /** ZLM 应用名（播放 URL 第一段）。 */
    private String app = "rtp";

    /**
     * 单端口收流模式：所有设备推流到同一端口，ZLM 按 RTP 的 SSRC 分流。
     *
     * <p>默认关闭（每通道独占端口）。开启后端口数不再受 ZLM 内置端口池
     * （{@code [30000,35000]}，仅 2500 对）限制，防火墙也只需放行一个端口。
     *
     * <p><b>代价</b>：RTCP 失效（丢包率统计不可用）、ZLM 的 RTP detach 回调不触发
     * （超时改由流下线事件感知）、无法做 SSRC 过滤。
     */
    private boolean rtpSinglePort = false;

    /** 单端口模式下的收流端口（UDP 与 TCP 被动共用）。 */
    private int rtpSinglePortNumber = 24560;

    /** 是否随流上下线自动录像。 */
    private boolean autoRecord = false;

    /**
     * 「无人观看」自动关流的延迟（毫秒）。
     *
     * <p>点播后若在此时长内没有任何播放器拉流，ZLM 会关闭该路流并向设备发 BYE。
     * 默认 45 秒（参考站为 20 秒）：前端首次点播需要现场加载播放器 SDK
     * （jessibuca.js + decoder.wasm 约 1.3MB）与 WASM 编译，公网慢时 20 秒内
     * 可能还没建立拉流连接，流就会被提前关闭，表现为「点播很慢/失败」。
     */
    private int streamNoneReaderDelayMs = 45_000;

    /** 录像根目录（留空用 ${user.dir}/record）。 */
    private String recordRoot = "";

    /** 平台运行时行为。 */
    private Starter starter = new Starter();

    /** 平台运行时行为配置。 */
    @Data
    public static class Starter {

        /** 是否在启动时执行 db/schema.sql 建表（幂等，可重复执行）。 */
        private boolean initSchema = true;

        /** 启动后是否自动点播第一路在线设备（原自检行为）。 */
        private boolean selfCheck = false;

        /**
         * 设备离线判定阈值（秒）：超过该时长未收到心跳即标记离线。
         *
         * <p>应大于设备心跳周期的 2~3 倍，默认 180 秒（设备心跳通常 60 秒）。
         */
        private int deviceOfflineSeconds = 180;

        /**
         * 设备注册后是否自动下发查询采集数据（目录/信息/状态）。
         *
         * <p>关闭后需手动调 {@code /api/v1/device/fetchcatalog} 等接口拉取。
         */
        private boolean autoCollect = true;

        /**
         * 同一设备两次自动采集的最小间隔（秒）。
         *
         * <p>设备频繁重启会反复注册，靠这个节流避免把设备打爆。默认 300 秒。
         */
        private int collectIntervalSeconds = 300;

        /** 是否采集设备信息（厂商/型号/固件，对应 DeviceInfo 查询）。 */
        private boolean collectDeviceInfo = true;

        /** 是否采集设备状态（对应 DeviceStatus 查询）。 */
        private boolean collectDeviceStatus = true;
    }
}
