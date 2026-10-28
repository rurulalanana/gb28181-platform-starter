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

    /**
     * 「通道编码回退到设备编码」这条规则的<b>唯一定义</b>。
     *
     * <p><b>为什么不直接写三元表达式</b>：这条规则在 7 个文件里被抄了 28 遍。
     * 它是国标接口的公共契约（直连摄像机的通道编号就是设备编号，
     * 所以 {@code code} 缺省时必须回退），一处改了别处不改就是
     * 「同一次点播，直播能起来、回放报设备未注册」这类难查的差异。
     *
     * @param serial 设备编码
     * @param code   通道编码，可为空
     * @return 实际使用的通道编码
     */
    public static String channelOf(String serial, String code) {
        return code == null || code.isBlank() ? serial : code;
    }
}
