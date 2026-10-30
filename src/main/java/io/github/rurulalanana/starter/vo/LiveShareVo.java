package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 直播分享页的通道信息与播放地址。
 *
 * <p>只暴露「看这一路直播」所需的最小字段，不复用 {@link StreamVo} ——
 * 那个出参带着 SSRC、callId、端口等信令内部状态，分享页是<b>免登录</b>的，
 * 没有必要也不该把这些细节暴露给任何拿到链接的人。
 *
 * <p>字段与前端 {@code LiveShareView.vue} 一一对应。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LiveShareVo {

    /** 设备编码。 */
    private String deviceId;

    /** 通道编码。 */
    private String channelId;

    /**
     * 展示用名称。
     *
     * <p>优先取通道的自定义名（{@code customName}），其次设备目录里的原始名，
     * 都没有时退回通道编码 —— 分享页顶部要有一个能认人的标题，
     * 只显示一串 20 位编码等于没有标题。
     */
    private String name;

    /** 设备是否在线。离线时页面直接提示，不必白等一次 INVITE 超时。 */
    private Boolean deviceOnline;

    /** 通道状态（{@code ON} / {@code OFF}）。 */
    private String status;

    /** 流是否已就绪（{@code ONLINE} 表示设备已在推流）。 */
    private Boolean streaming;

    /** WebSocket-FLV 地址。 */
    private String wsFlv;

    /** HTTP-FLV 地址。 */
    private String flv;

    /** HLS 地址（兜底协议）。 */
    private String hls;

    /** RTMP 地址。 */
    private String rtmp;

    /** RTSP 地址。 */
    private String rtsp;

    /**
     * 业务提示（通道不存在、设备离线、点播被拒等）。
     *
     * <p>与错误码不同：这些是「页面能打开、但放不出来」的情形，
     * 前端据此在播放区显示具体原因，而不是给一块黑屏。
     */
    private String notice;
}
