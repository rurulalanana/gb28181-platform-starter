package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 云端（平台侧）录像文件。
 *
 * <p>对应表 {@code gb_record}。与设备侧录像 {@link GbDeviceRecord} 不同：
 * 本表记录的是平台自己用 ZLMediaKit 录下来的 MP4。
 */
@Data
@TableName("gb_record")
public class GbRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 录像 ID（同时作为 ZLM 点播流名的一部分）。 */
    private String recordId;

    private String channelId;
    private String deviceId;
    private String name;

    /** 录像文件绝对路径。 */
    private String filePath;

    private String fileName;

    private Long fileSize;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 时长（秒）。 */
    private Integer duration;

    /** 重要标记，标记后不会被自动清理。 */
    private Boolean important;

    private Boolean cloudRecord;

    private LocalDateTime createTime;
}
