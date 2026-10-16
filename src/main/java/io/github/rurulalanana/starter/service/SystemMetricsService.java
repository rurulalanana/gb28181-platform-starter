package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.MediaType;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.starter.common.TimeFormats;
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
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 *   <li>负载计数：按会话类型拆分 —— 直播 = {@code REALTIME} 会话数，
 *       播放 = {@code PLAYBACK} 会话数（含下载中的 {@code DOWNLOAD}），
 *       录像 = {@code gb_record} 中 {@code end_time IS NULL}（录制中）的条数。</li>
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

    /**
     * 服务端保留的采样点数。
     *
     * <p>与前端 {@code MAX_POINTS} 对齐：采样间隔 3 秒 × 60 点 ≈ 3 分钟视窗。
     */
    private static final int HISTORY_SIZE = 60;

    /**
     * 采样间隔（毫秒）。
     *
     * <p>由 {@code PlatformScheduler} 定时驱动，与前端轮询间隔一致。
     * 不靠请求触发是因为：没人开页面时也要积累历史，否则第一个打开页面的人
     * 看到的仍是一个点。
     */
    private static final long SAMPLE_INTERVAL_MS = 3000;

    private final StreamService streamService;
    private final GbRecordMapper recordMapper;
    private final Gb28181MediaPlatform platform;

    /** 网络采样基线（-1 表示尚未采样）。 */
    private volatile long lastRxBytes = -1;
    private volatile long lastTxBytes = -1;
    private volatile long lastSampleNanos = -1;

    /**
     * 历史采样环形缓冲（固定容量，满了丢最旧的）。
     *
     * <p>每项是一条完整采样：时刻 + CPU + 内存占用率 + 收发速率。
     * 用 {@link ArrayDeque} + {@code synchronized} 而非 {@code CopyOnWriteArrayList}：
     * 写入频率固定且低（3 秒一次），读取（组装响应）也只在轮询时发生，
     * 锁开销可忽略，而 COW 每次写都要复制整个数组。
     */
    private final Deque<Sample> history = new ArrayDeque<>(HISTORY_SIZE + 1);

    /** 上一次采样时刻（毫秒），用于限流——避免高频轮询把缓冲冲爆。 */
    private volatile long lastSampleAt;

    /**
     * 最近一次采样结果（由 {@link #sample()} 写，读接口只读）。
     *
     * <p><b>为什么网络速率必须走这个缓存</b>：速率的算法是「与上次比差分」，
     * 而基线（{@code lastRxBytes}）只有一个。若定时采样与请求处理都去调
     * {@code fillNet}，两者会互相把对方的基线顶掉，各自的差分区间被截成
     * 一半甚至更短，表现为速率为 null 或数值乱跳。
     * 因此只允许采样线程推进基线，请求侧直接取最近一次结果。
     */
    private volatile Sample latest;

    /** 一条历史采样。 */
    private record Sample(String time, Double cpu, Double memPct, Double rx, Double tx) {
    }

    /**
     * 采集一次全部指标（响应请求）。
     *
     * <p>CPU / 内存 / 磁盘是瞬时值，每次请求重新读；
     * 网络速率是区间均值，取最近一次采样结果（见 {@link #latest}）。
     */
    public SystemMetricsVo metrics() {
        SystemMetricsVo vo = new SystemMetricsVo();
        fillCpuAndMemory(vo);
        vo.setDisks(collectDisks());
        Sample last = latest;
        if (last != null) {
            vo.setNetRxMbps(last.rx());
            vo.setNetTxMbps(last.tx());
        } else {
            // 启动后头几秒还没采过样：退回直采，至少不至于空白
            fillNet(vo);
        }
        fillLoadCounts(vo);
        vo.setHistory(historySnapshot());
        return vo;
    }

    /**
     * 定时采样入口（由 {@code PlatformScheduler} 调用）。
     *
     * <p>这是<b>唯一</b>推进网络基线与写历史缓冲的地方。
     * 恒定频率运行，不依赖是否有人打开页面 —— 否则第一个进概览页的人
     * 只能看到一个点，要等 3 分钟才成线。
     */
    public void sample() {
        try {
            SystemMetricsVo vo = new SystemMetricsVo();
            fillCpuAndMemory(vo);
            fillNet(vo);
            recordSample(vo);
        } catch (Throwable t) {
            log.debug("定时采样失败：{}", t.toString());
        }
    }

    /**
     * 把本次采样写入环形缓冲与 {@link #latest}。
     *
     * <p>带时间限流：限流是为了兼容手动触发（如启动后立即采一次），
     * 避免同一秒内写入多点把 3 分钟视窗压缩。
     */
    private void recordSample(SystemMetricsVo vo) {
        long now = System.currentTimeMillis();
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS - 200) {
            return;
        }
        lastSampleAt = now;
        Double memPct = null;
        if (vo.getMemTotal() != null && vo.getMemTotal() > 0 && vo.getMemUsed() != null) {
            memPct = round1(vo.getMemUsed() * 100.0 / vo.getMemTotal());
        }
        Sample s = new Sample(
                LocalDateTime.now().format(TimeFormats.CLOCK),
                vo.getCpu(), memPct, vo.getNetRxMbps(), vo.getNetTxMbps());
        latest = s;
        synchronized (history) {
            if (history.size() >= HISTORY_SIZE) {
                history.pollFirst();
            }
            history.addLast(s);
        }
    }

    /** 历史快照（时间正序）。 */
    private SystemMetricsVo.History historySnapshot() {
        SystemMetricsVo.History h = new SystemMetricsVo.History();
        synchronized (history) {
            for (Sample s : history) {
                h.getTime().add(s.time());
                h.getCpu().add(s.cpu());
                h.getMem().add(s.memPct());
                h.getRx().add(s.rx());
                h.getTx().add(s.tx());
            }
        }
        return h;
    }

    /**
     * 负载计数：直播 / 播放 / 录像。
     *
     * <p>三个数字的口径不同，务必区分：
     * <ul>
     *   <li><b>直播</b>：{@code MediaType.REALTIME} 会话数 —— 正在从设备拉流的通道；</li>
     *   <li><b>播放</b>：ZLM 中各流<b>观众总数之和</b> —— 含实时流的播放者、
     *       云端录像点播，以及<b>录制器本身</b>（ZLM 把 MP4 录制当读者，
     *       见 {@code protocol.mp4_as_player}）。同一个流被多个浏览器打开
     *       会累加，所以这个数字是「多少路正在被看」而非「多少路流有观众」。
     *       不能用会话数代替：录像点播走 MP4 解复用源，压根不建 RtpSession，
     *       只遍历会话表永远是 0；</li>
     *   <li><b>录像</b>：{@code gb_record} 中 {@code end_time IS NULL} 的条数。</li>
     * </ul>
     *
     * <p><b>为什么不直接用 {@code sessionCount()}</b>：那返回全部会话数，
     * 回放会话也被算进去，导致「直播」虚高而「播放」恒为 0。
     */
    private void fillLoadCounts(SystemMetricsVo vo) {
        long live = 0;
        try {
            for (RtpSession s : streamService.sessions()) {
                if (s != null && s.getType() == MediaType.REALTIME) {
                    live++;
                }
            }
        } catch (Exception e) {
            log.debug("统计直播会话失败：{}", e.toString());
        }
        vo.setLive(live);
        vo.setPlay(countPlaying());
        vo.setRecord(countRecording());
    }

    /** 正在播放的观众总数（实时流 + 录像点播 + 录制器，以 ZLM 的读者数为准）。 */
    private long countPlaying() {
        try {
            ZlmMediaServer media = platform.mediaServer();
            return media == null ? 0L : media.playingSourceCount(null);
        } catch (Exception e) {
            log.debug("统计播放数失败：{}", e.toString());
            return 0L;
        }
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

    /**
     * 录制中的云端录像条数。
     *
     * <p><b>为什么读 ZLM 内存索引而不是库表</b>：自动录像的归档挂在
     * 「流下线」事件上 —— 录制中的条目<b>还没落库</b>（没有 recordId 入库），
     * 查库恒为 0，用户会以为「我明明开了录像怎么计数是 0」。
     * ZLM 的内存索引才是录制状态的真源：正在录的条目 endTime=0。
     */
    private long countRecording() {
        try {
            if (platform.recordingService() == null) {
                return 0L;
            }
            return platform.recordingService().all().stream()
                    .filter(r -> r.getEndTime() <= 0)
                    .count();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
