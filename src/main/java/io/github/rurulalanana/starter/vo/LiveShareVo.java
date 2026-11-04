package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 直播分享页的通道信息与播放地址。只暴露「看这一路直播」的最小字段，不复用 {@link StreamVo}：
 * 后者带 SSRC、callId、端口等信令内部状态，而分享页<b>免登录</b>，拿到链接的人不该看到这些。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LiveShareVo {

    /** 设备编码。 */
    private String deviceId;

    /** 通道编码。 */
    private String channelId;

    /** 展示名：平台自定义名 > 设备目录原始名 > 通道编码（顶部总得有个能认人的标题）。 */
    private String name;

    /** 设备是否在线。离线时页面直接提示，不必白等一次 INVITE 超时。 */
    private Boolean deviceOnline;

    /**
     * 云台类型：0 未知 / 1 球机 / 2 半球 / 3 固定枪机 / 4 遥控枪机。
     * 固定枪机不显示云台控制块 —— 物理上不能转，摆上浮层只会让人以为平台坏了。
     */
    private Integer ptzType;

    private String status;

    /** 流是否已就绪（{@code ONLINE} 表示设备已在推流）。 */
    private Boolean streaming;

    private String wsFlv;

    private String flv;

    /** HLS 地址（兜底协议）。 */
    private String hls;

    private String rtmp;

    private String rtsp;

    /**
     * 业务提示（通道不存在、设备离线、点播被拒等）。与错误码不同：这些是「页面能打开、但放不出来」，
     * 前端据此在播放区显示具体原因，而不是给一块黑屏。
     */
    private String notice;
}
