package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备报警记录（标准 9.4 报警通知 / A.2.5.3）。
 *
 * <p>对应表 {@code gb_alarm}。
 */
@Data
@TableName("gb_alarm")
public class GbAlarm implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 报警 ID（平台生成，用于删除与详情查询）。 */
    private String alarmId;

    private String deviceId;
    private String channelId;

    /** 报警方式：1 电话 / 2 设备 / 3 短信 / 4 GPS / 5 视频 / 6 设备故障 / 7 其他。 */
    private Integer method;
    private String methodName;

    /** 报警级别：1~4。 */
    private Integer priority;
    private String priorityName;

    /** 报警类型，含义随 {@link #method} 变化。 */
    private Integer alarmType;
    private String alarmTypeName;

    /** 入侵报警事件类型：1 进入区域 / 2 离开区域。 */
    private Integer eventType;

    private String alarmDescription;

    /** 报警时间。 */
    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 状态：{@code ACTIVE} / {@code RESET}。 */
    private String state;

    private BigDecimal longitude;
    private BigDecimal latitude;

    /** 关联录像路径。 */
    private String videoPath;

    /** 关联快照路径。 */
    private String snapPath;

    private LocalDateTime createTime;
}
