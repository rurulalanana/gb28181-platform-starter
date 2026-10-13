package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 流量统计记录。
 *
 * <p>对应表 {@code gb_traffic_log}。
 */
@Data
@TableName("gb_traffic_log")
public class GbTrafficLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;
    private String channelId;

    /** 接收字节数（设备 → 平台）。 */
    private Long bytesUp;

    /** 发送字节数（平台 → 客户端）。 */
    private Long bytesDown;

    private LocalDate statDate;
    private LocalDateTime statTime;
}
