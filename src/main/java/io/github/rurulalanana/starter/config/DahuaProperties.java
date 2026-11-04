package io.github.rurulalanana.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 大华 NetSDK 直连接入配置（{@code dahua.*}）。
 *
 * <h3>为什么是顶层 {@code dahua} 而不是 {@code gb28181.dahua}</h3>
 * 大华 SDK 直连是<b>与国标并列的第二条接入通道</b>，不是国标的一部分 ——
 * 它不经过 SIP、不用 MANSCDP、不需要平台 20 位编码。
 * 把它挂在 {@code gb28181} 前缀下会暗示「这是国标的一个子项」，
 * 语义上是错的，也会让人误以为改它会影响国标链路。
 *
 * <p>同理，将来接海康、宇视时应当是并列的 {@code hikvision.*} /
 * {@code uniview.*}，而不是继续往 {@code gb28181} 里塞。
 *
 * <h3>边界</h3>
 * 本组配置<b>只被大华接入实现读取</b>。整段删掉，平台仍能正常跑国标；
 * 反之国标相关配置（{@code gb28181.sip-*} 等）也不影响大华设备。
 *
 * <p>与 {@link Gb28181Properties} 分开还有一个实际好处：后者已经有
 * 300 多行、20 多个配置项，把厂商私有参数混进去会让「哪些是国标标准、
 * 哪些是厂商扩展」彻底看不清。
 */
@Data
@ConfigurationProperties(prefix = "dahua")
public class DahuaProperties {

    /**
     * 是否启用大华接入。
     *
     * <p>关闭后 {@code DahuaDeviceAccessProvider} 仍会被注册（否则
     * {@code DeviceAccessRegistry} 无法区分「不支持该类型」和「支持但被关掉」），
     * 但所有操作会明确报「已禁用」，而不是去尝试加载原生库。
     *
     * <p>默认开启：原生库缺失时本来就会自动降级，不需要靠这个开关兜底。
     * 关掉它的场景是「确定不用大华，想彻底不碰 SDK」。
     */
    private boolean enabled = true;

    /**
     * 设备 RTSP 端口（大华默认 554）。
     *
     * <p>预览与回放走 RTSP 而不是 SDK 取码流：这样复用现有的
     * FLV/WS-FLV/HLS/WebRTC 输出与前端播放器，平台侧不用自己解 PS 流。
     * 代价是需要设备开启 RTSP（大华默认开启）。
     *
     * <p>这也是「SDK 原生库加载失败时仍能出画面」的原因 ——
     * 只要知道设备地址，拉流不依赖 SDK。
     */
    private int rtspPort = 554;

    /**
     * 设备 Web（HTTP CGI）端口，默认 80。
     *
     * <p><b>为什么需要它</b>：部分型号的<b>场景数组</b>类配置项
     * （{@code VideoColor} / {@code VideoInSharpness} / {@code VideoInExposure} …）
     * 通过 {@code CLIENT_SetNewDevConfig} 写不进去 —— 设备一律返回 {@code err=12}，
     * 连原样写回都被拒；而设备自己的 Web 界面走
     * {@code /cgi-bin/configManager.cgi?action=setConfig} 改得好好的。
     *
     * <p>所以参数写入在 SDK 被拒时会改走 CGI（见 {@code DahuaCgiAccess}）。
     * 该端口与 SDK 端口（37777）和 RTSP 端口（554）是三个不同的东西。
     * 设备改了 Web 端口时改这里。
     */
    private int httpPort = 80;

    /**
     * 单台设备的通道总数。
     *
     * <p><b>为什么需要它</b>：大华的 RTSP 把子码流排在主码流之后
     * （4 通道设备：主码流 1~4、子码流 5~8）。要推算子码流的通道号
     * 就必须知道总数。
     *
     * <p>填 0 表示「按设备上报的通道数」，这是推荐值 ——
     * 设备登录时会在 {@code NET_DEVICEINFO_Ex.byChanNum} 里给出。
     * 只有在设备不上报（返回 0）时才需要手工指定。
     */
    private int channelTotal = 0;

    /** 设备主动注册（SERVER 模式）配置。 */
    private ServerMode serverMode = new ServerMode();

    /**
     * 设备主动注册配置。
     *
     * <p>适用于设备 IP 不固定（4G、动态 IP、NAT 后）的场景：
     * 平台开监听端口，设备主动连上来，平台从回调里拿到实时地址。
     *
     * <p>与国标的注册机制完全不同：国标是设备向平台发 SIP REGISTER，
     * 这里是设备向平台的 TCP 端口上报序列号，再由平台反向登录。
     * <b>两者不要混为一谈</b> —— 这也是本配置独立于 {@code gb28181} 的理由之一。
     */
    @Data
    public static class ServerMode {

