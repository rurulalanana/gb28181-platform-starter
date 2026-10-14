package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：会话摘要（{@code GET /api/sessions}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeSessionItemVo {

    /**
     * 设备编码（= ZLM app）。
     *
     * <p>必须与 {@link #channelId} 一起看：通道编码只在单设备内唯一，
     * 跨设备可重复，只看通道编码无法区分两台设备的同号通道。
     */
    private String deviceId;

    /** 通道编码（= ZLM stream）。 */
    private String channelId;

    /** 复合标识 {@code <设备编码>/<通道编码>}，与 ZLM 媒体源路径一致。 */
    private String streamId;

    private String type;
    private String state;
    private Integer port;
    private String ssrc;
    private String mediaUri;
    private String callId;
}
