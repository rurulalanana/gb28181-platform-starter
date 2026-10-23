package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.starter.entity.GbConfig;
import io.github.rurulalanana.starter.mapper.GbConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时配置服务：<b>数据库优先，application.yml 兜底</b>。
 *
 * <h3>解决什么问题</h3>
 *
 * <p>改一个「录像保留 7 天 → 30 天」需要改 yml、重新打包、上传 76MB jar、
 * 停服重启（停机本身还要 20 秒）。运维角度这是不可接受的 ——
 * 而且重启会中断所有录像、让设备重新注册。
 *
 * <p>把这些参数放进 {@code gb_config} 表后，界面上改完<b>立即生效</b>：
 * 用户保存 → 写库 + 更新内存镜像 → 定时任务下一次执行就按新值跑。
 *
 * <h3>读取为什么走内存</h3>
 *
 * <p>这些值在<b>高频路径</b>上被读：保活任务每 30 秒 × 通道数、
 * 对账任务每 60 秒、流上线回调里还要读分片时长。每次都查库会产生
 * 大量本可避免的 SQL。因此启动时一次性载入 {@link #cache}，
 * 之后读内存（{@link ConcurrentHashMap#get} 是无锁的）。
 *
 * <h3>为什么 yml 也要读</h3>
 *
 * <p>如果只用注册表里写死的默认值，就与 {@code application.yml} 里的
 * 配置脱节了 —— 运维改了 yml 却不生效，会非常反直觉。因此启动顺序是：
 * <ol>
 *   <li>先读 {@link Environment}（即 yml 的值）作为基线；</li>
 *   <li>再用库里的值覆盖。</li>
 * </ol>
 * 这样「yml 是出厂默认，库是运行时覆盖」，两层语义清晰。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfigService {

    private final GbConfigMapper configMapper;
    private final Environment env;

    /**
     * 生效值缓存（key → 字符串值）。
     *
     * <p>只放<b>已解析并校验过</b>的值。读不到时回落到 {@link #defaults}，
     * 再读不到才用 {@link ConfigKeys.Def#def()}。
     */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** yml 基线（key → 值），启动时算好，避免每次回落都去查 Environment。 */
    private final Map<String, String> defaults = new ConcurrentHashMap<>();

    /** 是否已完成首次加载。 */
    private volatile boolean loaded;

    /**
     * 启动时加载：yml 基线 + 数据库覆盖。
     *
     * <p>由 {@code @PostConstruct} 之外的地方调用（`StreamEventBridge#attach`），
     * 因为需要数据源就绪。
     */
    public void reload() {
        // 1) yml 基线
        Map<String, String> base = new LinkedHashMap<>();
        for (ConfigKeys.Def d : ConfigKeys.ALL) {
            base.put(d.key(), resolveFromEnv(d));
        }
        defaults.clear();
        defaults.putAll(base);

        // 2) 数据库覆盖
        Map<String, String> merged = new LinkedHashMap<>(base);
        int overridden = 0;
        try {
            List<GbConfig> rows = configMapper.selectList(Wrappers.<GbConfig>lambdaQuery());
            for (GbConfig row : rows) {
                String key = row.getConfigKey();
                if (key == null) {
                    continue;
                }
                ConfigKeys.Def def = ConfigKeys.find(key);
                if (def == null) {
                    // 库里有多余的 key（旧版本遗留 / 手写进去的）：忽略但不删，
                    // 保留下来便于排查，只是不参与生效值
                    log.debug("忽略未注册的配置项: {}", key);
                    continue;
                }
                try {
                    merged.put(def.key(), def.validate(row.getConfigValue()));
                    overridden++;
                } catch (IllegalArgumentException e) {
                    // 库里的值非法（被手工改成脏数据）：回落到 yml，不能让它阻断启动
                    log.warn("配置项 {} 的值非法，已回落到默认值 {}：{}",
                            key, base.get(def.key()), e.getMessage());
                }
            }
        } catch (Exception e) {
            // 表还没建好 / 数据源异常：只用 yml 值，不影响启动
            log.warn("读取 gb_config 失败，本次全部使用 application.yml 的值: {}", e.getMessage());
        }

        cache.clear();
        cache.putAll(merged);
        loaded = true;
        log.info("运行时配置已加载：{} 项（其中 {} 项被数据库覆盖）",
                merged.size(), overridden);
    }

    /**
     * 从 Environment 取某个 key 的 yml 值。
     *
     * <p>把注册表的 key（{@code record.retain-days}）映射成 Spring 的属性名
     * （{@code gb28181.starter.record-retain-days}）。两者形态不同：
     * 注册表用点分层级便于分组展示，Spring 用 kebab-case 且带前缀。
     */
    private String resolveFromEnv(ConfigKeys.Def def) {
        String property = toPropertyName(def.key());
        String v = env.getProperty(property);
        if (v == null || v.isBlank()) {
            return def.def();
        }
        try {
            return def.validate(v);
        } catch (IllegalArgumentException e) {
            log.warn("application.yml 中 {} 的值非法，改用内置默认值 {}：{}",
                    property, def.def(), e.getMessage());
            return def.def();
        }
    }

    /**
     * 注册表 key → Spring 属性名。
     *
     * <p>{@code record.retain-days} → {@code gb28181.starter.record-retain-days}
     *
     * <p>只有 {@code record.}/{@code log.} 两组在 yml 里位于 {@code starter} 下；
     * {@code stream.} 与 {@code device.} 分属 {@code gb28181} 与
     * {@code gb28181.starter}。这里逐个映射而不是硬套前缀 ——
     * 猜错前缀会让「yml 改了不生效」这个坑以更难排查的形式出现。
     */
    private static String toPropertyName(String key) {
        return switch (key) {
            case ConfigKeys.DEVICE_OFFLINE_SECONDS ->
                    "gb28181.starter.device-offline-seconds";
            case ConfigKeys.STREAM_NONE_READER_DELAY_MS ->
                    "gb28181.stream-none-reader-delay-ms";
            case ConfigKeys.STREAM_IDLE_REAP_INTERVAL_MS ->
                    "gb28181.starter.idle-reap-interval-ms";
            default -> {
                // record.xxx-yyy → gb28181.starter.record-xxx-yyy
                // log.xxx-yyy    → gb28181.starter.log-xxx-yyy  （yml 里实际叫 oplog-*）
                String tail = key.replace('.', '-');
                if (ConfigKeys.LOG_OPLOG_RETAIN_DAYS.equals(key)) {
                    yield "gb28181.starter.oplog-retain-days";
                }
                yield "gb28181.starter." + tail;
            }
        };
    }

    // ==================== 读取 ====================

    /** 取整型配置。 */
    public int getInt(String key) {
        String v = raw(key);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            ConfigKeys.Def def = ConfigKeys.find(key);
            log.warn("配置项 {} 的值不是整数（{}），回落到默认值", key, v);
            return def == null ? 0 : Integer.parseInt(def.def());
        }
    }

    /** 取长整型配置。 */
    public long getLong(String key) {
        String v = raw(key);
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            ConfigKeys.Def def = ConfigKeys.find(key);
            log.warn("配置项 {} 的值不是整数（{}），回落到默认值", key, v);
            return def == null ? 0L : Long.parseLong(def.def());
        }
    }

    /** 取布尔配置。 */
    public boolean getBoolean(String key) {
        return "true".equalsIgnoreCase(raw(key));
    }

    /** 取字符串配置。 */
    public String getString(String key) {
        return raw(key);
    }

    /** 原始值：缓存 → yml 基线 → 注册表默认值，三级回落。 */
    private String raw(String key) {
        String k = key == null ? null : key.trim().toLowerCase(Locale.ROOT);
        if (k == null) {
            return "";
        }
        String v = cache.get(k);
        if (v != null) {
            return v;
        }
        v = defaults.get(k);
        if (v != null) {
            return v;
        }
        ConfigKeys.Def def = ConfigKeys.find(k);
        return def == null ? "" : def.def();
    }

    // ==================== 写入 ====================

    /**
     * 保存一项配置（校验 → 写库 → 更新内存）。
     *
     * <p><b>写库成功才更新内存</b>：反过来会造成「界面显示已保存、实际重启后
     * 又变回旧值」——库和内存不一致是最难排查的一类问题。
     *
     * @return 生效值
     * @throws IllegalArgumentException 值非法（由 {@link ConfigKeys.Def#validate} 抛出）
     */
    public String set(String key, String value) {
        ConfigKeys.Def def = ConfigKeys.find(key);
        if (def == null) {
            throw new IllegalArgumentException("不支持的配置项：" + key);
        }
        String normalized = def.validate(value);
        GbConfig existing = configMapper.selectOne(Wrappers.<GbConfig>lambdaQuery()
                .eq(GbConfig::getConfigKey, def.key()).last("limit 1"));
        if (existing == null) {
            GbConfig row = new GbConfig();
            row.setConfigKey(def.key());
            row.setConfigValue(normalized);
            row.setConfigGroup(def.group());
            row.setValueType(def.type());
            row.setDescription(def.desc());
            row.setUpdateTime(LocalDateTime.now());
            configMapper.insert(row);
        } else {
            existing.setConfigValue(normalized);
            existing.setUpdateTime(LocalDateTime.now());
            configMapper.updateById(existing);
        }
        cache.put(def.key(), normalized);
        log.info("运行时配置已更新: {} = {}（立即生效）", def.key(), normalized);
        return normalized;
    }

    /**
     * 删除一项配置的数据库覆盖，回落到 yml / 默认值。
     *
     * <p>「恢复默认」比「改成一个猜的值」更可靠 —— 运维不必记住出厂值。
     */
    public String reset(String key) {
        ConfigKeys.Def def = ConfigKeys.find(key);
        if (def == null) {
            throw new IllegalArgumentException("不支持的配置项：" + key);
        }
        configMapper.delete(Wrappers.<GbConfig>lambdaQuery()
                .eq(GbConfig::getConfigKey, def.key()));
        String fallback = defaults.getOrDefault(def.key(), def.def());
        cache.put(def.key(), fallback);
        log.info("运行时配置已重置: {} = {}（回落到 application.yml）", def.key(), fallback);
        return fallback;
    }

    // ==================== 查询（界面用） ====================

    /** 全部配置项及其当前值。 */
    public List<Item> list() {
        List<Item> out = new ArrayList<>();
        for (ConfigKeys.Def d : ConfigKeys.ALL) {
            Item item = new Item();
            item.setKey(d.key());
            item.setGroup(d.group());
            item.setType(d.type());
            item.setDescription(d.desc());
            item.setValue(raw(d.key()));
            item.setDefaultValue(defaults.getOrDefault(d.key(), d.def()));
            item.setMin(d.min());
            item.setMax(d.max());
            // 数据库里有覆盖 = true（界面上可以显示「已修改」并可一键恢复）
            item.setOverridden(cache.containsKey(d.key())
                    && !raw(d.key()).equals(item.getDefaultValue()));
            out.add(item);
        }
        return out;
    }

    /** 是否已完成首次加载（健康检查用）。 */
    public boolean isLoaded() {
        return loaded;
    }

    /** 一项配置的展示模型。 */
    @lombok.Data
    public static class Item {
        private String key;
        private String group;
        private String type;
        private String description;
        /** 当前生效值。 */
        private String value;
        /** yml / 内置默认值（用于「恢复默认」和「已修改」标记）。 */
        private String defaultValue;
        private long min;
        private long max;
        /** 是否已被数据库覆盖。 */
        private boolean overridden;
    }
}
