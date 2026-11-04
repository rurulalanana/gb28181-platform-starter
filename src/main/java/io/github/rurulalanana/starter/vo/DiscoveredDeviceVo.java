package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 局域网发现到的一台设备。
 *
 * <p>刻意<b>不返回密码</b>也不需要密码：发现只能拿到地址与型号，
 * 账号凭据必须由用户填写，平台不会去猜。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiscoveredDeviceVo {

    /** 设备序列号。与主动注册回调里的 SN 是同一个值，可直接作为设备编号。 */
    private String serial;

    private String ip;

    /** SDK 端口，正常是 37777。 */
    private Integer sdkPort;

    /** Web 端口，正常是 80。 */
    private Integer httpPort;

    private String model;

    private String firmware;

    /** 视频输入通道数。 */
    private Integer channels;

    /**
     * 平台是否已纳管该 SN。
     *
     * <p>发现列表里混着已纳管设备是常态（同一个网段反复搜索），
     * 前端据此把「添加」按钮变成「已添加」，避免用户重复录入。
     */
    private Boolean managed;
}
