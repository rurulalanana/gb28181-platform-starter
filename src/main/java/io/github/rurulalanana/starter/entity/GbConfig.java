package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 运行时配置（对应表 {@code gb_config}）。
 *
 * <p><b>语义：数据库优先，application.yml 兜底</b>。表里没有某个 key 时
 * 用 yml 的默认值；用户在界面上保存后写入本表，之后启动也从这里读。
 *
 * <p>只存「改了不该重启」的运维参数。端口、路径、数据库连接这类决定进程
 * 装配的配置仍在 application.yml —— 放进本表只会让界面显示改了却实际没生效。
 */
@Data
@TableName("gb_config")
public class GbConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键（如 {@code record.retain-days}），全表唯一。 */
    private String configKey;

    /** 配置值（统一按字符串存，读取时按 {@link #valueType} 解析）。 */
    private String configValue;

    /** 分组，前端按此分区展示（record / log / task / stream / device / media）。 */
    private String configGroup;

    /** 数据类型：INT / LONG / BOOLEAN / STRING。 */
    private String valueType;

    /** 界面显示说明。 */
    private String description;

    private LocalDateTime updateTime;
}
