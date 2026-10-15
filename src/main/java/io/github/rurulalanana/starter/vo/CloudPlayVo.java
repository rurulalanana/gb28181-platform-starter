package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 云端录像点播出参。
 *
 * <p>录像文件被拉入 ZLMediaKit 转协议输出，返回各协议播放地址。
 * 嵌入式 ZLMediaKit 未注册 HTTP 播放路由，浏览器出画面请用 {@link #webrtc}。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudPlayVo {

    /** 录像 ID。 */
    private String recordId;

    /** 通道编码。 */
    private String channelId;

    /** 文件名。 */
    private String fileName;

    /** 录像开始时间（{@code yyyyMMddHHmmss}）。 */
    private String startAt;

    /** 时长（秒）。 */
    private Integer duration;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** WebRTC 播放地址（延迟最低，需浏览器与平台网络可直连）。 */
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

    /** 下载地址（对象存储为签名/外链，本地为平台接口）。 */
    private String downloadUrl;

    /** 说明。 */
    private String note;
}
