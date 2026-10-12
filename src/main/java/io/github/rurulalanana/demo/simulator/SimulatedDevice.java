package io.github.rurulalanana.demo.simulator;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内置 GB28181 设备模拟器（裸 SIP over UDP）。
 *
 * <p>让平台在没有真实摄像头的情况下也能验证完整链路：
 * <pre>
 * 模拟设备 ──REGISTER──▶ 平台（登记上线）
 * 模拟设备 ◀──INVITE──── 平台点播（client.invitePlay）
 * 模拟设备 ──200 OK──▶ 平台（携带设备侧 SDP）
 * 模拟设备 ──RTP─────▶ 平台 RTP 服务器（ffmpeg 造测试画面，可选）
 * </pre>
 *
 * <p>媒体说明：ffmpeg 推的是<b>裸 H264 RTP（负载类型 98）</b>。ZLMediaKit 的
 * {@code [rtp_proxy]} 段默认 {@code ps_pt=96 / h264_pt=98}，因此该流会被识别为
 * ES/H264 而非国标 PS——真机测试时设备推的是 PT=96 的 PS 流，无需任何配置。
 */
@Slf4j
public class SimulatedDevice {

    private static final String CRLF = "\r\n";

    @Getter
    private final String deviceId;
    @Getter
    private final String name;
    @Getter
    private volatile String address;

    private final DatagramSocket socket;
    private final String fromTag = "tag" + Math.abs(System.nanoTime() % 1_000_000);
    private final AtomicInteger cseq = new AtomicInteger();
    private final Map<String, Dialog> dialogs = new ConcurrentHashMap<>();
    private final AtomicInteger received = new AtomicInteger();

    @Getter
    private volatile String lastRequest;
    @Getter
    private volatile boolean registered;

    /** 媒体推送配置。 */
    private volatile MediaConfig mediaConfig;

    private Thread receiverThread;
    private volatile boolean running = true;

    public SimulatedDevice(String deviceId, String name) throws IOException {
        this.deviceId = deviceId;
        this.name = name;
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(1000);
        this.address = "127.0.0.1:" + socket.getLocalPort();
    }

    /** 媒体推送配置（可后置设置）。 */
    public record MediaConfig(boolean enabled, String ffmpeg, String source) {
    }

    private static class Dialog {
        String callId;
        String toTag;
        int rtpPort = -1;
        Process mediaProcess;
        boolean sendingMedia;
    }

    // ==================== 生命周期 ====================

    /** 启动接收线程并向平台注册。 */
    public void start(String platformIp, int platformPort) {
        receiverThread = new Thread(this::receiveLoop, "sim-device-" + deviceId);
        receiverThread.setDaemon(true);
        receiverThread.start();
        register(platformIp, platformPort);
    }

    /** 向平台发送 REGISTER。 */
    public synchronized void register(String platformIp, int platformPort) {
        try {
            String msg = "REGISTER sip:" + platformIp + "@0.0.0.0 SIP/2.0" + CRLF
                    + via()
                    + "From: <sip:" + deviceId + "@3402000000>;tag=" + fromTag + CRLF
                    + "To: <sip:" + deviceId + "@3402000000>" + CRLF
                    + "Call-ID: " + newCallId("reg") + CRLF
                    + "CSeq: " + cseq.incrementAndGet() + " REGISTER" + CRLF
                    + "Contact: <sip:" + deviceId + "@127.0.0.1:" + socket.getLocalPort() + ">" + CRLF
                    + "Expires: 3600" + CRLF
                    + "Max-Forwards: 70" + CRLF
                    + "Content-Length: 0" + CRLF + CRLF;
            send(msg, platformIp, platformPort);
            registered = true;
            log.info("[模拟设备 {}] 已向 {}:{} 发送 REGISTER", deviceId, platformIp, platformPort);
        } catch (IOException e) {
            log.warn("[模拟设备 {}] 注册失败: {}", deviceId, e.toString());
        }
    }

    /** 发送心跳。 */
    public void keepalive(String platformIp, int platformPort) {
        try {
            String xml = "<?xml version=\"1.0\"?>\r\n"
                    + "<Notify>\r\n<CmdType>Keepalive</CmdType>\r\n<SN>" + cseq.get() + "</SN>\r\n"
                    + "<DeviceID>" + deviceId + "</DeviceID>\r\n"
                    + "<Status>OK</Status>\r\n<Info></Info>\r\n</Notify>";
            String msg = "MESSAGE sip:" + deviceId + "@3402000000 SIP/2.0" + CRLF
                    + via()
                    + "From: <sip:" + deviceId + "@3402000000>;tag=" + fromTag + CRLF
                    + "To: <sip:" + deviceId + "@3402000000>" + CRLF
                    + "Call-ID: " + newCallId("ka") + CRLF
                    + "CSeq: " + cseq.incrementAndGet() + " MESSAGE" + CRLF
                    + "Content-Type: Application/MANSCDP+xml" + CRLF
                    + "Max-Forwards: 70" + CRLF
                    + "Content-Length: " + xml.getBytes(StandardCharsets.UTF_8).length + CRLF + CRLF
                    + xml;
            send(msg, platformIp, platformPort);
        } catch (IOException e) {
            log.debug("心跳失败: {}", e.toString());
        }
    }

