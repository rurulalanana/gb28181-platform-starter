package io.github.rurulalanana.starter.dto;

import lombok.Data;

/**
 * 设备 / 通道定位（GBS 绝大多数接口都用 {@code serial} + {@code code} 定位一路通道）。
 *
 * <p>对于直连摄像机（通道编号等于设备编号），{@code code} 可省略。
 */
@Data
public class DeviceRef {

    /** 设备国标编号。 */
    private String serial;

    /** 通道国标编号。 */
    private String code;

    /** 解析出的通道编号：{@code code} 为空时回退到 {@code serial}。 */
    public String resolveCode() {
        return code == null || code.isBlank() ? serial : code;
    }
}
