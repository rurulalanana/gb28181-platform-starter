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

    private String channelId;

    /** 是否共享给该上级。 */
    private Boolean shared;

    /** 是否已推送过目录。 */
    private Boolean pushed;

    private LocalDateTime pushTime;
    private LocalDateTime createTime;
}
