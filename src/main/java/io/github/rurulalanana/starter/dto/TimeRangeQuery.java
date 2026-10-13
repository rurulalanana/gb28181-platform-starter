package io.github.rurulalanana.starter.dto;

import lombok.Data;

/**
 * 时间范围查询参数。
 *
 * <p>支持两种时间格式：
 * <ul>
 *   <li>{@code YYYY-MM-DDTHH:mm:ss}（GBS 查询类接口）</li>
 *   <li>{@code YYYYMMDDHHmmss}（GBS 云端录像类接口）</li>
 * </ul>
 */
@Data
public class TimeRangeQuery {

    /** 开始时间。 */
    private String starttime;

    /** 结束时间。 */
    private String endtime;
}
