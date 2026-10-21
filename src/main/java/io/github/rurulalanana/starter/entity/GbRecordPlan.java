package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 录像计划（对应表 {@code gb_record_plan}）。
 *
 * <p>定义「哪些通道、在每周哪几天、每天什么时段录云端录像」。
 * 与 LiveGBS 的录像计划语义一致：{@link #plan} 存 JSON 字符串，
 * 结构为 {@code [[{"s1":"00:00","e1":"24:00"}], [], [{"s3":"09:00","e3":"09:30"}], [], [], [], []]}，
 * 外层 7 个数组依次是周一~周日，每项是该天的时段列表（一天可多段）。
 */
@Data
@TableName("gb_record_plan")
public class GbRecordPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 计划 ID（平台生成，全局唯一）。 */
    private String planId;

    /** 计划名称。 */
    private String name;

    /** 每周时段规则（JSON 字符串，结构见类注释）。 */
    private String plan;

    /** 是否启用。 */
    private Boolean enable;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
