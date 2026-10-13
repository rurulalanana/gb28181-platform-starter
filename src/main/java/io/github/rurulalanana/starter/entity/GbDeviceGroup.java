package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设备分组（业务分组树）。
 *
 * <p>对应表 {@code gb_device_group}。
 */
@Data
@TableName("gb_device_group")
public class GbDeviceGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String groupCode;
    private String groupName;
    private String parentCode;
    private Integer sortNo;

    private LocalDateTime createTime;
}