        /**
         * 是否开启监听。
         *
         * <p><b>默认关闭</b>：它要占用一个端口，且必须在设备侧
         * 「网络 → 主动注册」里配置指向本平台才有意义。
         * 默认打开会让每次启动都多一个可能失败的点。
         */
        private boolean enabled = false;

        /** 绑定地址。{@code 0.0.0.0} 表示所有网卡（公网部署推荐）。 */
        private String bindIp = "0.0.0.0";

        /**
         * 监听端口，设备侧主动注册要填这个。
         *
         * <p>默认 9500：大华 SDK 示例与多数现场笔记用的是 9500/9502，
         * 取 9500 与厂商 AutoRegister 示例一致。
         */
        private int port = 9500;

        /**
         * 注册记录失效阈值（秒）。
         *
         * <p>设备断电/断网时不会发「我走了」，只能靠超时判定。
         * 大华的注册心跳通常是 30~60 秒，取 3 倍留出抖动余量。
         *
         * <p>太小会让在线设备被误判离线（表现为「设备明明在线却连不上」），
         * 太大则僵尸地址会拖慢巡检（每次都要等满登录超时）。
         */
        private int expireSeconds = 180;

        /**
         * 注册上来的设备是否<b>自动建档案进设备表</b>。
         *
         * <p>默认开启：设备主动注册的目的就是「不用人工录地址」，
         * 如果注册完还要人手填一遍 IP/端口，这个模式就失去了意义。
         *
         * <p>关掉它的场景：现场设备多、只想让白名单里的设备入表（安全要求高的机房），
         * 这时注册记录只出现在注册表里，由人工确认后再纳管。
         */
        private boolean autoProvision = true;

        /**
         * 自动建档案时使用的设备账号。
         *
         * <p>注册回调只带序列号与地址，<b>不带凭据</b>，所以必须有一份
         * 「本平台在大华设备上用的只读/操作账号」作为默认值。
         * 现场若按设备分别授权，注册后可在设备页单独改。
         */
        private String defaultUsername = "admin";

        /**
         * 自动建档案时使用的设备密码。
         *
         * <p><b>不要写进 application.yml</b>（会被打进 jar 并提交到仓库），
         * 用环境变量注入，见 yml 里的 {@code GB_DAHUA_PASSWORD}。
         * 为空时自动建档案会被跳过，只打一条告警 —— 不静默失败。
         */
        private String defaultPassword = "";

        /**
         * 设备 SDK 端口（不是注册回调里的端口）。
         *
         * <p><b>为什么不能用回调里的端口</b>：大华注册回调给的是设备的
         * <b>TCP 源端口</b>（每次重连都变，实测 54536 / 54612 / 55636…），
         * 拿它当连接目标必然失败。SDK 端口是设备侧固定配置，默认 37777。
         */
        private int sdkPort = 37777;
    }

    /**
     * 设备主动上传抓图的接收配置。
     *
     * <p><b>为什么需要它</b>：默认的抓图是「平台向设备要图」，每要一次就要建立
     * 一次会话往返。而报警联动抓图（人形/越界触发）是设备<b>主动</b>产生的 ——
     * 让设备把图直接 POST 上来，平台既不用轮询也不用拉流解码，
     * 这是流量最低、延迟最小的形态。
     *
     * <p>设备侧需要配置 {@code NET_EM_CFG_PICTURE_HTTP_UPLOAD(1609)}，
     * 填写本平台的接收地址。不同型号的配置入口不一样（网页 / ConfigTool / SDK 写配置）。
     */
    @Data
    public static class SnapUpload {

        /**
         * 是否开启接收端点。
         *
         * <p>默认关闭：它是对外开放的写入口（设备要能 POST 进来），
         * 没配令牌就开等于给了任何人一个写文件的口子。
         */
        private boolean enabled = false;

        /**
         * 上传令牌。设备侧地址里带 {@code ?token=xxx}。
         *
         * <p><b>不要写进 application.yml</b>，用环境变量 {@code GB_SNAP_TOKEN} 注入。
         * 设备不支持自定义 HTTP 头，只能把令牌放 URL 里 —— 这是设备侧的限制，
         * 所以令牌要足够长（建议 32 位随机），并且只授予「写图片」这一项能力。
         */
        private String token = "";

        /**
         * 单张图片大小上限（字节）。
         *
         * <p>默认 8 MB：4K JPEG 通常 1~2 MB，留足余量；
         * 但不设上限的话，一个错误配置或恶意请求就能把磁盘写满。
         */
        private long maxBytes = 8L * 1024 * 1024;
    }

    /** 抓图直传接收配置。 */
    private SnapUpload snapUpload = new SnapUpload();
}
