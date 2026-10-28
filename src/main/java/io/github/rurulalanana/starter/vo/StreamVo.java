package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 直播流信息。
 *
 * <p>由 {@code StreamService#describe} 组装，是 {@code stream/start}、{@code stream/info}、
 * {@code stream/list} 以及通道信息里播放地址的统一出参。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamVo {

    /** 流 ID（ZLM 流名，形如 {@code <通道编码>/0}）。 */
    private String streamId;

    /** 所属设备编号。 */
    private String deviceId;

    /** 通道编号。 */
    private String channelId;

    /** 平台 SIP 编号。 */
    private String smsId;

    /** 媒体流 SSRC。 */
    private String ssrc;

    /** 收流端口。 */
    private Integer rtpPort;

    /** 会话状态：{@code NEGOTIATING} / {@code ONLINE} / {@code CLOSED}。 */
    private String state;

    /** 流传输模式：{@code UDP} / {@code TCP}。 */
    private String transport;

    /** WebRTC 播放地址（嵌入式模式下唯一可用的浏览器通路）。 */
    private String webrtc;

    /** HTTP-FLV 地址。 */
    private String flv;

    /** WebSocket-FLV 地址。 */
    private String wsFlv;

    /** HLS 地址。 */
    private String hls;

    /** RTSP 地址。 */
    private String rtsp;

    /** RTMP 地址。 */
    private String rtmp;

    /** 通道展示名。 */
    private String channelName;

    /** 通道云台类型。 */
    private Integer channelPtzType;

    /** 设备展示名。 */
    private String deviceName;

    /** 开始时间（{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String startAt;

    /** 已持续秒数。 */
    private Long duration;

    /** SIP Call-ID。 */
    private String callId;

    /** 回放 / 下载时对应的录像文件路径。 */
    private String filePath;

    /**
     * 当前观众数（来自 ZLM 的读者计数，含录制器）。
     *
     * <p>运维排障时最常问的两个问题就是「这路到底有没有人在看」与
     * 「能不能关」—— 把它暴露出来，看列表就知道，不必再去翻日志。
     *
     * <p>统计的是 <b>ZLM 侧</b>的读者：ws-flv / http-flv / hls /
     * webrtc 等所有协议加录制器。实测对 ws-flv 是准确的
     * （连着时 1、断开后 0），因此它就是「有没有人看」的权威来源。
     */
    private Integer readers;
}
