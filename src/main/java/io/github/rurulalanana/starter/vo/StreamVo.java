package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 直播流信息。由 {@code StreamService#describe} 组装，是 {@code stream/start}、{@code stream/info}、
 * {@code stream/list} 与通道信息里播放地址的统一出参。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamVo {

    /** 流 ID（ZLM 流名，形如 {@code <通道编码>/0}）。 */
    private String streamId;

    private String deviceId;

    private String channelId;

    private String smsId;

    /** 媒体流 SSRC（GB/T 28181 附录 G，域标识 + 流标识）。 */
    private String ssrc;

    private Integer rtpPort;

    /** 会话状态：{@code NEGOTIATING} / {@code ONLINE} / {@code CLOSED}。 */
    private String state;

    /** 流传输模式：{@code UDP} / {@code TCP}。 */
    private String transport;

    /** WebRTC 播放地址（嵌入式模式下唯一可用的浏览器通路）。 */
    private String webrtc;

    private String flv;

    private String wsFlv;

    private String hls;

    private String rtsp;

    private String rtmp;

    private String channelName;

    private Integer channelPtzType;

    private String deviceName;

    /** 开始时间（{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String startAt;

    private Long duration;

    /** SIP Call-ID。 */
    private String callId;

    /** 回放 / 下载时对应的录像文件路径。 */
    private String filePath;

    /**
     * 当前观众数（来自 ZLM 读者计数，含录制器）。实测对 ws-flv 准确（连着时 1、断开后 0），
     * 是「这路到底有没有人在看 / 能不能关」的权威来源，看列表即知，不必翻日志。
     */
    private Integer readers;
}
