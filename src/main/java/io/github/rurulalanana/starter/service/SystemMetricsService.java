package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.starter.entity.GbRecord;
import io.github.rurulalanana.starter.mapper.GbRecordMapper;
import io.github.rurulalanana.starter.vo.SystemMetricsVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统运行指标采集（概览页数据源）。
 *
 * <p>数据来源：
 * <ul>
 *   <li>CPU / 内存：JDK {@code com.sun.management.OperatingSystemMXBean}；</li>
 *   <li>磁盘：Linux 解析 {@code /proc/mounts} 取物理分区，其他平台退化为
 *       {@code File.listRoots()}；</li>
 *   <li>网络速率：Linux 读 {@code /proc/net/dev} 求和后差分（请求驱动采样，
 *       两次调用之间计算平均速率；首次调用建立基线返回 null）；</li>
 *   <li>负载计数：直播 = 点播会话数；录像 = {@code gb_record} 中
 *       {@code end_time IS NULL}（录制中）的条数。</li>
 * </ul>
 *
 * <p>全部采集都做了兜底：单项失败只导致对应字段为 null，不影响其他指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMetricsService {

    /** /proc/mounts 中视为"物理分区"的文件系统类型。 */
    private static final Set<String> PHYSICAL_FS = Set.of(
            "ext4", "ext3", "ext2", "xfs", "btrfs", "vfat", "ntfs", "fuseblk");

    /** 磁盘最多展示的分区数（对齐参考站的展示密度）。 */
    private static final int MAX_DISKS = 4;

    private final StreamService streamService;
    private final GbRecordMapper recordMapper;

    /** 网络采样基线（-1 表示尚未采样）。 */
    private volatile long lastRxBytes = -1;
    private volatile long lastTxBytes = -1;
    private volatile long lastSampleNanos = -1;

    /** 采集一次全部指标。 */
    public SystemMetricsVo metrics() {
        SystemMetricsVo vo = new SystemMetricsVo();
        fillCpuAndMemory(vo);
        vo.setDisks(collectDisks());
        fillNet(vo);
        vo.setLive((long) streamService.sessionCount());
        vo.setPlay(0L);
        vo.setRecord(countRecording());
        return vo;
    }

    /** CPU 与内存（JDK com.sun.management 扩展）。 */
    private void fillCpuAndMemory(SystemMetricsVo vo) {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                double cpu = sun.getCpuLoad();
                if (cpu >= 0) {
                    vo.setCpu(Math.round(cpu * 1000) / 10.0);
                }
                long total = sun.getTotalMemorySize();
                if (total > 0) {
                    // 已用内存优先采用 /proc/meminfo 的 MemAvailable 口径
                    // （与 free 命令一致，排除 page cache 干扰；JDK 的
                    // getFreeMemorySize 在 Linux 上不含 cache 导致虚高）
                    Long available = readMemAvailable();
                    long free = available != null ? available : sun.getFreeMemorySize();
                    vo.setMemTotal(total);
                    vo.setMemUsed(Math.max(total - free, 0));
                }
            }
        } catch (Throwable t) {
            log.debug("采集 CPU/内存指标失败：{}", t.toString());
        }
    }

    /** 解析 /proc/meminfo 的 MemAvailable（KiB → 字节；非 Linux 返回 null）。 */
    private Long readMemAvailable() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) * 1024L;
                    }
                }
            }
        } catch (Exception ignore) {
            // 非 Linux：走 JDK 兜底
        }
        return null;
    }

    /** 磁盘挂载点用量。 */
    private List<SystemMetricsVo.Disk> collectDisks() {
        List<SystemMetricsVo.Disk> out = new ArrayList<>();
        try {
            Map<String, String> mounts = readPhysicalMounts();
            for (Map.Entry<String, String> e : mounts.entrySet()) {
                File f = new File(e.getValue());
                long total = f.getTotalSpace();
                if (total <= 0) {
                    continue;
                }
                SystemMetricsVo.Disk d = new SystemMetricsVo.Disk();
                d.setPath(e.getValue());
                d.setTotal(total);
                d.setUsed(total - f.getUsableSpace());
                out.add(d);
                if (out.size() >= MAX_DISKS) {
                    break;
                }
            }
        } catch (Throwable t) {
            log.debug("采集磁盘指标失败：{}", t.toString());
        }
        if (out.isEmpty()) {
            // 非 Linux（如开发机 macOS）：退化为根分区
            try {
                File root = new File("/");
                long total = root.getTotalSpace();
                if (total > 0) {
                    SystemMetricsVo.Disk d = new SystemMetricsVo.Disk();
                    d.setPath("/");
                    d.setTotal(total);
                    d.setUsed(total - root.getUsableSpace());
                    out.add(d);
                }
            } catch (Throwable ignore) {
                // 忽略
            }
        }
        return out;
    }

    /** 解析 /proc/mounts，返回 设备名 → 挂载点（保持顺序，去重设备）。 */
    private Map<String, String> readPhysicalMounts() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(Path.of("/proc/mounts"))) {
                String[] parts = line.split("\\s+");
                if (parts.length < 3) {
                    continue;
                }
                String device = parts[0];
                String mountPoint = parts[1].replace("\\040", " ");
                String fsType = parts[2];
                if (!device.startsWith("/dev/") || !PHYSICAL_FS.contains(fsType)) {
                    continue;
                }
                // 同一块设备可能有多个挂载点（bind mount），只保留第一个
                out.putIfAbsent(device, mountPoint);
            }
        } catch (Exception e) {
            // 非 Linux：/proc/mounts 不存在，交由上层兜底
        }
        return out;
    }

    /** 网络速率（/proc/net/dev 差分）。 */
    private void fillNet(SystemMetricsVo vo) {
        try {
            long rx = 0;
            long tx = 0;
            boolean found = false;
            for (String line : Files.readAllLines(Path.of("/proc/net/dev"))) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String iface = line.substring(0, colon).trim();
                if ("lo".equals(iface)) {
                    continue;
                }
                String[] nums = line.substring(colon + 1).trim().split("\\s+");
                if (nums.length < 9) {
                    continue;
                }
                rx += Long.parseLong(nums[0]);
                tx += Long.parseLong(nums[8]);
                found = true;
            }
            if (!found) {
                return;
            }
            long now = System.nanoTime();
            long lastRx = lastRxBytes;
            long lastTx = lastTxBytes;
            long lastNano = lastSampleNanos;
            lastRxBytes = rx;
            lastTxBytes = tx;
            lastSampleNanos = now;
            if (lastRx < 0 || now <= lastNano) {
                // 首次采样只建立基线
                return;
            }
            double seconds = (now - lastNano) / 1_000_000_000.0;
            if (seconds <= 0.2) {
                // 采样间隔过短，速率噪声大，跳过本次
                return;
            }
            vo.setNetRxMbps(round2((rx - lastRx) * 8.0 / 1_000_000.0 / seconds));
            vo.setNetTxMbps(round2((tx - lastTx) * 8.0 / 1_000_000.0 / seconds));
        } catch (Throwable t) {
            log.debug("采集网络速率失败：{}", t.toString());
        }
    }

    /** 录制中的云端录像条数（end_time 为空表示仍未结束）。 */
    private long countRecording() {
        try {
            return recordMapper.selectCount(Wrappers.<GbRecord>lambdaQuery()
                    .isNull(GbRecord::getEndTime));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
