package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 录像计划与通道的关联（对应表 {@code gb_record_plan_channel}）。
 *
 * <p>一个计划可挂多个通道；一个通道也可同时挂多个计划（任一计划命中时段即录）。
 */
@Data
@TableName("gb_record_plan_channel")
public class GbRecordPlanChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属计划 ID。 */
    private String planId;

    /** 设备编码。 */
    private String deviceId;

    /** 通道编码。 */
    private String channelId;

    private LocalDateTime createTime;
}
