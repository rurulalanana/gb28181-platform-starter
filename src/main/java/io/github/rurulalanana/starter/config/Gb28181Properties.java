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
     * SIP 信令要监听的传输协议，逗号分隔（默认 {@code UDP,TCP}）。
     *
     * <p><b>只监听 UDP 是常见的踩坑点</b>：GB/T 28181 允许设备用 UDP 或 TCP
     * 传信令，由设备侧配置决定。配成 TCP 的设备在只有 UDP 监听时<b>完全注册不上</b>，
     * 而且平台日志里一条记录都没有 —— 极易误判成网络不通或鉴权失败。
     *
     * <p>两个协议共用 {@link #sipPort}：SIP 的 UDP 与 TCP 属于不同传输层，
     * 同端口不冲突，防火墙需同时放行 udp 与 tcp。
     *
     * <p>取值：{@code "UDP,TCP"}（默认）/ {@code "UDP"} / {@code "TCP"}。
     * 非法值会被忽略并告警，不会让整个 SIP 起不来。
     */
    private String sipTransports = "UDP,TCP";

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

    /**
     * RTP 收流 socket 的接收缓冲（字节），对应 ZLM 的
     * {@code [rtp_proxy] udp_recv_socket_buffer}。
     *
     * <p><b>为什么不能留默认值</b>：国标摄像机收到 INVITE 后会把编码器里攒的
     * 码流<b>一次性倾出</b>（实测首秒包量约为稳态的 2.4 倍）。缓冲不够时内核
     * 直接丢包，而丢的往往正是开头的 SPS/PPS + 首个 I 帧 —— ZLM 等轨就绪超时后
     * 会把视频轨<b>永久丢弃</b>（日志 {@code Track not ready for a long time,
     * ignored: H264}），媒体源从此只剩音频，浏览器永远是黑屏。
     * 现场表现就是「重启后第一次播放不出画面，关掉重播才行」。
     *
     * <p><b>宿主机还要配套改内核参数</b>，否则本值会被 {@code net.core.rmem_max}
     * 静默夹小（默认常为 208KB）：
     * <pre>sysctl -w net.core.rmem_max=16777216</pre>
     * 持久化写 {@code /etc/sysctl.d/99-gb28181-rtp.conf}，详见 {@code docs/部署说明.md}。
     *
     * <p><b>容量估算</b>：单路 1080p 约 2Mbps，单端口模式下所有通道共用一个
     * socket、共享这块缓冲。8MB ≈ 32 秒单路码流，或 13 路并发时约 2.5 秒 ——
     * 通道数越多、突发越集中，越应该加大（32MB 可覆盖 50 路级别的重启风暴）。
     */
    private int udpRecvSocketBuffer = 8 * 1024 * 1024;

    /** 是否随流上下线自动录像。 */
    private boolean autoRecord = false;

    /**
     * 云端录像单文件最长时长（秒），到点由 ZLM 自动切分片。
     *
     * <p><b>这是「崩溃损失上限」，不是磁盘优化参数。</b>ZLM 的原生库存在
     * 未捕获的 C++ 异常（实测 {@code RtmpMediaSourceMuxer::getOwnerPoller
     * not implemented} → {@code terminate()} → SIGABRT），进程会整个崩掉。
     * 崩溃时正在写的分片来不及写 moov，留下一个大小正常但<b>无法播放</b>的
     * 孤儿文件（{@code moov atom not found}，且无法修复）。
     *
     * <p>因此这个值直接等于「一次崩溃最多丢多久的录像」：
     * <ul>
     *   <li>{@code 3600}（1 小时，ZLM 默认）：实测崩一次丢 55 分钟 / 319 MB；</li>
     *   <li>{@code 1800}（30 分钟，本平台默认）：最多丢 30 分钟，
     *       一天 24 个文件；</li>
     *   <li>{@code 300}（5 分钟）：最多丢 5 分钟，但一天 288 个文件，太碎。</li>
     * </ul>
     *
     * <p>云端录像列表按「一段 = 一条记录」展示，对账会把每个分片都登记进来，
     * 因此不影响检索。磁盘占用不变。
     *
     * <p>若确认原生库崩溃已被上游修复，可调回 3600 进一步减少文件数。
     */
    private int recordMaxSecond = 1800;

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

    /**
     * 录像根目录的实际取值：配置为空时回落到「工作目录 + {@code /record}」。
     *
     * <p><b>为什么要收敛成一个方法</b>：这个回落规则原本在 7 处各写一遍
     * （媒体装配、静态资源映射、清理任务、对账、归档、快照、转码），
     * 其中一处写成 {@code System.getProperty("user.dir") + "/record"}、
     * 另一处走 {@code Path.of(userDir, "record")}。规则一旦要改
     * （例如改成可配置的绝对路径），漏改任何一处都会让
     * 「录像在 A 目录、清理看 B 目录」，表现为磁盘明明满了却不清理。
     */
    public String resolvedRecordRoot() {
        return recordRoot == null || recordRoot.isBlank()
                ? System.getProperty("user.dir") + "/record"
                : recordRoot;
    }

    /**
     * 是否由<b>本进程</b>把录像目录暴露成静态资源（{@code /record/**}）。
     *
     * <p><b>默认为 {@code false}，即交给 nginx</b>。因为 Spring Boot 的
     * 静态资源处理器处理 Range 请求极慢 —— 同一段 2MB，实测：
     * <pre>
     *   Spring Boot 静态映射            16~20 秒（110 KB/s）
     *   nginx（sendfile + keep-alive）  0.06~0.15 秒（24 MB/s）
     * </pre>
     * 差 200 倍。而录像回放要按 GOP 分段下载很多次 Range，
     * 这个差距直接决定「起播 15 秒还是 2 秒」。
     *
     * <p>标准部署形态见 {@code deploy/nginx-record.conf}：nginx 监听公网端口，
     * {@code /record/**} 用 sendfile 直读磁盘，其余请求反代回本进程。
     *
     * <p>什么时候需要打开：没有 nginx 的单机/内网部署（比如直接
     * {@code java -jar} 跑起来试）。打开后浏览器可以用
     * {@code <video src="/record/...">} 直接播，但大文件拖动会明显变慢。
     *
     * <p><b>安全提示</b>：开启意味着知道路径就能下载录像，
     * 公网部署不要开。
     */
    private boolean recordStaticEnabled = false;

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

    /**
     * HTTPS（TLS 证书）配置。
     *
     * <p><b>为什么 TLS 在 nginx 终结而不在应用内</b>：本项目的标准部署是
     * {@code nginx(公网端口) → java(内网端口)}，录像由 nginx sendfile 直读。
     * 若在应用内开 HTTPS，nginx 回源就得改成 https，证书更新要重启连接器，
     * 等于两套东西互相打架；而 nginx 本来就该干这件事。
     *
     * <p>因此本组配置只描述「nginx 该怎么配」，实际生效由
     * {@code TlsCertificateService} 生成 nginx 配置片段并 reload 完成。
     */
    @Data
    public static class Tls {

        /**
         * 证书与私钥的存放目录。
         *
         * <p>默认放在应用目录下的 {@code ssl/}。私钥写入后权限强制为 600 ——
         * 私钥泄漏等于任何人都能冒充本站，比密码泄漏严重得多。
         */
        private String certDir;

        /**
         * nginx 的 HTTPS 站点配置文件绝对路径。
         *
         * <p>默认 {@code /etc/nginx/conf.d/gb28181-ssl.conf}。由本应用整体生成，
         * 不要手工编辑 —— 下次上传证书会被覆盖。
         */
        private String nginxConfPath = "/etc/nginx/conf.d/gb28181-ssl.conf";

        /**
         * nginx 可执行文件路径。
         *
         * <p>用于 {@code nginx -t} 语法校验与 {@code nginx -s reload}。
         * 应用需有权限执行它（通常意味着以 root 运行）。
         */
        private String nginxBin = "nginx";

        /**
         * 是否允许本应用自动 reload nginx。
         *
         * <p><b>默认为 true，但这是一个提权面</b>：应用能以 root 身份执行
         * {@code nginx -s reload}。若不允许，上传证书后只生成配置并提示运维手工
         * reload（界面会明确指出命令）。高安全要求的环境建议关掉。
         *
         * <p>注意：无论此项为何，{@code nginx -t} 失败时都<b>不会</b> reload ——
         * 一份坏配置 reload 会让整站（含录像直读）全部不可用。
         */
        private boolean autoReload = true;

        /**
         * HTTPS 监听端口。默认 443。
         *
         * <p>与 HTTP 端口是两套独立监听：HTTP 保持可用（内网/老客户端），
         * 由前端决定是否跳转。
         */
        private int port = 443;
    }

    /** HTTPS 配置。 */
    private Tls tls = new Tls();
}
