package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 平台运行信息（{@code getserverinfo}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServerInfoVo {

    private String server;
    private String interfaceVersion;

    /** 是否需要鉴权。 */
    private Boolean apiAuth;

    private String serverTime;
    private String startUpTime;

    /** 运行时长（人类可读）。 */
    private String runningTime;

    private Long deviceTotal;
    private Long deviceOnline;
    private Long channelTotal;
    private Long channelOnline;
    private Long channelCount;
    private Integer streamCount;

    private String platformId;

    /** 优先播放格式。 */
    private String preferStreamFmt;

    private String versionType;
    private String copyrightText;

    /** SIP 监听地址。 */
    private String sip;

    private Integer mediaHttp;
    private Integer mediaRtsp;
    private Integer mediaRtmp;
    private Integer mediaWebRtc;

    /** 硬件信息。 */
    private String hardware;
}
