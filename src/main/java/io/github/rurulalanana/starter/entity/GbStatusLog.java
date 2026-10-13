package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设备 / 通道状态变更记录。
 *
 * <p>对应表 {@code gb_status_log}。
 */
@Data
@TableName("gb_status_log")
public class GbStatusLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;
    private String channelId;

    private String oldStatus;
    private String newStatus;

    /** 描述（如「设备注册」「心跳超时」）。 */
    private String description;

    private String reason;

    private LocalDateTime createTime;
}