    public void setMediaConfig(MediaConfig config) {
        this.mediaConfig = config;
    }

    /** 停止设备：结束所有会话、杀掉 ffmpeg。 */
    public void stop() {
        running = false;
        for (Dialog d : dialogs.values()) {
            stopMedia(d);
        }
        dialogs.clear();
        socket.close();
        log.info("[模拟设备 {}] 已停止", deviceId);
    }

    /** 当前媒体推送进程数（用于测试页显示）。 */
    public boolean isPushing() {
        for (Dialog d : dialogs.values()) {
            if (d.mediaProcess != null && d.mediaProcess.isAlive()) {
                return true;
            }
        }
        return false;
    }

    public int getReceivedRequests() {
        return received.get();
    }

    // ==================== 收报文 ====================

    private void receiveLoop() {
        byte[] buf = new byte[8192];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                if (log.isDebugEnabled()) {
                    log.debug("[模拟设备 {}] 收到 {} 字节来自 {}:{} :: {}", deviceId,
                            packet.getLength(), packet.getAddress().getHostAddress(), packet.getPort(),
                            text.length() > 80 ? text.substring(0, 80).replace("\r\n", " | ") : text);
                }
                handle(text, packet.getAddress().getHostAddress(), packet.getPort());
            } catch (java.net.SocketTimeoutException ignore) {
                // 正常轮询
            } catch (IOException e) {
                if (running) {
                    log.debug("[模拟设备 {}] 接收异常: {}", deviceId, e.toString());
                }
            }
        }
    }

    private void handle(String msg, String fromIp, int fromPort) {
        if (msg == null || msg.isEmpty()) {
            return;
        }
        String firstLine = msg.split(CRLF)[0];
        // 响应以 "SIP/2.0 " 开头，请求以方法名开头（INVITE/BYE/...）
        if (firstLine.startsWith("SIP/2.0 ")) {
            received.incrementAndGet();
            lastRequest = firstLine;
            log.info("[模拟设备 {}] 收到响应 {} from {}:{}", deviceId, firstLine, fromIp, fromPort);
            return;
        }
        String method = firstToken(firstLine); // INVITE / ACK / BYE / OPTIONS
        received.incrementAndGet();
        lastRequest = firstLine;
        String callId = header(msg, "Call-ID");
        log.info("[模拟设备 {}] 收到 {} from {}:{}", deviceId, lastRequest, fromIp, fromPort);

        switch (method) {
            case "INVITE":
                try {
                    handleInvite(msg, fromIp, fromPort, callId);
                } catch (IOException e) {
                    log.warn("[模拟设备 {}] 处理 INVITE 失败: {}", deviceId, e.toString());
                }
                break;
            case "BYE":
                reply200(msg, fromIp, fromPort, callId, "");
                Dialog d = dialogs.remove(callId);
                if (d != null) {
                    stopMedia(d);
                }
                break;
            case "OPTIONS":
                reply200(msg, fromIp, fromPort, callId, "");
                break;
            case "ACK":
                log.debug("[模拟设备 {}] ACK", deviceId);
                break;
            default:
                break;
        }
    }

    /** 处理平台发来的 INVITE（点播/回放/下载）：回 200 OK + 设备侧 SDP，并按需推流。 */
    private void handleInvite(String msg, String fromIp, int fromPort, String callId) throws IOException {
        String body = msg.contains(CRLF + CRLF) ? msg.substring(msg.indexOf(CRLF + CRLF) + 4) : "";
        int platformRtpPort = parseVideoPort(body);
        String sessionName = parseField(body, "s=", "\r\n");
        String mediaUri = parseField(body, "u=", "\r\n");

        Dialog dialog = new Dialog();
        dialog.callId = callId;
        dialog.rtpPort = platformRtpPort;
        dialogs.put(callId, dialog);
        log.info("[模拟设备 {}] 会话建立 callId={} 平台收流端口={} s={} u={}",
                deviceId, callId, platformRtpPort, sessionName, mediaUri);

        // 设备侧 SDP：声明自己的媒体地址与 SSRC
        String sdp = "v=0" + CRLF
                + "o=" + deviceId + " 0 0 IN IP4 127.0.0.1" + CRLF
                + "s=" + (sessionName == null || sessionName.isEmpty() ? "Play" : sessionName) + CRLF
                + "c=IN IP4 127.0.0.1" + CRLF
                + "t=0 0" + CRLF
                + "m=video " + socket.getLocalPort() + " RTP/AVP 96" + CRLF
                + "a=rtpmap:96 PS/90000" + CRLF
                + "y=" + ssrcOf(sessionName) + CRLF;

        reply200(msg, fromIp, fromPort, callId, sdp);

        // 平台主动点播后，模拟设备向平台收流端口推测试画面
        if (mediaConfig != null && mediaConfig.enabled() && platformRtpPort > 0) {
            startMedia(dialog, platformRtpPort);
        }
    }

    /** 用 ffmpeg 生成测试画面并以裸 H264 RTP 推到平台。 */
    private void startMedia(Dialog dialog, int rtpPort) {
        if (dialog.mediaProcess != null) {
            return;
        }
        MediaConfig cfg = mediaConfig;
        String[] cmd = {cfg.ffmpeg(), "-hide_banner", "-loglevel", "error", "-re",
                "-f", "lavfi", "-i", cfg.source(),
                "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-pix_fmt", "yuv420p", "-g", "25", "-payload_type", "98",
                "-f", "rtp", "rtp://127.0.0.1:" + rtpPort};
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            dialog.mediaProcess = p;
            dialog.sendingMedia = true;
            log.info("[模拟设备 {}] ffmpeg 推流中 → 127.0.0.1:{}（{}）", deviceId, rtpPort, cfg.source());
            // 读取 stderr 便于排障
            Thread drain = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] b = new byte[512];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        String s = new String(b, 0, n).trim();
                        if (!s.isEmpty()) {
                            log.warn("[ffmpeg {}] {}", deviceId, s);
                        }
                    }
                } catch (IOException ignore) {
                    // 进程结束时忽略
                }
                if (!p.isAlive()) {
                    log.warn("[ffmpeg {}] 进程已退出，退出码={}（检查 ffmpeg 是否可用 / 编码器 libx264）",
                            deviceId, p.exitValue());
                }
            });
            drain.setDaemon(true);
            drain.start();
        } catch (IOException e) {
            log.warn("[模拟设备 {}] ffmpeg 启动失败（是否已安装 ffmpeg？）: {}", deviceId, e.toString());
        }
    }

    private void stopMedia(Dialog dialog) {
        if (dialog.mediaProcess != null && dialog.mediaProcess.isAlive()) {
            dialog.mediaProcess.destroyForcibly();
            log.info("[模拟设备 {}] ffmpeg 推流已停止", deviceId);
        }
        dialog.mediaProcess = null;
        dialog.sendingMedia = false;
    }

    // ==================== 发报文 ====================

    private void reply200(String request, String ip, int port, String callId, String sdp) {
        try {
            String toHeader = header(request, "To");
            String toWithTag = toHeader.contains(";tag=") ? toHeader : toHeader + ";tag=srv" + Math.abs(callId.hashCode() % 100000);
            String cseq = header(request, "CSeq");
            String body = sdp == null ? "" : sdp;
            String msg = "SIP/2.0 200 OK" + CRLF
                    + "Via: " + header(request, "Via").split(",")[0] + CRLF
                    + "From: " + header(request, "From") + CRLF
                    + "To: " + toWithTag + CRLF
                    + "Call-ID: " + callId + CRLF
                    + "CSeq: " + cseq + CRLF
                    + (body.isEmpty() ? "" : "Content-Type: application/sdp" + CRLF)
                    + "Server: gb28181-simulator" + CRLF
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + CRLF + CRLF
                    + body;
            send(msg, ip, port);
            Dialog d = dialogs.get(callId);
            if (d != null && toWithTag.contains(";tag=")) {
                d.toTag = toWithTag.substring(toWithTag.indexOf(";tag=") + 5);
            }
        } catch (IOException e) {
            log.warn("[模拟设备 {}] 应答失败: {}", deviceId, e.toString());
        }
    }

    private void send(String msg, String ip, int port) throws IOException {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(ip), port));
    }

    private String via() {
        return "Via: SIP/2.0/UDP 127.0.0.1:" + socket.getLocalPort()
                + ";branch=z9hG4bK-" + System.nanoTime() + CRLF;
    }

    private String newCallId(String prefix) {
        return prefix + "-" + System.nanoTime() + "@127.0.0.1";
    }

    // ==================== 解析工具 ====================

    private static String firstToken(String line) {
        int sp = line.indexOf(' ');
        return sp > 0 ? line.substring(0, sp) : line;
    }

    private static String header(String msg, String name) {
        for (String line : msg.split(CRLF)) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return "";
    }

    private static int parseVideoPort(String sdp) {
        for (String line : sdp.split(CRLF)) {
            if (line.startsWith("m=video ")) {
                String[] parts = line.split(" ");
                try {
                    return Integer.parseInt(parts[1]);
                } catch (Exception ignore) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String parseField(String text, String prefix, String suffix) {
        if (text == null) {
            return null;
        }
        for (String line : text.split(CRLF)) {
            if (line.startsWith(prefix)) {
                int end = line.indexOf(suffix, prefix.length());
                return end > 0 ? line.substring(prefix.length(), end) : line.substring(prefix.length());
            }
        }
        return null;
    }

    /** 设备侧 SSRC：实时首位 0，历史首位 1（GB/T 28181 附录 G）。 */
    private String ssrcOf(String sessionName) {
        boolean historical = sessionName != null
                && (sessionName.toLowerCase().contains("playback") || sessionName.toLowerCase().contains("download"));
        String domain = deviceId.length() >= 8 ? deviceId.substring(3, 8) : deviceId;
        String seq = String.format("%04d", Math.floorMod(System.identityHashCode(this) % 9999, 9999));
        return (historical ? "1" : "0") + domain + seq;
    }
}