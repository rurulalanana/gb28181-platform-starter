package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 系统运行指标（概览页 6 张卡片的数据源，每 3 秒轮询）。
 *
 * <p>对应参考站概览页的「通道信息 / 负载信息 / CPU 使用 / 存储使用 /
 * 带宽使用 / 内存使用」。设备与通道在线数复用 {@code OnlineStatsVo}，
 * 本 VO 只承载主机级指标与负载计数。
 */
@Data
public class SystemMetricsVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 系统 CPU 使用率（0~100，取不到为 null）。 */
    private Double cpu;

    /** 物理内存总量（字节，取不到为 null）。 */
    private Long memTotal;

    /** 物理内存已用（字节，取不到为 null）。 */
    private Long memUsed;

    /** 磁盘挂载点（物理分区，最多 4 个）。 */
    private List<Disk> disks;

    /** 网络接收速率（Mbps；首次采样或非 Linux 为 null）。 */
    private Double netRxMbps;

    /** 网络发送速率（Mbps；首次采样或非 Linux 为 null）。 */
    private Double netTxMbps;

    /** 负载计数：点播中的实时流数。 */
    private Long live;

    /** 负载计数：播放数（暂无数据源，恒 0）。 */
    private Long play;

    /** 负载计数：云端录像录制中数。 */
    private Long record;

    /** 磁盘挂载点用量。 */
    @Data
    public static class Disk implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 挂载点（如 /、/mnt/data）。 */
        private String path;

        /** 分区总容量（字节）。 */
        private Long total;

        /** 已用（字节）。 */
        private Long used;
    }
}
