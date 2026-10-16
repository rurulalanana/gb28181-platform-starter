package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
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

    /** 负载计数：直播中的会话数（{@code MediaType.REALTIME}）。 */
    private Long live;

    /** 负载计数：播放数（{@code MediaType.PLAYBACK} 与 {@code DOWNLOAD}）。 */
    private Long play;

    /** 负载计数：云端录像录制中数。 */
    private Long record;

    /** 服务端累积的采样历史（页面首次加载即可画出完整曲线）。 */
    private History history;

    /**
     * 采样历史（时间正序，各数组等长）。
     *
     * <p>由服务端持续采样维护，前端进页面直接拿它铺满折线图 ——
     * 否则图表从零开始累积，刚打开时只有一个点，看不出趋势。
     */
    @Data
    public static class History implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 采样时刻（{@code HH:mm:ss}）。 */
        private List<String> time = new ArrayList<>();

        /** CPU 使用率（0~100）。 */
        private List<Double> cpu = new ArrayList<>();

        /** 内存占用率（0~100）。 */
        private List<Double> mem = new ArrayList<>();

        /** 网络接收速率（Mbps）。 */
        private List<Double> rx = new ArrayList<>();

        /** 网络发送速率（Mbps）。 */
        private List<Double> tx = new ArrayList<>();
    }

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
