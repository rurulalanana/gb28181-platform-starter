package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 预置位名称自定义。
 *
 * <p>对应表 {@code gb_preset}。设备上报的预置位只有编号，
 * 本表保存本域为其起的名字。
 */
@Data
@TableName("gb_preset")
public class GbPreset implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String channelId;

    /** 预置位编号（1~255）。 */
    private Integer presetId;

    private String name;

    private LocalDateTime updateTime;
}
