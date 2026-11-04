package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 预置点名称自定义。
 *
 * <p>对应表 {@code gb_preset}。设备上报的预置点只有编号（设备侧名称也存在设备里），
 * 本表保存本域为其起的<b>别名</b> —— 这是平台唯一需要自己存的预置点数据。
 *
 * <p>不要在这里加坐标字段：早期曾用「平台存 (pan,tilt,zoom) + 绝对定位」
 * 替代设备预置点，后来确认设备完全支持（{@code QueryDevState(0x57)}），
 * 那套副本已移除 —— 两份数据必然不同步。
 */
@Data
@TableName("gb_preset")
public class GbPreset implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String channelId;

    /** 预置点编号（1~255）。 */
    private Integer presetId;

    private String name;

    private LocalDateTime updateTime;
}
