package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 通道快照（{@code cloudrecord/snap}）。
 *
 * <p>与 LiveGBS 的 {@code snap_url} 语义一致：给前端一个可直接放进
 * {@code <img src>} 的地址。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SnapVo {

    /** 设备编码。 */
    private String serial;

    /** 通道编码。 */
    private String code;

    /** 快照访问路径（相对平台 HTTP 根，如 {@code /api/v1/cloudrecord/snap/xx/yy.jpg}）。 */
    private String url;
}
