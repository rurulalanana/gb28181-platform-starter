package io.github.rurulalanana.starter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rurulalanana.dahua.ext.DahuaCameraConfig;
import io.github.rurulalanana.dahua.ext.DahuaSdkException;
import io.github.rurulalanana.starter.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大华相机图像参数的读写编排。
 *
 * <h3>为什么 JSON 手术放在平台侧</h3>
 * 大华把图像参数打包在少数几个 JSON 配置项里，要改其中一项就必须
 * 「解析整棵树 → 改字段 → 序列化写回」。平台侧本来就有 Jackson
 * （Spring Boot 自带），在这里做不额外引入依赖；而且平台还要把结果
 * 翻译成统一 DTO、按 {@code group}/{@code type} 组织给前端，本来就得解析。
 *
 * <p>这条也解决了一个正确性问题：手写字符串替换去改 JSON，遇到
 * {@code "Gamma":10} 与 {@code "GammaX":10} 这种前缀相同的字段会改错，
 * 而用 Jackson 解析成树再改，天然不会。
 *
 * <h3>为什么每次写都要「读全量 → 改一个 → 写回」</h3>
 * 大华的写配置是<b>整段覆盖</b>，不是部分更新。只发改动字段会把其余字段
 * 全部清零 —— 实测表现为「只调了亮度，结果对比度、饱和度全变 0」。
 */
@Slf4j
@Service
public class DahuaCameraConfigService {

    private final DahuaCapabilityService dahuaCapability;
    private final ObjectMapper mapper;

    public DahuaCameraConfigService(DahuaCapabilityService dahuaCapability,
                                    ObjectMapper mapper) {
        this.dahuaCapability = dahuaCapability;
        this.mapper = mapper;
    }

    // ==================== 能力位 ====================

    /**
     * 读该通道支持哪些图像参数。
     *
     * <p>判据是「配置 JSON 里有没有这个字段」。比按型号猜可靠：
     * 同型号不同固件的能力也可能不同。
     *
     * @return 参数定义 + 是否可用 + 当前值
     */
    public List<Capability> capabilities(String deviceId, String channelCode) {
        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, channelCode);
        Map<String, String> jsons = readAllConfigs(session, sdkChannel);
        Map<String, Boolean> caps = DahuaCameraConfig.readCapabilities(jsons);
        /*
         * 当前抗闪烁设置 —— 快门的可选档位由它决定。
         *
         * 50Hz 下只能取 1/(50·2ⁿ)、60Hz 取 1/(60·2ⁿ)，这是防闪烁的物理约束，
         * 不是型号差异。给错档位的话设备虽然照存（Value1 是自由浮点字段），
         * 但防闪烁实际不生效，用户看不出来。
         */
        String antiFlicker = String.valueOf(coerceForType(DahuaCameraConfig.Type.ENUM,
                readValue(parse(jsons.get("VideoInExposure")),
                        List.of("AntiFlicker"))));

