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

    /** ZLM 应用名（播放 URL 第一段）。 */
    private String app = "rtp";

    /** 是否随流上下线自动录像。 */
    private boolean autoRecord = false;

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
