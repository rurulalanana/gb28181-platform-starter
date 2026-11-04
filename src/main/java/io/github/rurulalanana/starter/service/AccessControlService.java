package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.security.AccessControl;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbAcl;
import io.github.rurulalanana.starter.mapper.GbAclMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 接入控制（黑白名单）：同时是 SIP 信令层的 {@link AccessControl} 实现与后台管理服务。
 *
 * <p>统一接入密码只能回答「密码对不对」，回答不了「这个来源许不许进」；密码泄露或设备用弱口令时，
 * 来源过滤是不依赖密码的最后一道拦截。检查发生在摘要认证之前 —— 否则黑名单里的设备只要密码对了就能进。
 *
 * <p>用不可变内存快照（{@code volatile} 引用 + 写时整体替换）而非每次查库：{@link #check} 在 jain-sip
 * 协议栈线程上每个 REGISTER 调用一次，查库会把信令线程拖住，设备一多就是注册超时雪崩。
 *
 * <p>白名单为空表示不限制（而非「谁都不许进」）：否则新增第一条白名单之前所有设备都会掉线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessControlService implements AccessControl {

    /** 名单类型。 */
    public static final String BLACK = "BLACK";
    public static final String WHITE = "WHITE";

    /** 匹配方式。 */
    public static final String KIND_DEVICE = "DEVICE";
    public static final String KIND_PREFIX = "PREFIX";
    public static final String KIND_IP = "IP";
    public static final String KIND_CIDR = "CIDR";

    private final GbAclMapper aclMapper;

    /** 当前生效的规则快照：整体替换、不原地修改（读端可能正在遍历，改半个列表会读到中间态）。 */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /**
     * 必须监听 {@link ApplicationReadyEvent} 而不能用 {@code @PostConstruct}：建表由
     * {@code SchemaInitializer} 的 {@code ApplicationRunner} 完成，它在 bean 初始化之后才跑，
     * 提前查会在首启时留下一条永久性的误导告警。失败不阻断启动（快照为空 = 不限制来源）。
     */
    @EventListener(ApplicationReadyEvent.class)
    void load() {
        reload();
    }

    /** 重新从库里加载全部规则（增删改后调用）。 */
    public synchronized void reload() {
        try {
            List<GbAcl> rows = aclMapper.selectList(null);
            snapshot = Snapshot.of(rows);
            log.info("接入控制已加载：黑名单 {} 条，白名单 {} 条",
                    snapshot.blackRules.size(), snapshot.whiteRules.size());
        } catch (Exception e) {
            // 表还没建好 / 数据源异常：保持现状，不因为读失败就把已有规则清空（否则「库抖一下 → 黑名单失效」）
            log.warn("读取接入控制规则失败，沿用现有规则: {}", e.getMessage());
        }
    }

    // ==================== SIP 层调用 ====================

    @Override
    public boolean isEnabled() {
        Snapshot s = snapshot;
        // 一条规则都没有 = 完全不限制，省掉后续判断
        return !s.blackRules.isEmpty() || !s.whiteRules.isEmpty();
    }

    @Override
    public Decision check(String deviceId, String ip) {
        Snapshot s = snapshot;
        if (s.blackRules.isEmpty() && s.whiteRules.isEmpty()) {
            return Decision.allow();
        }
        // 1) 黑名单优先：白名单不能给黑名单开后门
        for (Rule r : s.blackRules) {
            if (r.matches(deviceId, ip)) {
                return Decision.deny("命中黑名单：" + r.describe());
            }
        }
        // 2) 有白名单时必须命中
        if (!s.whiteRules.isEmpty()) {
            for (Rule r : s.whiteRules) {
                if (r.matches(deviceId, ip)) {
                    return Decision.allow();
                }
            }
            return Decision.deny("不在白名单内（白名单共 " + s.whiteRules.size() + " 条）");
        }
        // 3) 只有黑名单且未命中 → 放行
        return Decision.allow();
    }

    // ==================== 后台管理 ====================

    /** 全部规则（界面列表用）。 */
    public List<GbAcl> list(String listType) {
        List<GbAcl> rows = aclMapper.selectList(null);
        if (listType == null || listType.isBlank()) {
            return rows;
        }
        String want = listType.trim().toUpperCase(Locale.ROOT);
        List<GbAcl> out = new ArrayList<>();
        for (GbAcl r : rows) {
            if (want.equals(r.getListType())) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * 新增一条规则。
     *
     * @throws BizException 参数非法或已存在同值规则
     */
    public GbAcl add(String listType, String kind, String value, String remark) {
        String type = normalizeListType(listType);
        String k = normalizeKind(kind);
        String v = normalizeValue(k, value);
        Long dup = aclMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.<GbAcl>lambdaQuery()
                .eq(GbAcl::getListType, type)
                .eq(GbAcl::getKind, k)
                .eq(GbAcl::getValue, v));
        if (dup != null && dup > 0) {
            throw BizException.badRequest("该规则已存在：" + type + " / " + k + " / " + v);
        }
        GbAcl row = new GbAcl();
        row.setListType(type);
        row.setKind(k);
        row.setValue(v);
        row.setRemark(remark);
        aclMapper.insert(row);
        reload();
        log.info("接入控制新增: {} {} {}（{}）", type, k, v, remark);
        return row;
    }

    /** 删除一条规则。 */
    public void remove(Long id) {
        if (id == null) {
            throw BizException.badRequest("缺少 id");
        }
        GbAcl row = aclMapper.selectById(id);
        if (row == null) {
            throw BizException.notFound("规则不存在：" + id);
        }
        aclMapper.deleteById(id);
        reload();
        log.info("接入控制删除: {} {} {}", row.getListType(), row.getKind(), row.getValue());
    }

    /** 统计（界面提示用）。 */
    public long count(String listType) {
        Long n = aclMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.<GbAcl>lambdaQuery()
                .eq(GbAcl::getListType, normalizeListType(listType)));
        return n == null ? 0 : n;
    }

    // ==================== 校验 ====================

    private static String normalizeListType(String listType) {
        String t = listType == null ? "" : listType.trim().toUpperCase(Locale.ROOT);
        if (BLACK.equals(t) || WHITE.equals(t)) {
            return t;
        }
        throw BizException.badRequest("名单类型只能是 BLACK 或 WHITE，收到：" + listType);
    }

    private static String normalizeKind(String kind) {
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        switch (k) {
            case KIND_DEVICE:
            case KIND_PREFIX:
            case KIND_IP:
            case KIND_CIDR:
                return k;
            default:
                throw BizException.badRequest(
                        "匹配方式只能是 DEVICE / PREFIX / IP / CIDR，收到：" + kind);
        }
    }

    /**
     * 规范化并校验匹配值：规则写错时设备会被无声地挡住或放进，
     * 而现场排查往往先怀疑网络和密码，所以宁可在录入时报错。
     */
    private static String normalizeValue(String kind, String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw BizException.badRequest("匹配值不能为空");
        }
        switch (kind) {
            case KIND_DEVICE:
                if (!v.matches("\\d{20}")) {
                    throw BizException.badRequest(
                            "设备编码必须是 20 位数字（GB/T 28181 编码规则），收到：" + v);
                }
                return v;
            case KIND_PREFIX:
                if (!v.matches("\\d{1,20}")) {
                    throw BizException.badRequest(
                            "编码前缀必须是 1~20 位数字，收到：" + v);
                }
                return v;
            case KIND_IP: {
                long ipv4 = parseIpv4(v);
                if (ipv4 < 0) {
                    throw BizException.badRequest("不是合法的 IPv4 地址：" + v);
                }
                return v;
            }
            case KIND_CIDR:
                parseCidr(v);
                return v;
            default:
                return v;
        }
    }

    // ==================== IP / 网段解析 ====================

    /** 解析 IPv4 为无符号整数；非法返回 {@code -1}。 */
    private static long parseIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return -1;
        }
        long out = 0;
        for (String part : parts) {
            if (!part.matches("\\d{1,3}")) {
                return -1;
            }
            int n = Integer.parseInt(part);
            if (n < 0 || n > 255) {
                return -1;
            }
            out = (out << 8) | n;
        }
        return out;
    }

    /** 解析 CIDR，返回 [网络地址, 掩码]；非法抛 {@link BizException}。 */
    private static long[] parseCidr(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash <= 0) {
            throw BizException.badRequest("网段格式应为 192.168.1.0/24，收到：" + cidr);
        }
        long base = parseIpv4(cidr.substring(0, slash));
        if (base < 0) {
            throw BizException.badRequest("网段中的地址不合法：" + cidr);
        }
        String maskText = cidr.substring(slash + 1);
        if (!maskText.matches("\\d{1,2}")) {
            throw BizException.badRequest("网段前缀长度不合法：" + cidr);
        }
        int bits = Integer.parseInt(maskText);
        if (bits < 0 || bits > 32) {
            throw BizException.badRequest("网段前缀长度必须在 0~32 之间：" + cidr);
        }
        long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
        return new long[]{base & mask, mask};
    }

    // ==================== 内部结构 ====================

    /** 一条编译好的规则（匹配时不再解析字符串）。 */
    private static final class Rule {

        final String kind;
        /** 展示用原文：DEVICE/PREFIX 是编码，IP 是地址，CIDR 是网段。 */
        final String text;
        final String devicePart;
        /** IP / CIDR 用 */
        final long ipBase;
        final long ipMask;

        private Rule(String kind, String text, String devicePart, long ipBase, long ipMask) {
            this.kind = kind;
            this.text = text;
            this.devicePart = devicePart;
            this.ipBase = ipBase;
            this.ipMask = ipMask;
        }

        /**
         * 编译一条规则；失败返回 {@code null}。不抛异常：库里的历史脏数据不该让整份名单加载失败，
         * 那等于黑名单被一条错记录作废。
         */
        static Rule compile(GbAcl row) {
            String kind = row.getKind() == null ? KIND_DEVICE
                    : row.getKind().trim().toUpperCase(Locale.ROOT);
            String value = row.getValue() == null ? "" : row.getValue().trim();
            if (value.isEmpty()) {
                return null;
            }
            switch (kind) {
                case KIND_DEVICE:
                case KIND_PREFIX:
                    return new Rule(kind, value, value, 0, 0);
                case KIND_IP: {
                    long ip = parseIpv4(value);
                    return ip < 0 ? null : new Rule(kind, value, null, ip, 0xFFFFFFFFL);
                }
                case KIND_CIDR: {
                    try {
                        long[] c = parseCidr(value);
                        return new Rule(kind, value, null, c[0], c[1]);
                    } catch (RuntimeException e) {
                        return null;
                    }
                }
                default:
                    return null;
            }
        }

        boolean matches(String deviceId, String ip) {
            switch (kind) {
                case KIND_DEVICE:
                    return devicePart.equals(deviceId);
                case KIND_PREFIX:
                    return deviceId != null && !deviceId.isEmpty() && deviceId.startsWith(devicePart);
                case KIND_IP:
                case KIND_CIDR: {
                    if (ip == null || ip.isEmpty()) {
                        return false;
                    }
                    long parsed = parseIpv4(ip);
                    if (parsed < 0) {
                        return false;
                    }
                    return (parsed & ipMask) == ipBase;
                }
                default:
                    return false;
            }
        }

        String describe() {
            switch (kind) {
                case KIND_PREFIX:
                    return "编码前缀 " + text;
                case KIND_IP:
                    return "IP " + text;
                case KIND_CIDR:
                    return "网段 " + text;
                default:
                    return "设备 " + text;
            }
        }
    }

    /** 不可变规则快照。 */
    private static final class Snapshot {

        static final Snapshot EMPTY = new Snapshot(Collections.emptyList(), Collections.emptyList());

        final List<Rule> blackRules;
        final List<Rule> whiteRules;

        private Snapshot(List<Rule> blackRules, List<Rule> whiteRules) {
            this.blackRules = blackRules;
            this.whiteRules = whiteRules;
        }

        static Snapshot of(List<GbAcl> rows) {
            List<Rule> black = new ArrayList<>();
            List<Rule> white = new ArrayList<>();
            if (rows != null) {
                for (GbAcl row : rows) {
                    Rule rule = Rule.compile(row);
                    if (rule == null) {
                        log.warn("忽略无法解析的接入控制规则：{} {} {}",
                                row.getListType(), row.getKind(), row.getValue());
                        continue;
                    }
                    if (WHITE.equalsIgnoreCase(row.getListType())) {
                        white.add(rule);
                    } else {
                        // 未知 listType 一律当黑名单处理：宁可挡，不可漏
                        black.add(rule);
                    }
                }
            }
            return new Snapshot(Collections.unmodifiableList(black),
                    Collections.unmodifiableList(white));
        }
    }
}
