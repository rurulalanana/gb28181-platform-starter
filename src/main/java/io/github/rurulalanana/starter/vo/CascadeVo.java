package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 级联配置信息。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CascadeVo {

    private String id;
    private String name;

    /** 上级平台国标编号。 */
    private String serial;

    /** 上级平台域。 */
    private String realm;

    private String host;
    private Integer port;

    /** 本级对外编号。 */
    private String localSerial;
    private String localHost;
    private Integer localPort;

    private String username;
    private String password;

    private Integer keepaliveMaxCount;
    private Integer keepaliveInterval;
    private Integer registerInterval;
    private Integer registerTimeout;
    private Integer controlPriority;
    private Integer loadLimit;
    private Integer catalogGroupSize;

    /** 信令传输模式。 */
    private String commandTransport;

    private String charset;

    /** 是否已注册到上级。 */
    private Boolean online;

    private Boolean allowControl;
    private Boolean shareAllChannel;
    private Boolean streamKeepalive;
    private Boolean enable;

    private String remark;

    /**
     * 上行推流传输方式：{@code auto}（跟随上级 SDP）/ {@code tcp} / {@code udp}。
     *
     * <p>遇到「上级说 UDP 但收不到流」时改成 {@code tcp}。
     */
    private String mediaTransport;
    private String createdAt;
    private String updatedAt;
}