        List<Capability> out = new ArrayList<>();
        for (DahuaCameraConfig.Param p : DahuaCameraConfig.all()) {
            Capability c = new Capability();
            c.setKey(p.key());
            c.setLabel(p.label());
            c.setGroup(p.group());
            c.setType(p.type().name());
            c.setRange(p.range());
            c.setMin(p.min());
            c.setMax(p.max());
            c.setVirtual(p.virtual());
            /*
             * 联动信息：这个参数只在上级取特定值时才显示。
             *
             * 设备界面就是这样做的 —— 曝光模式切「手动」才出现快门/增益/光圈，
             * 白平衡切「手动」才出现红蓝增益，日夜模式选「自动」才出现灵敏度与延迟。
             * 前端据此隐藏无关控件，否则自动模式下摆一堆无效输入框，
             * 用户改了也不会生效，只会以为平台有 bug。
             */
            c.setDependsOn(p.dependsOn());
            c.setDependsValues(p.dependsValues() == null ? List.of() : p.dependsValues());
            /*
             * 组合参数的成员字段不在界面上单独渲染。
             *
             * 用户要表达的是「视角 = 倒影」，而不是「Flip 开 / Mirror 关 / Rotate90 关」
             * 三个开关 —— 后者既与下拉重复，又允许配出互相矛盾的状态。
             * 字段本身仍留在能力表里（读写组合参数要靠它拿真实字段名），只是前端跳过。
             */
            c.setHidden(DahuaCameraConfig.isVirtualMember(p.key())
                    || DahuaCameraConfig.KEY_FOCUS_LIMIT_MODE.equals(p.key()));

            List<OptionVo> opts = new ArrayList<>();
            /*
             * 快门档位跟着抗闪烁走，用 p.options() 里的兜底值会给出设备当前不支持的档位：
             * 50Hz 下摆出 1/1000，用户选了“保存成功”但防闪烁失效。
             */
            List<DahuaCameraConfig.Option> paramOptions = isShutterSpeed(p)
                    ? DahuaCameraConfig.shutterOptions(antiFlicker)
                    : p.options();
            for (DahuaCameraConfig.Option o : paramOptions) {
                opts.add(new OptionVo(o.value(), o.label()));
            }
            c.setAvailable(Boolean.TRUE.equals(caps.get(p.key())));
            if (c.isAvailable()) {
                /*
                 * 范围参数要读两个字段并返回 [min, max]，
                 * 走 coerceForType 会被当单值处理，前端两个输入框就绑到同一个数上了。
                 */
                if (p.type() == DahuaCameraConfig.Type.RANGE) {
                    c.setValue(readRangeValue(jsons, p));
                } else if (DahuaCameraConfig.KEY_FOCUS_LIMIT.equals(p.key())) {
                    /*
                     * 「接近焦距极限」跨 FocusLimit / FocusLimitSelectMode 两个字段，
                     * 只读 FocusLimit 的话「自动」会被回显成上一次的距离值。
                     */
                    c.setValue(readFocusLimit(jsons, p));
                } else if (isShutterSpeed(p)) {
                    /*
                     * 快门：设备存毫秒，界面要分母。
                     * 回显时用<b>当前抗闪烁的档位表</b>优先匹配；
                     * 匹配不上（如 50Hz 下的 1/120）就反算得到真分母。
                     */
                    List<DahuaCameraConfig.Option> shutterOpts =
                            DahuaCameraConfig.shutterOptions(antiFlicker);
                    Object v = readShutterSpeed(parse(jsons.get(p.configCommand())), p, shutterOpts);
                    c.setValue(v);
                    /* 「自定义」只在室外档位存在：50Hz/60Hz 下设备不给自由范围 */
                    if (!DahuaCameraConfig.shutterSupportsCustom(antiFlicker)) {
                        opts.removeIf(o -> DahuaCameraConfig.SHUTTER_CUSTOM.equals(o.getValue()));
                    }
                    /*
                     * 当前值不在下拉里 → 补一个“设备当前值”选项。
                     *
                     * 不补的话 el-select 匹配不上，会渲染成空白，
                     * 看起来就像“没读到参数”，而实际设备里有值。
                     * 典型场景：从室外切到 50Hz，存的 1/120 不在 50Hz 档位表里。
                     */
                    if (v != null) {
                        String cur = String.valueOf(v);
                        boolean known = opts.stream().anyMatch(o -> o.getValue().equals(cur));
                        if (!known) {
                            opts.add(new OptionVo(cur, labelOfShutter(cur)));
                        }
                    }
                } else {
                    Object raw = readRawValue(jsons, p);
                    Object value = coerceForParam(p, raw);
                    c.setValue(value);
                    /*
                     * 控制类型是<b>布尔</b>但底层存的是字符串（3D 降噪、自定义开关）：
                     * 界面渲染成开关，{@code options} 里那两个只是映射用的，
                     * 不能当选项下发 —— 否则前端会把它当二选下拉。
                     */
                    if (DahuaCameraConfig.hasBoolMapping(p)) {
                        opts.clear();
                    }
                    /*
                     * 设备的实际存储类型可能比选项表窄，典型是布尔字段配了多值选项。
                     * 这时把选项收窄成「设备真能表达的那几个」——
                     * 否则用户选一个设备存不下的值，写进去被强转，回读校验再报「未生效」，
                     * 纯属自找困惑。判据是当前值的类型，不是选项表本身。
                     */
                    if (p.type() == DahuaCameraConfig.Type.ENUM && raw instanceof Boolean) {
                        opts.removeIf(o -> !"true".equalsIgnoreCase(o.getValue())
                                && !"false".equalsIgnoreCase(o.getValue()));
                    }
                    /*
                     * 当前值不在选项表里 → 补一个只读的「设备当前值」选项。
                     *
                     * <p>大华对某些字符串字段不做校验（图像风格写入任意字符串都「成功」），
                     * 设备里完全可能存着选项表之外的值 —— 用户也可能在设备 Web 界面设过。
                     * 不补的话下拉匹配不上，会渲染成空白，看起来像「没读到参数」。
                     */
                    if (p.type() == DahuaCameraConfig.Type.ENUM && value != null) {
                        String cur = String.valueOf(value);
                        boolean known = opts.stream().anyMatch(o -> o.getValue().equals(cur));
                        if (!known) {
                            opts.add(new OptionVo(cur, cur + "（设备当前值）"));
                        }
                    }
                }
            }
            c.setOptions(opts);
            out.add(c);
        }
        return out;
    }

    // ==================== 读取 ====================

    /**
     * 读全部参数当前值。
     *
     * @return key → 值（值为 {@code null} 表示该型号没有这一项）
     */
    public Map<String, Object> read(String deviceId, String channelCode) {
        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, channelCode);
        Map<String, String> jsons = readAllConfigs(session, sdkChannel);
        Map<String, Object> out = new LinkedHashMap<>();
        for (DahuaCameraConfig.Param p : DahuaCameraConfig.all()) {
            /*
             * 值为 null 表示「该型号没有这一项」。刻意保留 null 键：
             * 序列化时会被 NON_NULL 去掉，所以前端看到「键不存在」= 不支持，
             * 而不是「值为空」，两者含义完全不同。
             */
            out.put(p.key(), readParamValue(jsons, p));
        }
        out.putIfAbsent("_channel", sdkChannel);
        return out;
    }

    /**
     * 读一个参数的当前值，<b>支持组合参数，并按控件类型归一</b>。
     *
     * <h3>为什么要归一</h3>
     * 设备的字段类型与界面控件类型并不总是一致，实测有两种：
     * <pre>
     *   Rotate90  返回 0/1（数字），但界面是开关 → 必须转成 false/true
     *   3DType    返回 Auto/Off（字符串），但界面是开关 → 必须转成 true/false
     * </pre>
     * 不归一的话前端拿到的值匹配不上控件（开关把 {@code "Auto"} 当 truthy 或反之），
     * 用户看到的永远是同一个状态。
     */
    private Object readParamValue(Map<String, String> jsons, DahuaCameraConfig.Param p) {
        // 范围参数是两个字段，不走单值归一
        if (p.type() == DahuaCameraConfig.Type.RANGE) {
            return readRangeValue(jsons, p);
        }
        // 「接近焦距极限」跨两个字段，模式优先
        if (DahuaCameraConfig.KEY_FOCUS_LIMIT.equals(p.key())) {
            return readFocusLimit(jsons, p);
        }
        // 快门存的是毫秒，界面要的是分母（1/xx）
        if (isShutterSpeed(p)) {
            return readShutterSpeed(parse(jsons.get(p.configCommand())), p, null);
        }
        return coerceForParam(p, readRawValue(jsons, p));
    }

    /** 这个参数是不是「快门速度」（需要分母 ↔ 毫秒换算）。 */
    private static boolean isShutterSpeed(DahuaCameraConfig.Param p) {
        return p != null && DahuaCameraConfig.KEY_SHUTTER_SPEED.equals(p.key());
    }

    /**
     * 快门值的下拉标签。
     *
     * <p>当前值不在档位表里时（例如切了抗闪烁、或用户在设备上设过 1/200），
     * 下拉要能把它显示出来，否则 {@code el-select} 匹配不上会渲染成空白。
     */
    private static String labelOfShutter(String denominator) {
        if (DahuaCameraConfig.SHUTTER_CUSTOM.equalsIgnoreCase(denominator)) {
            return "自定义";
        }
        return "1/" + denominator + "（设备当前值）";
    }

    /** 用户选的是不是「快门 → 自定义」。 */
    private static boolean isShutterCustom(Object value) {
        return value != null
                && DahuaCameraConfig.SHUTTER_CUSTOM.equalsIgnoreCase(String.valueOf(value).trim());
    }

    /**
     * 快门当前值（分母字符串，或 {@code custom}）。
     *
     * <p>设备存的是 {@code Value1}/{@code Value2}（毫秒）：
     * <ul>
     *   <li>两个值相等 → 选了具体档位，换算 {@code 1000/毫秒} 再找档位</li>
     *   <li>两个值不等 → 用户自定义了范围，界面上显示「自定义」</li>
     * </ul>
     * 只看 {@code Value1} 会把自定义范围回显成一个具体档位，
     * 用户刷新后发现自己的设置「变了」。
     *
     * <p><b>两个值相等时不再把「匹配不上档位」当成自定义</b>：
     * 设备把 {@code Value1} 当自由浮点字段存，任何毫秒值都能写进去。
     * 例如 1/200（{@code 5ms}）不在任何预设档位表里，但设备完全支持，
     * 回显应该显示「1/200」而不是「自定义」—— 后者会让用户以为自己
     * 设成了范围，从而反复重试。反算在 {@code shutterDenominator} 里做。
     *
     * @param options 当前抗闪烁下可用的档位；{@code null} = 室外全集
     */
    private Object readShutterSpeed(JsonNode root, DahuaCameraConfig.Param p,
                                    List<DahuaCameraConfig.Option> options) {
        if (root == null) {
            return null;
        }
        Object min = readValue(root, List.of("Value1"));
        Object max = readValue(root, List.of("Value2"));
        if (min == null) {
            return null;
        }
        double lo = asDouble(min);
        double hi = max == null ? lo : asDouble(max);
        if (Math.abs(lo - hi) > 0.01) {
            return DahuaCameraConfig.SHUTTER_CUSTOM;
        }
        String d = DahuaCameraConfig.shutterDenominator(lo, options);
        return d == null ? DahuaCameraConfig.SHUTTER_CUSTOM : d;
    }

    /**
     * 「接近焦距极限」的当前值。
     *
     * <p>设备把这 13 个选项做成了两个字段：
     * {@code FocusLimitSelectMode=Auto} 时界面上显示「自动」，
     * 而 {@code FocusLimit} 仍保留上一次的距离值（单位毫米）。
     * 只读 {@code FocusLimit} 的话，「自动」会被回显成一个具体距离，
     * 用户看到的就是「存了自动、刷新又变回 5m」。
     */
    private Object readFocusLimit(Map<String, String> jsons, DahuaCameraConfig.Param p) {
        JsonNode root = parse(jsons.get(p.configCommand()));
        Object mode = readValue(root, List.of(DahuaCameraConfig.KEY_FOCUS_LIMIT_MODE_FIELD));
        if (mode != null
                && DahuaCameraConfig.FOCUS_LIMIT_AUTO.equalsIgnoreCase(String.valueOf(mode).trim())) {
            return DahuaCameraConfig.FOCUS_LIMIT_AUTO;
        }
        return coerceForType(DahuaCameraConfig.Type.ENUM,
                readValue(root, p.fields()));
    }

    /**
     * 本次写入是否为「接近焦距极限 = 自动」。
     *
     * <p>为 {@code true} 时<b>不能</b>把值写进 {@code FocusLimit}：
     * 那是整数字段（毫米），写字符串 {@code "Auto"} 会直接抛
     * 「该参数需要数字：Auto」—— 这就是用户遇到的那个报错。
     * 设备自己保存「自动」时也只把 {@code FocusLimitSelectMode} 改成 {@code Auto}，
     * {@code FocusLimit} 原封不动（抓 RPC2 验证）。
     */
    private static boolean isFocusLimitAuto(DahuaCameraConfig.Param p, Object value) {
        return DahuaCameraConfig.KEY_FOCUS_LIMIT.equals(p.key())
                && value != null
                && DahuaCameraConfig.FOCUS_LIMIT_AUTO.equalsIgnoreCase(String.valueOf(value).trim());
    }

    /**
     * 按参数定义归一读数（含「开关 ↔ 字符串」映射）。
     *
     * @return 归一后的值；读不到返回 {@code null}
     */
    static Object coerceForParam(DahuaCameraConfig.Param p, Object raw) {
        if (raw == null) {
            return null;
        }
        /*
         * 开关型但底层是字符串（3DType=Auto/Off、IntensityMode=Enable/Disable）：
         * 按 options 里声明的「开 / 关」字串比对，而不是把它当普通布尔。
         * 否则 "Auto" 会被 asBool 判成 false —— 开关永远显示为关。
         */
        if (DahuaCameraConfig.hasBoolMapping(p)) {
            String s = String.valueOf(raw).trim();
            String on = DahuaCameraConfig.boolOnValue(p);
            String off = DahuaCameraConfig.boolOffValue(p);
            if (s.equalsIgnoreCase(on)) {
                return Boolean.TRUE;
            }
            if (s.equalsIgnoreCase(off)) {
                return Boolean.FALSE;
            }
            // 设备里存了第三种值：按布尔语义兜底，至少不会显示成反的
            return asBool(raw) ? Boolean.TRUE : Boolean.FALSE;
        }
        return coerceForType(p.type(), raw);
    }

    /** 把开关值翻回设备要存的字符串；不是映射型时原样返回。 */
    private static Object writeValueForParam(DahuaCameraConfig.Param p, Object value) {
        if (!DahuaCameraConfig.hasBoolMapping(p)) {
            return value;
        }
        boolean on = asBool(value);
        return on ? DahuaCameraConfig.boolOnValue(p) : DahuaCameraConfig.boolOffValue(p);
    }

    /** 读设备原始值（未按控件类型归一），组合参数取换算结果。 */
    private Object readRawValue(Map<String, String> jsons, DahuaCameraConfig.Param p) {
        if (p.virtual()) {
            return readVirtualValue(jsons, p);
        }
        return readValue(parse(jsons.get(p.configCommand())), p.fields());
    }

    /**
     * 把设备读到的原始值转成控件类型对应的值。
     *
     * <h3>枚举一律转成字符串</h3>
     * 大华的枚举有两类：
     * <pre>
     *   数字枚举  ExposureMode=0 / DayNightSensitivity=2
     *   字符串枚举 WhiteBalance="Auto" / BacklightMode="Off"
     * </pre>
     * 而 {@link DahuaCameraConfig.Option#value()} 统一是字符串，
     * 所以这里把数字也转成字符串 —— 前端下拉的 {@code value} 才能匹配上，
     * 否则会出现「下拉框空白、选项一个都对不上」。
     *
     * @return 归一后的值；读不到返回 {@code null}
     */
    static Object coerceForType(DahuaCameraConfig.Type type, Object raw) {
        if (raw == null) {
            return null;
        }
        if (type == DahuaCameraConfig.Type.BOOLEAN) {
            return asBool(raw) ? Boolean.TRUE : Boolean.FALSE;
        }
        if (type == DahuaCameraConfig.Type.ENUM) {
            if (raw instanceof Boolean b) {
                return String.valueOf(b);
            }
            if (raw instanceof Number num) {
                // 去掉 0.0 那种小数尾巴，否则 "0.0" 匹配不上选项 "0"
                double d = num.doubleValue();
                return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            return String.valueOf(raw).trim();
        }
        // NUMBER：去掉 "12" 这类字符串外壳
        if (raw instanceof Number num) {
            return num.intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(raw).trim());
        } catch (RuntimeException e) {
            return raw;
        }
    }

    /** 组合参数的当前值（由成员字段换算）。 */
    private Object readVirtualValue(Map<String, String> jsons, DahuaCameraConfig.Param p) {
        List<DahuaCameraConfig.Param> members = DahuaCameraConfig.membersOf(p.key());
        if (members.size() < 3) {
            return null;
        }
        Boolean flip = asBoolOrNull(parse(jsons.get(members.get(0).configCommand())),
                members.get(0).fields());
        Boolean mirror = asBoolOrNull(parse(jsons.get(members.get(1).configCommand())),
                members.get(1).fields());
        Boolean corridor = asBoolOrNull(parse(jsons.get(members.get(2).configCommand())),
                members.get(2).fields());
        if (flip == null || mirror == null || corridor == null) {
            return null;
        }
        return DahuaCameraConfig.viewModeOf(flip, mirror, corridor);
    }

    /**
     * 范围参数的当前值（下限、上限两个字段）。
     *
     * <p><b>小数不能取整</b>：快门范围的毫秒值是 2 位小数
     * （1/215 = 4.65ms、1/90 = 11.11ms），取整会让界面显示 4 / 11，
     * 用户看到的值与设备不一致，再保存就把 4.65 覆盖成 4。
     * 增益/光圈是整数，走 {@link #asNumber} 时自然落到整数。
     *
     * @return {@code [min, max]}；任一读不到返回 {@code null}
     */
    private Object readRangeValue(Map<String, String> jsons, DahuaCameraConfig.Param p) {
        JsonNode root = parse(jsons.get(p.configCommand()));
        Object min = readValue(root, List.of(DahuaCameraConfig.rangeMinField(p)));
        Object max = readValue(root, List.of(DahuaCameraConfig.rangeMaxField(p)));
        if (min == null || max == null) {
            return null;
        }
        return List.of(asNumber(min), asNumber(max));
    }

    /** 数字归一：整数返回 {@link Integer}，带小数返回 {@link Double}（保留原精度）。 */
    private static Number asNumber(Object v) {
        double d = asDouble(v);
        return d == Math.rint(d) ? (Number) (int) d : (Number) d;
    }

    /** 按候选字段名读布尔值；读不到返回 {@code null}（与 {@code false} 区分）。 */
    private Boolean asBoolOrNull(JsonNode node, List<String> fields) {
        Object v = readValue(node, fields);
        if (v == null) {
            return null;
        }
        return asBool(v);
    }

    // ==================== 写入 ====================

    /**
     * 一次写入的结果。
     *
     * @param needRestart  是否需要重启设备才生效
     * @param notEffective 写了但回读后仍为原值的参数（key → 设备实际保留的值）。
     *                     <b>非空说明这些参数设备没接受</b>，前端必须如实提示，
     *                     不能显示「保存成功」。
     * @param hints        设备侧规则造成的原因说明（如 EIS 与移动侦测互斥）。
     *                     与 {@code notEffective} 配合使用：前者说「怎么解决」，
     *                     后者说「哪些没生效」。
     */
    public record WriteResult(boolean needRestart, Map<String, Object> notEffective,
                              Map<String, String> hints) {
    }

    /**
     * 改一个或多个参数。
     *
     * <p>按配置项分组：同一配置项的多个改动只读写一次，
     * 不同配置项逐个处理。这既减少往返，也避免「写 A 时把刚写的 B 覆盖掉」。
     *
     * @param values key → 新值（字符串或数字均可）
     */
    public WriteResult write(String deviceId, String channelCode, Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            throw BizException.badRequest("没有要修改的参数");
        }
        // 先校验 key 合法性：拼错的 key 必须报错，不能静默忽略
        List<String> known = new ArrayList<>();
        for (String k : values.keySet()) {
            if (DahuaCameraConfig.byKey(k) == null) {
                throw BizException.badRequest("不支持的参数：" + k
                        + "（可用参数见 /cameraconfig/capabilities）");
            }
            known.add(k);
        }
        /*
         * 组合参数展开：`viewMode=倒影` 要变成 flip=true/mirror=false/rotate90=false。
         *
         * 展开必须放在这里而不是各个写入分支里：后面「按配置项分组读写」
         * 只认真正对应 JSON 字段的参数，虚拟参数在那里没有字段可写，
         * 会被静默跳过 —— 表现为「保存成功但画面没变」。
         */
        Map<String, Object> expanded = expandVirtual(values);
        /*
         * 「接近焦距极限 = 自动」要额外把 FocusLimitSelectMode 置为 Auto。
         *
         * 设备界面把这 13 项做成<b>一个</b>下拉，但实际上跨了两个字段：
         *   选「自动」  → FocusLimitSelectMode=Auto（距离值保持原样）
         *   选具体距离  → FocusLimit=值 且 FocusLimitSelectMode=Manual
         * 只写 FocusLimit 的话，选了「自动」等于没改 ——
         * 设备照旧按原来的限制值工作。
         */
        expanded.putAll(focusLimitSideEffects(expanded));

        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, channelCode);
        boolean needRestart = false;
        Map<String, Object> notEffective = new LinkedHashMap<>();
        /* 设备侧功能互斥等「不是字段错」的原因说明（key → 可操作提示）。 */
        Map<String, String> exclusionHints = new LinkedHashMap<>();

        for (String command : DahuaCameraConfig.commandsOf(known)) {
            // ① 读全量
            String json;
            try {
                json = dahuaCapability.ext().readConfig(session, command, sdkChannel);
            } catch (DahuaSdkException e) {
                throw dahuaCapability.translate("读取配置 " + command, e);
            }
            if (json == null || json.isBlank()) {
                throw BizException.badRequest(
                        "设备未返回配置项 " + command + "（该型号可能不支持）");
            }
            // ② 改字段
            JsonNode root;
            try {
                root = mapper.readTree(json);
            } catch (Exception e) {
                throw BizException.badRequest("配置项 " + command + " 的返回不是合法 JSON，拒绝写入");
            }
            List<String> changed = new ArrayList<>();
            for (VarEntry e : entriesFor(expanded, command)) {
                DahuaCameraConfig.Param p = e.param();
                Object v = e.value();
                /*
                 * 开关型但底层是字符串时，先翻成设备要的字串再写。
                 * 直接写布尔 true 会落成字符串 "true"，设备不认可（只认 Auto/Off）。
                 */
                Object toWrite = writeValueForParam(p, v);
                /*
                 * 单值参数（亮度/锐度/缩放速度/强度…）先校验范围。
                 *
                 * 滑块只在界面层夹取，接口可以绕过它；越界值写到设备上会被静默忽略，
                 * 最后报「可能做成了只读」，把用户引到错误方向。
                 */
                if (p.type() == DahuaCameraConfig.Type.NUMBER) {
                    validateNumber(p, v);
                }
                /*
                 * 范围参数要拆成两次写：下限字段写 v[0]，上限字段写 v[1]。
                 * 当成单值写会把同一个数字塞进两个字段（增益上下限都变成 50）。
                 */
                int written;
                if (p.type() == DahuaCameraConfig.Type.RANGE) {
                    written = writeRange(root, p, v);
                } else if (isShutterSpeed(p)) {
                    /*
                     * 快门：界面给分母，设备存毫秒，且要同时写 Value1 与 Value2。
                     *
                     * 不能走 writeValueAll(p.fields())：那里「同一对象只写第一个命中的字段」，
                     * 结果只改了 Value1、Value2 还是旧值 —— 读回时两个值不等，
                     * 会被当成「自定义范围」，用户选的 1/50 反而变了「自定义」。
                     *
                     * 选「自定义」时不写：真正生效靠用户在下方填的毫秒范围（shutterRange）。
                     */
                    /*
                     * 50Hz / 60Hz 下快门必须与工频同步，自由值会让防闪烁失效（画面频闪）。
                     * 设备不拦（Value1 是自由浮点字段），所以平台必须拦。
                     *
                     * <b>必须在「自定义」分支之前判</b>：那个分支会 continue 掉，
                     * 放在后面等于「自定义」完全绕过校验。
                     */
                    String af = String.valueOf(coerceForType(DahuaCameraConfig.Type.ENUM,
                            readValue(root, List.of("AntiFlicker"))));
                    boolean hzLocked = DahuaCameraConfig.ANTI_FLICKER_50HZ.equals(af)
                            || DahuaCameraConfig.ANTI_FLICKER_60HZ.equals(af);
                    List<DahuaCameraConfig.Option> allowedOpts =
                            DahuaCameraConfig.shutterOptions(af);
                    String chosen = String.valueOf(v).trim();

                    if (isShutterCustom(v)) {
                        if (!DahuaCameraConfig.shutterSupportsCustom(af)) {
                            throw BizException.badRequest(String.format(
                                    "抗闪烁设为 %s 时不能使用自定义快门（防闪烁要求快门与工频同步），"
                                            + "可选：%s",
                                    DahuaCameraConfig.ANTI_FLICKER_50HZ.equals(af) ? "50Hz" : "60Hz",
                                    allowedOpts.stream().map(DahuaCameraConfig.Option::label)
                                            .toList()));
                        }
                        changed.add(p.key() + "→自定义（由快门范围写入）");
                        continue;
                    }
                    Double ms = DahuaCameraConfig.shutterMillis(v);
                    if (ms == null) {
                        throw BizException.badRequest(
                                "快门取值非法：" + v + "（应为档位分母如 50，或 custom）");
                    }
                    if (hzLocked && allowedOpts.stream().noneMatch(o -> o.value().equals(chosen))) {
                        throw BizException.badRequest(String.format(
                                "抗闪烁设为 %s 时，快门只能取 %s（防闪烁要求快门与工频同步）",
                                DahuaCameraConfig.ANTI_FLICKER_50HZ.equals(af) ? "50Hz" : "60Hz",
                                allowedOpts.stream().map(DahuaCameraConfig.Option::label).toList()));
                    }
                    written = writeValueAll(root, List.of("Value1"), ms)
                            + writeValueAll(root, List.of("Value2"), ms);
                } else if (isFocusLimitAuto(p, v)) {
                    /*
                     * 「自动」不进 FocusLimit（整数字段），
                     * 真正的生效靠 focusLimitSideEffects 追加的 focusLimitMode=Auto。
                     */
                    changed.add(p.key() + "→" + DahuaCameraConfig.KEY_FOCUS_LIMIT_MODE_FIELD
                            + "=Auto（距离值保持原样）");
                    continue;
                } else {
                    written = writeValueAll(root, p.fields(), toWrite);
                }
                if (written == 0) {
                    throw BizException.badRequest("配置项 " + command
                            + " 里找不到字段 " + p.fields()
                            + "（该型号可能不支持参数 " + p.key() + "）");
                }
                changed.add(p.key() + "→" + p.fields().get(0) + "=" + toWrite + "（" + written + " 处）");
            }
            if (changed.isEmpty()) {
                continue;
            }
            /*
             * ③ 整段写回 —— 必须序列化<b>根节点</b>，不能序列化被修改的子对象。
             *
             * 只写回子对象会把 id/params 那层结构整个丢掉，设备收到后判定非法。
             */
            DahuaSdkException sdkRejected = null;
            try {
                boolean restart = dahuaCapability.ext().writeConfig(session, command, sdkChannel,
                        mapper.writeValueAsString(root));
                needRestart = needRestart || restart;
            } catch (DahuaSdkException e) {
                /*
                 * 不直接抛：场景数组类配置项在部分型号上这个入口就是不可用的
                 * （设备返回 err=12，连原样写回都拒），但它的 Web CGI 能写。
                 * 先记下，等会儿改走 CGI 再判生死。
                 */
                sdkRejected = e;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw BizException.badRequest("配置序列化失败：" + e.getOriginalMessage());
            }
            if (sdkRejected == null) {
                log.info("大华相机参数已写入（SDK）: device={} channel={} command={} 改动={}",
                        deviceId, channelCode, command, changed);
            }
            /*
             * ④ 回读校验：把「写入」和「生效」分开说。
             *
             * <b>这里只保留两种去向</b>：
             * <ul>
             *   <li>读回已是目标值 → 完成</li>
             *   <li>还没读到目标值 → 进 pending，交给下面的 CGI 兜底</li>
             * </ul>
             * 不要在这里按 {@code sdkRejected} 提前 continue ——
             * 「SDK 写失败」正是<b>最需要</b>走兜底的情况，
             * 早退会让什么都没写进去却对前端报 saved:true。
             */
            List<VarEntry> pending = new ArrayList<>();
            for (VarEntry e : entriesFor(expanded, command)) {
                if (!confirmByRetry(session, command, sdkChannel, e)) {
                    pending.add(e);
                }
            }
            /*
             * ⑤ SDK 写失败、或写了没生效 → 改走设备自己的 CGI 通道。
             *
             * 实测依据：同一批参数（VideoColor 的亮度、VideoInSharpness 的锐度…）
             * 用 CLIENT_SetNewDevConfig 一律 err=12，而设备 Web 界面
             * 用 configManager.cgi?action=setConfig&VideoColor[0][0].Brightness=60 改得好好的。
             * 所以不是参数不可写，是这个 SDK 入口对数组型配置不适用。
             */
            if (!pending.isEmpty()) {
                pending = writeViaCgi(session, command, sdkChannel, pending);
            }
            for (VarEntry e : pending) {
                /*
                 * 回读值也要做「字符串 → 开关」归一，否则前端看到的 rejected
                 * 会是 Auto/Off 这种原始字串，和界面上的开关对不上。
                 *
                 * 范围/快门要按各自的读法取：单值 readBack 只拿到下限（甚至毫秒），
                 * 前端会显示成「[10, 50] 被拒，设备实际是 1.0」这种看不懂的东西。
                 */
                Object got;
                if (e.param().type() == DahuaCameraConfig.Type.RANGE) {
                    got = readRangeBack(session, command, sdkChannel, e.param());
                } else if (isShutterSpeed(e.param())) {
                    got = readShutterSpeedBack(session, command, sdkChannel, e.param());
                } else {
                    got = coerceForParam(e.param(),
                            readBack(session, command, sdkChannel, e.param().fields()));
                }
                notEffective.put(e.param().key(), got);
            }
            if (sdkRejected != null && !pending.isEmpty()) {
                log.warn("大华相机参数写入失败（SDK 与 CGI 都未生效）: device={} channel={} command={} {}",
                        deviceId, channelCode, command, sdkRejected.getMessage());
            }
            /*
             * EIS 失败时补一条可操作提示，但<b>不预设原因</b>。
             *
             * 设备确实有「电子防抖 ↔ 智慧移动侦测」互斥规则（抓包见过
             * {@code DevVariableCapsManager.checkStart} 返回
             * {@code ExclusionType:["SmartMotion","ElecStable"]}），
             * 但那是<b>开启智慧移动侦测时</b>才生效的条件。
             * 关掉之后 EIS 就能正常开（实测 {@code VideoImageControl[0].Stable=1} 返回 OK）。
             *
             * 所以这里只能写成「若仍失败，检查是否…」，
             * 早期版本无条件断言「设备启用了移动侦测」，在用户已经关掉该功能时是错的。
             */
            if (pending.stream().anyMatch(e -> "eis".equals(e.param().key()))) {
                exclusionHints.put("eis",
                        "EIS 未生效。若设备上开启了「智能 → 移动侦测」，需先关闭它，"
                                + "两者互斥；否则请检查设备是否支持电子防抖");
            }
            /*
             * 背光=宽动态失败时，先查透雾。
             *
             * 设备规则（界面原话）：「開啟透霧時，無法設定寬動態。」
             * 抓 RPC2 验证：透雾处于开启态时往 VideoInBacklight.Mode 写 WideDynamic
             * 会被拒；把透雾改回关闭后同一次写入立刻成功。
             * 早期因为枚举里给了个设备不认的 Manual 值，透雾「一直算开着」，
             * 连带把宽动态也挡住了 —— 两个 bug 是同一个根。
             */
            if (pending.stream().anyMatch(e -> "backlightMode".equals(e.param().key()))) {
                Object want = values.get("backlightMode");
                if (want != null && "WideDynamic".equalsIgnoreCase(String.valueOf(want).trim())) {
                    exclusionHints.put("backlightMode",
                            "宽动态未生效。设备规定「开启透雾时无法设置宽动态」，"
                                    + "请先把「透雾 → 模式」改回「关闭」再保存");
                }
            }
        }
        /*
         * 组合参数的结果要按组合名回报，不能把三个成员字段各自抛给前端：
         * 用户点的是「倒影」，看到「flip 未生效」会以为平台记错了参数名。
         * 这里只在三个成员全都没生效时才判定组合失败。
         */
        for (String k : known) {
            DahuaCameraConfig.Param p = DahuaCameraConfig.byKey(k);
            if (p == null || !p.virtual()) {
                continue;
            }
            List<DahuaCameraConfig.Param> members = DahuaCameraConfig.membersOf(k);
            if (members.isEmpty()) {
                continue;
            }
            boolean allRejected = true;
            for (DahuaCameraConfig.Param m : members) {
                if (!notEffective.containsKey(m.key())) {
                    allRejected = false;
                    break;
                }
            }
            if (allRejected) {
                for (DahuaCameraConfig.Param m : members) {
                    notEffective.remove(m.key());
                }
                notEffective.put(k, values.get(k));
            }
        }
        return new WriteResult(needRestart, notEffective, exclusionHints);
    }

    /**
     * 范围参数写入：下限写第一个字段，上限写 {@code #} 标记的那个字段。
     *
     * @param v 期望是长度 &gt;= 2 的数组（{@code [min, max]}）或 {@code "min,max"} 字符串
     * @return 实际写入处数（{@code 0} = 字段不存在）
     */
    private int writeRange(JsonNode root, DahuaCameraConfig.Param p, Object v) {
        List<Number> pair = asRangePair(v);
        if (pair == null) {
            throw BizException.badRequest(
                    "参数 " + p.key() + " 需要 [最小值, 最大值] 两个数，收到：" + v);
        }
        validateRange(p, pair);
        String minField = DahuaCameraConfig.rangeMinField(p);
        String maxField = DahuaCameraConfig.rangeMaxField(p);
        int n = writeValueAll(root, List.of(minField), pair.get(0));
        n += writeValueAll(root, List.of(maxField), pair.get(1));
        return n;
    }

    /**
     * 把各种形态的范围值归一成 {@code [min, max]}；非法时返回 {@code null}。
     *
     * <p><b>保留小数</b>：快门范围的单位是毫秒，设备自己存 2 位小数
     * （1/215 = 4.65、1/90 = 11.11）。早期统一 {@code asInt} 会把它取整成
     * 4 / 11，用户存进去的 `15 ~ 35` 正常，但重新保存 4.65 会变成 4。
     */
    private static List<Number> asRangePair(Object v) {
        if (v instanceof List<?> list && list.size() >= 2) {
            return List.of(asNumber(list.get(0)), asNumber(list.get(1)));
        }
        if (v instanceof int[] arr && arr.length >= 2) {
            return List.of(arr[0], arr[1]);
        }
        if (v instanceof Number[] arr && arr.length >= 2) {
            return List.of(asNumber(arr[0]), asNumber(arr[1]));
        }
        if (v instanceof String s && s.contains(",")) {
            String[] parts = s.split(",");
            if (parts.length >= 2) {
                return List.of(asNumber(parts[0].trim()), asNumber(parts[1].trim()));
            }
        }
        return null;
    }

    /** 一条「参数定义 + 目标值」，写入时按配置项分组用。 */
    private record VarEntry(DahuaCameraConfig.Param param, Object value) {
    }

    /**
     * 校验范围参数的两个值：各自落在 {@code [min, max]} 内，且下限不大于上限。
     *
     * <h3>为什么必须在这里挡</h3>
     * 设备确实会拒绝越界值，但它的拒绝表现为「写入被忽略、回读仍是原值」，
     * 于是平台只能报「设备接受了指令但没有应用，该型号可能把这些项做成了只读」——
     * 这是一句<b>误导</b>：参数完全可写，只是值不合法。
     * 用户照着这句话去查「只读」永远查不出来。
     *
     * <p>实测（固件 3.201.0000007.1.R）：显卡为光圈优先时写
     * {@code IrisMin=10, IrisMax=200} 与 {@code 80/20}（下限大于上限）
     * 都被设备静默忽略，回读保持 {@code [10, 50]}。
     *
     * <p>前端 {@code el-input-number} 已经把输入夹在 {@code [min, max]}，
     * 但接口可以直接调用、旧草稿也可能带着越界值提交，所以服务端这一层不能省。
     */
    private static void validateRange(DahuaCameraConfig.Param p, List<Number> pair) {
        double lo = pair.get(0).doubleValue();
        double hi = pair.get(1).doubleValue();
        Integer min = p.min();
        Integer max = p.max();
        if (min != null && max != null) {
            if (lo < min || lo > max || hi < min || hi > max) {
                throw BizException.badRequest(String.format(
                        "%s 的取值范围是 %d~%d，收到 [%s, %s]", p.label(), min, max,
                        trimNumber(lo), trimNumber(hi)));
            }
        }
        if (lo > hi) {
            throw BizException.badRequest(String.format(
                    "%s 的最小值不能大于最大值，收到 [%s, %s]", p.label(),
                    trimNumber(lo), trimNumber(hi)));
        }
    }

    /** 数字转文本时去掉多余的 {@code .0}（用户填 150，不该回显 150.0）。 */
    private static String trimNumber(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /**
     * 校验单值参数落在 {@code [min, max]} 内。
     *
     * <p>与 {@link #validateRange} 同理：滑块只在界面层夹取，接口不管的话
     * 越界值会安静地写到设备上（设备对部分字段不做校验，直接收下）。
     */
    private static void validateNumber(DahuaCameraConfig.Param p, Object value) {
        Integer min = p.min();
        Integer max = p.max();
        if (min == null || max == null || value == null) {
            return;
        }
        double v;
        try {
            v = asDouble(value);
        } catch (RuntimeException e) {
            throw BizException.badRequest(p.label() + " 需要数字，收到：" + value);
        }
        if (v < min || v > max) {
            // 整数不要显示成 150.0 —— 用户填的是 150，回显 150.0 会让人以为平台改了值
            String shown = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
            throw BizException.badRequest(String.format(
                    "%s 的取值范围是 %d~%d，收到 %s", p.label(), min, max, shown));
        }
    }

    /**
     * 「接近焦距极限」的连带写入。
     *
     * <p>设备界面上它是一 个下拉（自动 + 12 个距离），底层却是两个字段：
     * <ul>
     *   <li>自动 → {@code FocusLimitSelectMode=Auto}</li>
     *   <li>距离 → {@code FocusLimit=值} 且 {@code FocusLimitSelectMode=Manual}</li>
     * </ul>
     * 只写 {@code FocusLimit} 时，选「自动」等于没改 —— 设备仍按原来的限制值工作。
     *
     * @return 需要额外写入的「字段 key → 值」，由调用方并入待写集合
     */
    private Map<String, Object> focusLimitSideEffects(Map<String, Object> values) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (!values.containsKey("focusLimit")) {
            return extra;
        }
        Object v = values.get("focusLimit");
        boolean auto = v != null
                && DahuaCameraConfig.FOCUS_LIMIT_AUTO.equalsIgnoreCase(String.valueOf(v).trim());
        extra.put("focusLimitMode", auto ? "Auto" : "Manual");
        return extra;
    }

    /**
     * 改走设备 Web CGI 写入，返回<b>仍然没生效</b>的那些参数。
     *
     * <h3>为什么要这条兜底</h3>
     * 部分型号（实测固件 {@code 3.201.0000007.1.R} 的 IPC）对<b>场景数组</b>
     * 类配置项（{@code VideoColor} / {@code VideoInSharpness} / {@code VideoInExposure}
     * / {@code VideoInWhiteBalance} / {@code VideoInDenoise}）的
     * {@code CLIENT_SetNewDevConfig} 一律返回 {@code err=12}，
     * 连「读出来原样写回」都被拒；而设备 Web 界面改同一批参数是成功的。
     * 所以不是参数不可写，是这个 SDK 入口对数组型配置不适用。
     *
     * <h3>怎么对应字段</h3>
     * CGI 的 {@code getConfig} 返回的是一行行
     * {@code VideoColor[0][0].Brightness=50}，场景是第二维下标。
     * 这里按「键以 {@code .字段路径} 结尾」匹配，<b>命中几处就写几处</b> ——
     * 与 SDK 路径「所有场景一起改」的语义保持一致，否则用户改了白天、夜里没变。
     *
     * <p>带 {@code params.table.} 的键要跳过：那是早期误把整包响应写进设备留下的，
     * 再写它只是继续污染。
     *
     * @return 写了但回读仍不符合的条目（空 = 全部生效）
     */
    private List<VarEntry> writeViaCgi(
            io.github.rurulalanana.dahua.internal.DahuaSdk.Session session,
            String command, int sdkChannel, List<VarEntry> pending) {
        Map<String, String> lines;
        try {
            lines = dahuaCapability.cgi().read(session, command, dahuaCapability.httpPort());
        } catch (DahuaSdkException e) {
            log.debug("CGI 兜底不可用，保持 SDK 结果: {} {}", command, e.getMessage());
            return pending;
        }
        Map<String, String> kv = new LinkedHashMap<>();
        List<VarEntry> unresolved = new ArrayList<>();
        for (VarEntry e : pending) {
            // focusLimit=自动 不写 FocusLimit（整数字段），改由 focusLimitMode 那条生效
            if (isFocusLimitAuto(e.param(), e.value())) {
                continue;
            }
            /*
             * 快门要换算成毫秒，而且 Value1 / Value2 两个键都得写。
             *
             * 走下面的通用匹配只会命中 Value1（匹配到第一个就 break），
             * Value2 留旧值 → 读回两个值不等 → 被判成「自定义范围」，
             * 用户选的 1/50 会变成「自定义」。
             */
            if (isShutterSpeed(e.param())) {
                if (isShutterCustom(e.value())) {
                    continue;   // 自定义由快门范围那条负责
                }
                Double ms = DahuaCameraConfig.shutterMillis(e.value());
                if (ms == null) {
                    unresolved.add(e);
                    continue;
                }
                int hits = 0;
                for (String key : lines.keySet()) {
                    if (key.contains(".params.")) {
                        continue;
                    }
                    if (key.endsWith(".Value1") || key.endsWith(".Value2")) {
                        kv.put(key, cgiText(ms, lines.get(key)));
                        hits++;
                    }
                }
                if (hits == 0) {
                    unresolved.add(e);
                }
                continue;
            }
            /*
             * 范围参数要拆成「下限字段=下限值 / 上限字段=上限值」两笔写。
             *
             * 走下面的通用匹配会把整个 {@code [min, max]} 数组当单值塞进第一个字段
             * （{@code GainMin=[10,80]}），设备直接 Bad Request；
             * 而且上限字段名带 {@code #} 前缀，{@code .endsWith(".#GainMax")} 永远不命中，
             * 上限根本不会被写到。表现是「增益/光圈范围改了没反应」。
             */
            if (e.param().type() == DahuaCameraConfig.Type.RANGE) {
                List<Number> pair = asRangePair(e.value());
                String minField = DahuaCameraConfig.rangeMinField(e.param());
                String maxField = DahuaCameraConfig.rangeMaxField(e.param());
                if (pair == null || minField == null || maxField == null) {
                    unresolved.add(e);
                    continue;
                }
                int hits = 0;
                for (String key : lines.keySet()) {
                    if (key.contains(".params.")) {
                        continue;
                    }
                    if (key.endsWith(".".concat(minField))) {
                        kv.put(key, cgiText(pair.get(0), lines.get(key)));
                        hits++;
                    } else if (key.endsWith(".".concat(maxField))) {
                        kv.put(key, cgiText(pair.get(1), lines.get(key)));
                        hits++;
                    }
                }
                if (hits == 0) {
                    unresolved.add(e);
                }
                continue;
            }
            String matched = null;
            for (String field : e.param().fields()) {
                String suffix = ".".concat(field);
                for (String key : lines.keySet()) {
                    if (key.contains(".params.")) {
                        continue;
                    }
                    if (key.endsWith(suffix)) {
                        /*
                         * 值必须按<b>目标字段当前值的形态</b>来写，不能按用户输入的类型。
                         *
                         * 典型坑：{@code eis} 在参数表里是 BOOLEAN，用户传 true，
                         * 但设备的 {@code VideoImageControl[0].Stable} 是整数 0/1 ——
                         * 写 "true" 设备返回 Bad Request 并静默保持原值，
                         * 表现为「开了 EIS 但一刷新又变回关」。
                         *
                         * <p>开关型但底层是字符串的（3DType=Auto/Off）要先行映射，
                         * 否则会写成 "true"，设备只认 Auto/Off。
                         */
                        kv.put(key, cgiText(writeValueForParam(e.param(), e.value()), lines.get(key)));
                        matched = field;
                    }
                }
                if (matched != null) {
                    break;
                }
            }
            if (matched == null) {
                unresolved.add(e);
            }
        }
        if (kv.isEmpty()) {
            return unresolved;
        }
        try {
            dahuaCapability.cgi().write(session, kv, dahuaCapability.httpPort());
            log.info("大华相机参数已写入（CGI 兜底）: command={} 键数={}", command, kv.size());
        } catch (DahuaSdkException e) {
            log.warn("大华 CGI 兜底写入失败: command={} {}", command, e.getMessage());
            unresolved.addAll(pending);
            return unresolved;
        }
        // 回读确认；CGI 声称 OK 也可能是「接受但没改」
        List<VarEntry> stillWrong = new ArrayList<>(unresolved);
        for (VarEntry e : pending) {
            if (unresolved.contains(e)) {
                continue;
            }
            if (!confirmByRetry(session, command, sdkChannel, e)) {
                stillWrong.add(e);
            }
        }
        return stillWrong;
    }

    /**
     * 回读确认，允许设备慢半拍。
     *
     * <h3>为什么不能只读一次</h3>
     * 设备的配置写入是<b>异步生效</b>的：CGI 已经返回 {@code OK}、设备自己
     * {@code getConfig} 也能读到新值，但紧接着用 SDK {@code GetNewDevConfig}
     * 读回来仍是旧值（实测 {@code VideoInOptions.Flip} 就是如此）。
     *
     * <p>只读一次会把「已经改成、只是还没同步给 SDK」误判成「设备没应用」，
     * 于是接口返回 {@code saved:false}，前端把面板刷成设备旧值 ——
     * 用户看到自己刚调好的值跳回去，只好再点一次保存，来回反复。
     * 这就是「一点保存界面就鬼畜」的直接来源。
     *
     * <p>所以允许最多 3 次、每次间隔 250ms 的确认窗口。
     * 设备真没接受的话三次都读不到新值，结论依然如实返回，不会变成假成功。
     */
    private boolean confirmByRetry(
            io.github.rurulalanana.dahua.internal.DahuaSdk.Session session,
            String command, int sdkChannel, VarEntry e) {
        /*
         * 「接近焦距极限 = 自动」要校验的是 FocusLimitSelectMode，而不是 FocusLimit。
         * FocusLimit 此时根本不会被写（保持旧距离值），拿它去比永远不相等，
         * 会白跑三次重试再白跑一遍 CGI 兜底，最后报 saved:false。
         */
        if (isFocusLimitAuto(e.param(), e.value())) {
            DahuaCameraConfig.Param modeParam =
                    DahuaCameraConfig.byKey(DahuaCameraConfig.KEY_FOCUS_LIMIT_MODE);
            if (modeParam != null) {
                e = new VarEntry(modeParam, DahuaCameraConfig.FOCUS_LIMIT_AUTO);
            }
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            /*
             * 回读值必须先按参数定义归一，再和用户提交的值比。
             *
             * 反例：3D 降噪是开关（用户提交 true），底层存的是 "Auto"。
             * 直接拿 "Auto" 去比 true 会因为 asBool("Auto")==false 而判成「没生效」，
             * 于是每次都白跑一遍 CGI 兜底、最后还报 saved:false。
             */
            Object got;
            if (e.param().type() == DahuaCameraConfig.Type.RANGE) {
                got = readRangeBack(session, command, sdkChannel, e.param());
            } else if (isShutterSpeed(e.param())) {
                /*
                 * 快门要同时看 Value1 和 Value2 才能判断「具体档位」还是「自定义范围」，
                 * 只读单值永远判不出后者。
                 */
                got = readShutterSpeedBack(session, command, sdkChannel, e.param());
            } else {
                got = coerceForParam(e.param(),
                        readBack(session, command, sdkChannel, e.param().fields()));
            }
            if (sameValue(e.value(), got)) {
                return true;
            }
            if (attempt == 3) {
                return false;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 范围参数的设备当前值（{@code [min, max]}）；任一读不到返回 {@code null}。 */
    private Object readRangeBack(
            io.github.rurulalanana.dahua.internal.DahuaSdk.Session session,
            String command, int sdkChannel, DahuaCameraConfig.Param p) {
        Object min = readBack(session, command, sdkChannel,
                List.of(DahuaCameraConfig.rangeMinField(p)));
        Object max = readBack(session, command, sdkChannel,
                List.of(DahuaCameraConfig.rangeMaxField(p)));
        if (min == null || max == null) {
            return null;
        }
        try {
            // 保留小数：快门范围的毫秒值是 2 位小数（1/215 = 4.65），取整会对不上
            return List.of(asNumber(min), asNumber(max));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 快门的设备当前值（分母字符串或 {@code custom}）。 */
    private Object readShutterSpeedBack(
            io.github.rurulalanana.dahua.internal.DahuaSdk.Session session,
            String command, int sdkChannel, DahuaCameraConfig.Param p) {
        try {
            JsonNode root = parse(dahuaCapability.ext().readConfig(session, command, sdkChannel));
            /*
             * 回读校验用<b>室外全集</b>（传 null）：校验的语义是「这个毫秒值
             * 对应哪个快门」，不该受当前抗闪烁的可用列表影响。
             * 举例：50Hz 下存量 8.33ms（非 50Hz 档位），用 50Hz 列表会匹配不上，
             * 反算得到 120 —— 依然正确。两者结果一致，但全集路径少一次例外。
             */
            return readShutterSpeed(root, p, null);
        } catch (Exception e) {
            log.debug("回读快门失败（不影响写入结果）: {}", e.toString());
            return null;
        }
    }

    /**
     * 值转成 CGI 需要的文本形态，<b>按目标字段现有的形态</b>对齐。
     *
     * <h3>为什么必须看现有值</h3>
     * CGI 是纯字符串接口，设备对类型很挑：
     * <pre>
     *   VideoImageControl[0].Flip    = false   → 必须写 true/false
     *   VideoImageControl[0].Stable  = 0       → 必须写 1/0（写 true 会 Bad Request）
     * </pre>
     * 两者在参数表里都是 {@code BOOLEAN}（界面上都是开关），
     * 所以「控件类型」判断不出该写哪种，只能看设备当前值的字面形态。
     *
     * <p>实测踩坑：{@code eis} 传 {@code true} 时写成 {@code Stable=true}，
     * 设备返回 Bad Request 并静默保持原值 —— 表现为「开了 EIS 一刷新又变回关」。
     *
     * @param v    目标值（可能是 Boolean / Number / String）
     * @param curr 该字段当前的文本值（如 {@code "0"} / {@code "false"}）；可为 {@code null}
     */
    private static String cgiText(Object v, String curr) {
        String cur = curr == null ? "" : curr.trim();
        boolean curIsBool = "true".equalsIgnoreCase(cur) || "false".equalsIgnoreCase(cur);
        boolean curIsInt = looksLikeInt(cur);

        if (v instanceof Boolean b) {
            // 目标字段是整数型（0/1）时，布尔要落成 1/0
            if (!curIsBool && curIsInt) {
                return b ? "1" : "0";
            }
            return b ? "true" : "false";
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            String num = d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
            // 目标字段是布尔型时，数字要落成 true/false
            if (curIsBool) {
                return d == 0 ? "false" : "true";
            }
            return num;
        }
        return String.valueOf(v);
    }

    /** 是否看起来像整数（空串与含点号的都不算）。 */
    private static boolean looksLikeInt(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int i = s.startsWith("-") ? 1 : 0;
        if (i >= s.length()) {
            return false;
        }
        for (; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把组合参数展开成真实字段参数。
     *
     * <p>只有 {@code viewMode} 一个组合参数：{@code viewMode=1} 展开成
     * {@code flip=true} / {@code mirror=false} / {@code rotate90=false}。
     * 其余 key 原样保留。
     */
    private Map<String, Object> expandVirtual(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            DahuaCameraConfig.Param p = DahuaCameraConfig.byKey(e.getKey());
            if (p == null || !p.virtual()) {
                out.put(e.getKey(), e.getValue());
                continue;
            }
            int mode = (int) asDouble(e.getValue());
            boolean[] bits = DahuaCameraConfig.expandViewMode(mode);
            List<DahuaCameraConfig.Param> members = DahuaCameraConfig.membersOf(p.key());
            for (int i = 0; i < members.size() && i < bits.length; i++) {
                out.put(members.get(i).key(), bits[i]);
            }
        }
        return out;
    }

    /** 取属于指定配置项的那些「参数 + 值」。 */
    private List<VarEntry> entriesFor(Map<String, Object> values, String command) {
        List<VarEntry> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            DahuaCameraConfig.Param p = DahuaCameraConfig.byKey(e.getKey());
            if (p != null && !p.virtual() && command.equals(p.configCommand())) {
                out.add(new VarEntry(p, e.getValue()));
            }
        }
        return out;
    }

    /**
     * 回读确认本次写入是否真的生效。
     *
     * <p><b>为什么必须有这一步</b>：大华的 {@code CLIENT_SetNewDevConfig}
     * 返回 {@code true} 只代表「设备收到了」，不代表「值改了」。
     * 实测某型 IPC 的视角字段（Flip/Mirror/Rotate90）就是这个行为：
     * 写入返回成功、读回永远保持原值 —— 该型号把它们做成了只读。
     * 不校验就会让前端显示「保存成功」，而画面丝毫没有变化。
     *
     * @param session  会话
     * @param command  配置项
     * @param channel  SDK 通道号
     * @param fields   候选字段名
     * @return 读回的值；读不到返回 {@code null}
     */
    private Object readBack(io.github.rurulalanana.dahua.internal.DahuaSdk.Session session,
                            String command, int channel, List<String> fields) {
        try {
            String json = dahuaCapability.ext().readConfig(session, command, channel);
            return readValue(parse(json), fields);
        } catch (Exception e) {
            log.debug("回读配置失败（不影响写入结果）: {} {}", command, e.toString());
            return null;
        }
    }

    /** 值比较：数字按数值比（{@code 1} 与 {@code 1.0} 等价），布尔按语义比。 */
    private static boolean sameValue(Object want, Object got) {
        if (want == null) {
            return true;
        }
        if (got == null) {
            return false;
        }
        if (want instanceof Boolean || got instanceof Boolean) {
            return asBool(want) == asBool(got);
        }
        if (want instanceof Number || got instanceof Number) {
            try {
                return Math.abs(asDouble(want) - asDouble(got)) < 1e-6;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return String.valueOf(want).equalsIgnoreCase(String.valueOf(got));
    }

    // ==================== 内部 ====================

    /** 读出所有涉及的配置项原始 JSON。 */
    private Map<String, String> readAllConfigs(io.github.rurulalanana.dahua.internal.DahuaSdk.Session session,
                                               int sdkChannel) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String command : DahuaCameraConfig.commandsOf(
                DahuaCameraConfig.all().stream().map(DahuaCameraConfig.Param::key).toList())) {
            try {
                out.put(command, dahuaCapability.ext().readConfig(session, command, sdkChannel));
            } catch (DahuaSdkException e) {
                // 单个配置项读不到不影响其他项：不同型号支持的配置项本来就不同
                log.debug("读配置项失败（该型号可能不支持）: {} {}", command, e.getMessage());
                out.put(command, "");
            }
        }
        return out;
    }

    /** 解析成树；空或非法时返回 {@code null}（调用方按「没有该字段」处理）。 */
    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.debug("配置 JSON 解析失败: {}", e.toString());
            return null;
        }
    }

    /**
     * 写值到<b>整棵树里所有出现该字段的对象</b>，返回实际写入处数。
     *
     * <h3>为什么必须递归、而且要写全部</h3>
     * 实测某型 IPC 的 {@code VideoInOptions} 结构：
     * <pre>
     * {"params":{"table":{
     *     "Flip":false,                          ← 顶层
     *     "NormalOptions":{"Flip":false, ...},   ← 正常场景
     *     "NightOptions":{"Flip":false, ...}     ← 夜间场景
     * }}}
     * </pre>
     * 只改顶层时 {@code CLIENT_SetNewDevConfig} 返回成功、读回顶层也确实变了，
     * 但<b>画面不翻转</b> —— 设备实际用的是场景里的那一份。
     *
     * <p>探针实测：把三处 {@code Flip} 一起改成 {@code true} 后，
     * 读回出现 {@code "Flip":true}，还原后消失，说明<b>全量写才生效</b>。
     *
     * <p>同理，{@code VideoInExposure} 的 {@code params.table} 是
     * Day/Night/Normal 等场景数组，每个场景一份独立参数；
     * 用户点「调曝光」的意思是整台相机都调，只改第一个会让夜间画面不变。
     *
     * @return 实际写入的对象数；{@code 0} 表示整棵树里都没有该字段
     */
    private int writeValueAll(JsonNode root, List<String> fields, Object value) {
        return writeRecursive(root, fields, value);
    }

    /** 深度优先：每个对象里找到第一个命中的候选字段就改这一处，然后继续往下。 */
    private int writeRecursive(JsonNode node, List<String> fields, Object value) {
        if (node == null) {
            return 0;
        }
        int n = 0;
        if (node instanceof ObjectNode obj) {
            for (String f : fields) {
                int wrote = writeAt(obj, f, value);
                if (wrote > 0) {
                    n += wrote;
                    break; // 同一对象只写一个候选字段
                }
            }
            for (JsonNode child : obj) {
                n += writeRecursive(child, fields, value);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                n += writeRecursive(child, fields, value);
            }
        }
        return n;
    }

    /**
     * 在对象里按路径写值，返回写入处数（0 = 路径不存在）。
     *
     * <p>路径语法与 {@link #nodeAt} 一致，支持 {@code A.B} 与 {@code A[0].B}。
     *
     * <p>只改<b>已存在</b>且为<b>标量</b>的字段：大华的配置里字段必须预先存在，
     * 凭空加一个设备不认识的字段会被忽略（或整段拒绝），且不报错。
     *
     * <p>类型按<b>原值的 JSON 类型</b>写回（布尔写布尔、数字写数字），
     * 而不是按用户输入的类型 —— 否则 `true` 写成字符串 `"true"` 设备会当非法值忽略。
     */
    private static int writeAt(ObjectNode obj, String path, Object value) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        String rest = dot < 0 ? null : path.substring(dot + 1);

        // 先定位到「真正承载字段的那个对象」
        ObjectNode holder = obj;
        String leaf = head;
        int bracket = head.indexOf('[');
        if (bracket > 0 && head.endsWith("]")) {
            String name = head.substring(0, bracket);
            int idx;
            try {
                idx = Integer.parseInt(head.substring(bracket + 1, head.length() - 1).trim());
            } catch (NumberFormatException ex) {
                return 0;
            }
            JsonNode arr = obj.get(name);
            if (arr == null || !arr.isArray() || idx >= arr.size()) {
                return 0;
            }
            JsonNode el = arr.get(idx);
            if (!(el instanceof ObjectNode elObj)) {
                return 0;
            }
            if (rest == null) {
                return 0;   // 目标是数组元素本身，不是字段
            }
            return writeAt(elObj, rest, value);
        }
        if (rest != null) {
            JsonNode child = holder.get(leaf);
            if (!(child instanceof ObjectNode childObj)) {
                return 0;
            }
            return writeAt(childObj, rest, value);
        }

        if (!holder.has(leaf)) {
            return 0;
        }
        JsonNode old = holder.get(leaf);
        // 只改标量：数组/对象形态的同名字段不是我们理解的参数值
        if (!old.isValueNode()) {
            return 0;
        }
        if (old.isBoolean()) {
            holder.put(leaf, asBool(value));
        } else if (old.isIntegralNumber()) {
            holder.put(leaf, asInt(value));
        } else if (old.isFloatingPointNumber()) {
            holder.put(leaf, asDouble(value));
        } else if (old.isTextual()) {
            holder.put(leaf, String.valueOf(value));
        } else {
            holder.put(leaf, asInt(value));
        }
        return 1;
    }

    /**
     * 按候选字段名取值。
     *
     * <h3>为什么不能「取第一个找到的」</h3>
     * 实测某型 IPC 的 {@code VideoInOptions} 里同一个 {@code Flip} 出现 20 多次，
     * 分属不同<b>场景 profile</b>（NormalOptions / NightOptions / DuskOptions …），
     * 外加顶层一份。而<b>第一组是只读快照</b> —— 探针把全部 {@code Flip}
     * 写成 {@code true} 后，只有第一组保持 {@code false}，其余都变了。
     * 按「第一个」读会永远读到那个不会变的快照值，表现为
     * 「写入返回成功、读回永远不变」。
     *
     * <h3>取值优先级</h3>
     * {@code NormalOptions}（白天/普通场景，用户日常看到的就是它）
     * → {@code NightOptions} → 顶层 → 第一个找到的。
     *
     * <p>这是<b>展示口径</b>：设备支持按场景分别设置，界面只显示普通场景的值。
     * 改值时仍然是「所有场景一起改」（见 {@link #writeValueAll}），
     * 否则用户改了白天、夜里却没变。
     */
    private Object readValue(JsonNode root, List<String> fields) {
        if (root == null) {
            return null;
        }
        // ① 优先按场景名找
        for (String profile : new String[]{"NormalOptions", "NightOptions"}) {
            Object v = findInProfile(root, profile, fields);
            if (v != null) {
                return v;
            }
        }
        // ② 顶层承载对象
        for (ObjectNode target : targetNodes(root)) {
            Object v = valueOf(target, fields);
            if (v != null) {
                return v;
            }
        }
        // ③ 兜底：深度遍历第一个
        return findAny(root, fields);
    }

    /** 在指定场景子对象里找字段（递归找第一个同名 profile）。 */
    private Object findInProfile(JsonNode node, String profile, List<String> fields) {
        if (node == null) {
            return null;
        }
        if (node instanceof ObjectNode obj) {
            JsonNode sub = obj.get(profile);
            if (sub instanceof ObjectNode subObj) {
                Object v = valueOf(subObj, fields);
                if (v != null) {
                    return v;
                }
            }
            for (JsonNode child : obj) {
                Object v = findInProfile(child, profile, fields);
                if (v != null) {
                    return v;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Object v = findInProfile(child, profile, fields);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    /** 从一个对象里按候选名取值（支持 {@code A.B} 形式的嵌套路径）。 */
    private Object valueOf(ObjectNode obj, List<String> fields) {
        for (String f : fields) {
            JsonNode v = nodeAt(obj, f);
            if (v == null) {
                continue;
            }
            if (v.isNumber()) {
                return v.numberValue();
            }
            if (v.isBoolean()) {
                return v.booleanValue();
            }
            if (v.isValueNode()) {
                return v.asText();
            }
        }
        return null;
    }

    /**
     * 按路径取节点，支持两种语法：
     * <pre>
     *   3DAutoType.AutoLevel   逐层下钻子对象
     *   NearLight[0].Light     先按下标取数组元素，再下钻
     * </pre>
     *
     * <p>大华部分参数嵌在子对象或对象数组里，但只有少数项如此，
     * 所以不用专门的表达式引擎，按点号与 {@code [n]} 切就行。
     */
    private static JsonNode nodeAt(ObjectNode obj, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        String rest = dot < 0 ? null : path.substring(dot + 1);

        int bracket = head.indexOf('[');
        JsonNode child;
        if (bracket > 0 && head.endsWith("]")) {
            // NearLight[0] → 先取数组，再取第 0 个元素
            String name = head.substring(0, bracket);
            int idx;
            try {
                idx = Integer.parseInt(head.substring(bracket + 1, head.length() - 1).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
            JsonNode arr = obj.get(name);
            if (arr == null || !arr.isArray() || idx >= arr.size()) {
                return null;
            }
            child = arr.get(idx);
        } else {
            child = obj.get(head);
        }

        if (child == null) {
            return null;
        }
        if (rest == null) {
            return child;
        }
        return child instanceof ObjectNode childObj ? nodeAt(childObj, rest) : null;
    }

    /** 深度遍历找第一个命中。 */
    private Object findAny(JsonNode node, List<String> fields) {
        if (node == null) {
            return null;
        }
        if (node instanceof ObjectNode obj) {
            Object v = valueOf(obj, fields);
            if (v != null) {
                return v;
            }
            for (JsonNode child : obj) {
                Object c = findAny(child, fields);
                if (c != null) {
                    return c;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Object c = findAny(child, fields);
                if (c != null) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * 找出配置 JSON 里「真正装着图像参数」的那一层。
     *
     * <p><b>实测结构</b>（某型 IPC，与文档里的猜测完全不同）：
     * <pre>
     *   VideoInOptions      {"id":3348,"params":{"table":{ "Flip":false, ... }}}     ← table 是对象
     *   VideoInExposure     {"id":4372,"params":{"table":[ {"Name":"Day",...}, ... ]}} ← table 是场景数组
     *   VideoInWhiteBalance {"id":5396,"params":{"table":[ {"Name":"Auto",...}, ... ]}}
     * </pre>
     *
     * 关键点有三个，任何一条搞错都会「读不到值」：
     * <ol>
     *   <li>真正的内容在 {@code params.table} 里，不是顶层；</li>
     *   <li>{@code table} 可能是对象，也可能是数组；</li>
     *   <li>数组的元素是<b>场景</b>（Day / Night / Normal / FrontLight…），
     *       <b>不是通道</b> —— 所以不能按通道下标取，要全部返回。</li>
     * </ol>
     *
     * @return 承载字段的对象列表（空表示结构无法识别）
     */
    private List<ObjectNode> targetNodes(JsonNode root) {
        List<ObjectNode> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode cur = root;
        // 逐层剥：params → table
        if (cur.has("params")) {
            cur = cur.get("params");
        }
        if (cur != null && cur.has("table")) {
            cur = cur.get("table");
        }
        if (cur == null) {
            return out;
        }
        if (cur.isArray()) {
            for (JsonNode n : cur) {
                if (n instanceof ObjectNode obj) {
                    out.add(obj);
                }
            }
            return out;
        }
        if (cur instanceof ObjectNode obj) {
            /*
             * table 是对象时，它<b>直接</b>就是参数字典；
             * 但也可能再嵌一层场景（如 NormalOptions / NightOptions）。
             * 这里回退到根：只要根对象自己就带参数字段（无 params 包裹），也接受。
             */
            out.add(obj);
            return out;
        }
        // params 下没有 table：直接把 root 当参数字典（部分固件如此）
        if (root instanceof ObjectNode r) {
            out.add(r);
        }
        return out;
    }

    /** 兼容旧签名：取第一个承载对象。 */
    private static boolean asBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    /**
     * 转整数。
     *
     * <p>接受布尔是因为<b>设备的字段类型与界面控件类型不一致</b>：
     * {@code Rotate90} 在配置里存的是 {@code 0/1}（整数），
     * 而界面是开关，参数表把它定义成 {@code BOOLEAN}。
     * 不兼容布尔就会抛「该参数需要数字：false」。
     */
    private static int asInt(Object v) {
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw BizException.badRequest("该参数需要数字：" + v);
        }
    }

    private static double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw BizException.badRequest("该参数需要数字：" + v);
        }
    }

    /**
     * 参数能力项（接口返回体）。
     *
     * <p>带齐界面元数据（{@code group} / {@code type} / {@code options} / {@code min} / {@code max}），
     * 前端<b>不需要硬编码任何参数名</b>：按 {@code group} 分块、按 {@code type} 渲染
     * 滑块/开关/下拉、按 {@code options} 填选项。换型号或加参数只改后端这张表。
     */
    @lombok.Data
    public static class Capability {
        private String key;
        private String label;
        /** 界面分组（图像 / 视角 / 曝光 / 白平衡 / …）。 */
        private String group;
        /** 控件类型：{@code NUMBER} / {@code BOOLEAN} / {@code ENUM}。 */
        private String type;
        /** 枚举选项（仅 ENUM 有值）。 */
        private List<OptionVo> options;
        /** 数值下限（仅 NUMBER 有值）。 */
        private Integer min;
        /** 数值上限（仅 NUMBER 有值）。 */
        private Integer max;
        /** 取值范围说明（兼容旧前端）。 */
        private String range;
        /** 是否为组合参数（viewMode 由 flip/mirror/rotate90 组合而成）。 */
        private boolean virtual;
        /**
         * 是否不在界面上单独渲染（组合参数的成员字段）。
         *
         * <p>成员字段由组合参数代表，界面上再摆一遍开关既重复又容易配出矛盾状态。
         * 值仍会随能力表返回，供排查用。
         */
        private boolean hidden;
        /**
         * 上级参数的 key（为空表示恒显示）。
         *
         * <p>例：{@code shutter} 的 {@code dependsOn = "exposure"}，
         * 只有曝光模式选了「手动」才该出现。
         */
        private String dependsOn;
        /** 上级参数取这些值时才显示；空表示只看上级是否开启（开关类）。 */
        private List<String> dependsValues;
        /** 该型号是否支持这一项（判据：配置 JSON 里有没有这个字段）。 */
        private boolean available;
        /** 当前值；{@code null} 表示读不到。 */
        private Object value;
    }

    /** 枚举选项。{@code value} 是写回设备的值（数字或字符串都已归一成字符串）。 */
    @lombok.Data
    public static class OptionVo {
        private final String value;
        private final String label;
    }
}
