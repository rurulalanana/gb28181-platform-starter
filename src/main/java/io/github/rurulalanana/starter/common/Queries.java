package io.github.rurulalanana.starter.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import java.time.LocalDateTime;

/**
 * 查询条件拼装的公共片段。
 *
 * <p>本平台有大量「按设备/通道 + 时间区间」的列表接口（报警、状态日志、
 * 位置日志）。它们的条件拼装逐行相同，只有实体与时间列不同，
 * 于是同一段八行代码被抄了三份：
 *
 * <pre>{@code
 * LocalDateTime from = TimeFormats.parse(startTime);
 * LocalDateTime to   = TimeFormats.parse(endTime);
 * if (from != null) q.ge(Entity::getTime, from);
 * if (to   != null) q.le(Entity::getTime, to);
 * }</pre>
 *
 * <p><b>抄写的真正代价不是行数，而是校验口径会走偏</b>：{@link TimeFormats#parse}
 * 接受多种写法（ISO、空格分隔、纯日期），某处漏了 {@code null} 判断就会拼出
 * {@code >= null}；某处把 {@code ge/le} 写反，前端只会表现为「查不到数据」，
 * 很难定位到是哪个接口。集中到这里之后，「起止时间如何生效」只有一种答案。
 *
 * @since 1.0.3
 */
public final class Queries {

    private Queries() {
    }

    /**
     * 给查询加上时间区间条件（闭区间）。两个端点都可为 {@code null}，
     * 表示该方向不限。
     *
     * <p>字符串解析失败同样视为「不限」，与接口其它地方的宽容口径一致 ——
     * 列表类接口不该因为一个时间参数写错就返回 500。
     *
     * @param wrapper 目标查询
     * @param column  时间列（如 {@code GbAlarm::getStartTime}）
     * @param from    起始时间文本，可为空
     * @param to      结束时间文本，可为空
     * @param <T>     实体类型
     * @return 传入的 wrapper，便于链式调用
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
     * 给查询加上「设备编码 / 通道编码」两个可选等值条件。
     *
     * <p>与 {@link #timeRange} 同理：这两行在报警、状态日志、位置日志里各写一遍，
     * 其中一处用了 {@code hasText}、另一处用 {@code !isBlank()}，
     * 混用空白串时行为就会有细微差别（{@code " "} 是否算「有值」）。
     *
     * @param wrapper   目标查询
     * @param deviceCol 设备列
     * @param channelCol 通道列
     * @param deviceId  设备编码，空白表示不过滤
     * @param channelId 通道编码，空白表示不过滤
     * @param <T>       实体类型
     * @return 传入的 wrapper
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
