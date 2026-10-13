package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：会话摘要（{@code GET /api/sessions}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeSessionItemVo {

    private String channelId;
    private String type;
    private String state;
    private Integer port;
    private String ssrc;
    private String mediaUri;
    private String callId;
}
