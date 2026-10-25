package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 级联共享通道关联。
 *
 * <p>对应表 {@code gb_cascade_channel}。
 */
@Data
@TableName("gb_cascade_channel")
public class GbCascadeChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cascadeId;

    /** 上级平台编号（冗余，便于按上级查询）。 */
    private String parentId;

    /**
     * 通道所属设备编码。
     *
     * <p><b>定位通道必须用「设备 + 通道」而不是单独的通道编码</b>：
     * GB/T 28181 里通道编码只在单设备内唯一，多设备共存时
     * {@code 34020000001320000001} 可能同时存在于十几台设备上（现场实测）。
     * 只按通道编码记录共享关系，勾选 A 设备的通道会把 B 设备的同号通道一起共享出去。
     */
    private String deviceId;

    private String channelId;

    /** 是否共享给该上级。 */
    private Boolean shared;

    /** 是否已推送过目录。 */
    private Boolean pushed;

    private LocalDateTime pushTime;
    private LocalDateTime createTime;
}
