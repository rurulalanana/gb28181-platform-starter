package io.github.rurulalanana.starter.common;

import java.math.BigDecimal;

/**
 * 宽容的数字解析：解析失败返回 {@code null}，不抛异常。
 *
 * <p><b>为什么需要</b>：SIP 报文（MANSCDP）与对外查询接口里的数值字段全是字符串，
 * 现场设备会给出空串、{@code "null"}、带单位的值甚至乱码。
 * 这类字段属于「有就用、没有就算了」的附加信息，解析失败不该让整条报文处理失败 ——
 * 结果就是每个用到的地方都写一遍 {@code try/catch NumberFormatException}。
 * 同一段逻辑曾经在 4 个类里各存一份（DeviceService / SipEventBridge /
 * AlarmService / DeviceController），收敛到这里。
 *
 * <p><b>与 {@code Integer.valueOf} 的区别</b>：那三个方法在入参非法时抛
 * {@link NumberFormatException}，调用方必须自己包 try/catch；本类全部返回
 * {@code null}，调用方只需判空。
 */
public final class Numbers {

    private Numbers() {
    }

    /** 解析为整数；空白或非法格式返回 {@code null}。 */
    public static Integer intOrNull(String text) {
        String v = trimToNull(text);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析为浮点数；空白或非法格式返回 {@code null}。 */
    public static Double doubleOrNull(String text) {
        String v = trimToNull(text);
        if (v == null) {
            return null;
        }
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析为精确小数；空白或非法格式返回 {@code null}。 */
    public static BigDecimal decimalOrNull(String text) {
        String v = trimToNull(text);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 去掉首尾空白；空串或全空白返回 {@code null}。 */
    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String v = text.trim();
        return v.isEmpty() ? null : v;
    }
}
