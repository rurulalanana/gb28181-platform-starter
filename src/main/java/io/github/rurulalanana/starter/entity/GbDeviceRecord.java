package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设备侧录像目录项（标准 9.8 {@code RecordInfo} 查询结果缓存）。
 *
 * <p>对应表 {@code gb_device_record}。这些文件存储在设备上，
 * 播放需通过回放 INVITE 让设备把流推回来。
 */
@Data
@TableName("gb_device_record")
public class GbDeviceRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String channelId;
    private String deviceId;
    private String name;

    /** 设备侧文件路径（回放 SDP 的 {@code u=} 行）。 */
    private String filePath;

    /** 录像地址。 */
    private String address;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private Integer duration;
    private Long size;

    /** 录像类型：{@code time} 定时 / {@code alarm} 报警 / {@code manual} 手动。 */
    private String type;

    private Integer secrecy;
    private Integer streamNumber;

    private LocalDateTime updateTime;
}
