package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：WebRTC 参数（{@code GET /api/webrtc/config}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeWebrtcConfigVo {

    private Integer webrtcPort;
    private String app;
    private String note;
}
