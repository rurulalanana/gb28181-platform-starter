package io.github.rurulalanana.starter.common;

import java.util.List;

/**
 * 分页结果。
 *
 * <p>各接口的计数与列表字段名不同（{@code deviceCount/deviceList}、
 * {@code channelCount/channelList}…），由各接口的 VO 自行承载；
 * 本类只提供游标分页的通用切片逻辑。
 *
 * @param <T> 列表元素类型
 */
public record PageResult<T>(long total, List<T> list) {

    public static <T> PageResult<T> of(long total, List<T> list) {
        return new PageResult<>(total, list);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(0, List.of());
    }
}
