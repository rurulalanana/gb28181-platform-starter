package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：平台状态（{@code GET /api/status}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeStatusVo {

    private String platformId;
    private String sip;
    private String mediaIp;
    private Integer http;
    private Integer rtmp;
    private Integer rtsp;
    private Integer webrtc;
    private Integer sessionCount;
    private Integer recordCount;
    private String note;
}
