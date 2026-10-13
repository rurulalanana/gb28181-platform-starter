package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 报警信息。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AlarmVo {

    /** 报警 ID。 */
    private String id;

    private String deviceId;
    private String deviceName;
    private String channelId;
    private String channelName;

    /** 报警级别：1~4。 */
    private Integer alarmPriority;
    private String alarmPriorityName;

    /** 报警方式：1 电话 / 2 设备 / 3 短信 / 4 GPS / 5 视频 / 6 设备故障 / 7 其他。 */
    private Integer alarmMethod;
    private String alarmMethodName;

    /** 报警类型（含义随 {@link #alarmMethod} 变化）。 */
    private Integer alarmType;
    private String alarmTypeName;

    /** 入侵报警事件类型：1 进入区域 / 2 离开区域。 */
    private Integer alarmEventType;

    private String alarmDescription;

    /** 报警时间。 */
    private String time;

    /** 结束时间。 */
    private String endTime;

    /** 状态：{@code ACTIVE} / {@code RESET}。 */
    private String state;

    private java.math.BigDecimal longitude;
    private java.math.BigDecimal latitude;

    /** 关联录像路径。 */
    private String recordPath;

    /** 关联快照路径。 */
    private String snapPath;

    private String createdAt;

    /** 报警图片列表（设备上报时填充）。 */
    private List<String> snapShotList;
}
