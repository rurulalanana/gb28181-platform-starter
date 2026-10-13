package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：WebRTC 交换结果（{@code POST /api/webrtc/play}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeWebrtcVo {

    private Integer code;
    private String peerId;
    private String sdp;
    private String webrtcUrl;
}
