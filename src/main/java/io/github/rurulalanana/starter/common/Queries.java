package io.github.rurulalanana.starter.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import java.time.LocalDateTime;

/**
 * 查询条件拼装的公共片段：只覆盖「时间区间」与「设备/通道」两类可选项，更复杂的组合留在调用方。
 * 报警/状态日志/位置日志的这段条件逐行相同，抄三份的真正代价不是行数而是口径走偏：
 * 漏了 null 判断会拼出 {@code >= null}，{@code ge/le} 写反只表现为「查不到数据」。
 * 集中后「起止时间如何生效」只有一种答案；时间解析统一走 {@link TimeFormats#parse}。
 * 两个方法都直接改传入的 wrapper 并返回它，便于链式调用，也避免新建 wrapper 丢掉已有条件。
 */
public final class Queries {

    private Queries() {
    }

    /**
     * 给查询加上闭区间时间条件，端点可为 {@code null}（该方向不限）。字符串解析失败同样视为不限：
     * 列表接口不该因为一个时间参数写错就返回 500，与其它接口的宽容口径一致。
     */
    public static <T> LambdaQueryWrapper<T> timeRange(LambdaQueryWrapper<T> wrapper,
                                                      SFunction<T, ?> column,
                                                      String from, String to) {
        LocalDateTime begin = TimeFormats.parse(from);
        if (begin != null) {
            wrapper.ge(column, begin);
        }
        LocalDateTime end = TimeFormats.parse(to);
        if (end != null) {
            wrapper.le(column, end);
        }
        return wrapper;
    }

    /**
     * 给查询加上「设备编码 / 通道编码」两个可选等值条件；空白表示不过滤。
     * 统一用 {@code isBlank()} 而非 {@code hasText}，避免空白串 {@code " "} 被当成有值
     * （两处曾混用，行为有细微差别）。
     */
    public static <T> LambdaQueryWrapper<T> deviceAndChannel(LambdaQueryWrapper<T> wrapper,
                                                             SFunction<T, ?> deviceCol,
                                                             SFunction<T, ?> channelCol,
                                                             String deviceId, String channelId) {
        if (deviceId != null && !deviceId.isBlank()) {
            wrapper.eq(deviceCol, deviceId);
        }
        if (channelId != null && !channelId.isBlank()) {
            wrapper.eq(channelCol, channelId);
        }
        return wrapper;
    }
}
