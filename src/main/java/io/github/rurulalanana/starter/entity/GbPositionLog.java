package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 移动位置记录（轨迹）。
 *
 * <p>对应表 {@code gb_position_log}。
 */
@Data
@TableName("gb_position_log")
public class GbPositionLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;
    private String channelId;

    /** 获取方式：移动位置上报 / 报警位置上报 / 通道通知 / 通道检索 / 接口设置。 */
    private String method;

    private BigDecimal longitude;
    private BigDecimal latitude;
    private BigDecimal altitude;
    private BigDecimal speed;
    private BigDecimal direction;

    private LocalDateTime createTime;
}
