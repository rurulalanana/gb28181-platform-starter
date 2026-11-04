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
 * 运行时配置服务：数据库优先，application.yml 兜底。
 *
 * <p>改「录像保留 7 天 → 30 天」不该走改 yml、重新打包、停服重启（重启会中断录像并让设备重注册）；
 * 放进 {@code gb_config} 后保存即生效：写库 + 更新内存镜像，定时任务下一轮按新值跑。
 *
 * <p>读取走内存：这些值在高频路径上被读（保活每 30 秒 × 通道数、对账每 60 秒、流上线回调）。
 * 启动顺序是先读 {@link Environment} 作为基线，再用库里的值覆盖 —— yml 是出厂默认，库是运行时覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfigService {

    private final GbConfigMapper configMapper;
    private final Environment env;

    /** 生效值缓存：只放已解析并校验过的值；读不到时回落 {@link #defaults}，再回落 {@link ConfigKeys.Def#def()}。 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** yml 基线（key → 值），启动时算好，避免每次回落都去查 Environment。 */
    private final Map<String, String> defaults = new ConcurrentHashMap<>();

    /** 是否已完成首次加载。 */
    private volatile boolean loaded;

    /**
     * 启动期先只灌「yml 基线」，不等数据源。不能只靠 {@link #reload()}：它要查库，
     * 由 {@code StreamEventBridge#attach} 触发，而那之前 SIP 注册鉴权就会读 {@code sip.password}，
     * {@link #raw} 会回落到写死的空串 —— 等于启动瞬间把鉴权关了。
     */
    @jakarta.annotation.PostConstruct
    void initDefaultsFromEnv() {
        for (ConfigKeys.Def d : ConfigKeys.ALL) {
            defaults.put(d.key(), resolveFromEnv(d));
        }
        cache.putAll(defaults);
    }

    /**
     * 启动时加载：yml 基线 + 数据库覆盖。需在数据源就绪后调用（{@code StreamEventBridge#attach}）。
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
                    // 库里多余的 key（旧版本遗留 / 手写）：忽略但不删，便于排查
                    log.debug("忽略未注册的配置项: {}", key);
                    continue;
                }
                try {
                    merged.put(def.key(), def.validate(row.getConfigValue()));
                    // 只统计真正改变了生效值的行：库里可能残留与默认值相同的记录，
                    // 把它们算进「已覆盖」会与界面按值比较的 overridden 判定对不上
                    if (!java.util.Objects.equals(row.getConfigValue(),
                            base.get(def.key()))) {
                        overridden++;
                    }
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
     * 从 Environment 取 key 的 yml 值：注册表用点分层级（{@code record.retain-days}），
     * Spring 用 kebab-case 且带前缀（{@code gb28181.starter.record-retain-days}）。
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
     * 注册表 key → Spring 属性名，逐个映射而非硬套前缀：yml 里各组的真实位置不一致
     * （{@code record.}/{@code log.} 在 starter 下，{@code stream.} 与部分 {@code device.} 不在，
     * {@code tls.*} 是嵌套结构），猜错前缀会让「yml 改了不生效」以更难排查的形式出现。
     */
    private static String toPropertyName(String key) {
        return switch (key) {
            case ConfigKeys.DEVICE_OFFLINE_SECONDS ->
                    "gb28181.starter.device-offline-seconds";
            /*
             * device.* 组里其余项的 yml 名字不带 device- 前缀（starter.auto-collect 等），
             * 走默认分支会拼成不存在的属性，表现为「yml 写着 false，界面显示 true」。
             */
            case ConfigKeys.DEVICE_OFFLINE_SCAN_INTERVAL_MS ->
                    "gb28181.starter.offline-scan-interval-ms";
            case ConfigKeys.DEVICE_AUTO_COLLECT ->
                    "gb28181.starter.auto-collect";
            case ConfigKeys.DEVICE_COLLECT_INTERVAL_SECONDS ->
                    "gb28181.starter.collect-interval-seconds";
            case ConfigKeys.DEVICE_COLLECT_DEVICE_INFO ->
                    "gb28181.starter.collect-device-info";
            case ConfigKeys.DEVICE_COLLECT_DEVICE_STATUS ->
                    "gb28181.starter.collect-device-status";
            // sip.password → gb28181.sip-password（不在 starter 下）
            case ConfigKeys.SIP_PASSWORD ->
                    "gb28181.sip-password";
            /* tls.* 在 yml 里是嵌套结构（gb28181.tls.port），走默认分支拼出的属性不存在，改了不生效 */
            case ConfigKeys.TLS_PORT ->
                    "gb28181.tls.port";
            case ConfigKeys.TLS_AUTO_RELOAD ->
                    "gb28181.tls.auto-reload";
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
     * 保存一项配置（校验 → 写库 → 更新内存）。必须写库成功才更新内存，
     * 否则会出现「界面显示已保存、重启后变回旧值」。
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
        // 改成与默认值相同在语义上就是「恢复默认」：删行而不是留着，
        // 否则库里积冗余行、界面按值判定 overridden 显示「未修改」，两个口径打架
        String fallback = defaults.getOrDefault(def.key(), def.def());
        boolean sameAsDefault = normalized.equals(fallback);
        GbConfig existing = configMapper.selectOne(Wrappers.<GbConfig>lambdaQuery()
                .eq(GbConfig::getConfigKey, def.key()).last("limit 1"));
        if (sameAsDefault) {
            if (existing != null) {
                configMapper.delete(Wrappers.<GbConfig>lambdaQuery()
                        .eq(GbConfig::getConfigKey, def.key()));
            }
            cache.put(def.key(), normalized);
            log.info("运行时配置已更新: {} = {}（与默认值一致，已移除数据库覆盖）",
                    def.key(), normalized);
            return normalized;
        }
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

    /** 删除一项配置的数据库覆盖，回落到 yml / 默认值（运维不必记住出厂值）。 */
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

    /**
     * 批量保存多项配置：先整体校验、再逐项落库，任一项非法就整体拒绝（半套配置生效比全不生效更难排查）。
     * 前端「改几项、点一次保存」是配置页的惯例，逐个请求会出现中途失败而前面的已生效。
     * 落库阶段若某项异常，前面已写的保留（跨行无法回滚），前端可按返回值重试。
     *
     * @param changes key → 期望值
     * @return 实际生效的 key → 规范化后的值
     * @throws IllegalArgumentException 任一项的值非法
     */
    public java.util.Map<String, String> setBatch(java.util.Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) {
            return java.util.Map.of();
        }
        // 第一轮：全部校验，任一非法直接抛出，不做任何写入
        java.util.Map<String, String> normalized = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> e : changes.entrySet()) {
            ConfigKeys.Def def = ConfigKeys.find(e.getKey());
            if (def == null) {
                throw new IllegalArgumentException("不支持的配置项：" + e.getKey());
            }
            normalized.put(def.key(), def.validate(e.getValue()));
        }
        // 第二轮：落库 + 更新内存
        java.util.Map<String, String> applied = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> e : normalized.entrySet()) {
            String key = e.getKey();
            applied.put(key, set(key, e.getValue()));
        }
        log.info("运行时配置批量更新：{} 项（{}）", applied.size(), applied.keySet());
        return applied;
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
            // 库里有覆盖 = true（界面显示「已修改」并可一键恢复）
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
        private String defaultValue;
        private long min;
        private long max;
        /** 是否已被数据库覆盖。 */
        private boolean overridden;
    }
}
