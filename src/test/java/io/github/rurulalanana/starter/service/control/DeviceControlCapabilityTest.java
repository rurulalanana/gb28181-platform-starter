package io.github.rurulalanana.starter.service.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link DeviceControl} 的能力表与实现类的覆写情况一致。
 *
 * <h3>为什么必须有这个测试</h3>
 * 统一接口的核心约定是「不覆写就继承默认实现，默认实现就抛不支持」。
 * 这条约定有一个<b>编译期发现不了</b>的失效方式：
 * <ul>
 *   <li>{@code capabilities()} 里声明了某项能力，但忘了覆写方法
 *       → 前端显示按钮可用，点了报「不支持」，用户莫名其妙；</li>
 *   <li>覆写了方法，但没写进 {@code capabilities()}
 *       → 功能其实是好的，前端却把按钮置灰了，等于白做。</li>
 * </ul>
 * 两者都不会编译失败、也不会运行时报错，只能靠测试钉住。
 *
 * <h3>国标那一侧更要盯</h3>
 * 国标是主链路，{@code capabilities()} 直接返回 {@code allOf}。
 * 新增能力时如果只在枚举和接口加了一项、没在
 * {@link Gb28181DeviceControl} 里实现，国标设备就会莫名其妙变成「不支持」——
 * 而这个错误只有在现场点到那个按钮时才会暴露。本测试用反射保证
 * <b>32 个方法全部被国标覆写</b>。
 */
class DeviceControlCapabilityTest {

    /**
     * 接口里的元信息方法（不是「能力」，不参与能力表对齐）。
     *
     * <p>它们是统一接口的基础设施：{@code supports} 供前端/调用方判断，
     * {@code unsupportedHint} / {@code unsupported} 供默认实现产异常。
     */
    private static final Set<String> META_METHODS =
            Set.of("supports", "unsupportedHint", "unsupported");

    /**
     * 方法名 → 能力（1:N，同一能力可以有多个方法）。
     *
     * <p>{@code queryHomePosition} 与 {@code homePosition} 共用
     * {@code HOME_POSITION}；{@code wiper} 与 {@code fillLight} 共用
     * {@code AUX_SWITCH}。其余一对一。
     *
     * <p><b>新增一个 default 方法时必须往这里加一行</b>，否则测试发现不了它的漏实现
     * —— 这是本测试唯一的维护负担，代价远小于现场踩坑。
     */
    private static final Map<String, DeviceCapability> METHOD_TO_CAPABILITY = new LinkedHashMap<>();

    static {
        METHOD_TO_CAPABILITY.put("ptz", DeviceCapability.PTZ);
        METHOD_TO_CAPABILITY.put("focusIris", DeviceCapability.FOCUS_IRIS);
        METHOD_TO_CAPABILITY.put("ptzPrecise", DeviceCapability.PTZ_PRECISE);
        METHOD_TO_CAPABILITY.put("dragZoom", DeviceCapability.DRAG_ZOOM);
        METHOD_TO_CAPABILITY.put("preset", DeviceCapability.PRESET);
        METHOD_TO_CAPABILITY.put("queryPresets", DeviceCapability.PRESET_QUERY);
        METHOD_TO_CAPABILITY.put("cruise", DeviceCapability.CRUISE);
        METHOD_TO_CAPABILITY.put("queryCruiseTrack", DeviceCapability.CRUISE_QUERY);
        METHOD_TO_CAPABILITY.put("queryHomePosition", DeviceCapability.HOME_POSITION);
        METHOD_TO_CAPABILITY.put("homePosition", DeviceCapability.HOME_POSITION);
        METHOD_TO_CAPABILITY.put("wiper", DeviceCapability.AUX_SWITCH);
        METHOD_TO_CAPABILITY.put("fillLight", DeviceCapability.AUX_SWITCH);
        METHOD_TO_CAPABILITY.put("recording", DeviceCapability.RECORDING);
        METHOD_TO_CAPABILITY.put("guard", DeviceCapability.GUARD);
        METHOD_TO_CAPABILITY.put("reboot", DeviceCapability.REBOOT);
        METHOD_TO_CAPABILITY.put("forceIFrame", DeviceCapability.FORCE_I_FRAME);
        METHOD_TO_CAPABILITY.put("formatSdCard", DeviceCapability.FORMAT_SD_CARD);
        METHOD_TO_CAPABILITY.put("alarmReset", DeviceCapability.ALARM_RESET);
        METHOD_TO_CAPABILITY.put("syncTime", DeviceCapability.SYNC_TIME);
        METHOD_TO_CAPABILITY.put("deviceConfig", DeviceCapability.DEVICE_CONFIG);
        METHOD_TO_CAPABILITY.put("subscribe", DeviceCapability.SUBSCRIBE);
        METHOD_TO_CAPABILITY.put("queryDeviceStatus", DeviceCapability.QUERY_STATUS);
        METHOD_TO_CAPABILITY.put("queryDeviceInfo", DeviceCapability.QUERY_INFO);
        METHOD_TO_CAPABILITY.put("queryConfig", DeviceCapability.QUERY_CONFIG);
        METHOD_TO_CAPABILITY.put("querySdCardStatus", DeviceCapability.QUERY_SD_CARD);
        METHOD_TO_CAPABILITY.put("queryPtzPosition", DeviceCapability.QUERY_PTZ_POSITION);
        METHOD_TO_CAPABILITY.put("osdConfig", DeviceCapability.OSD_CONFIG);
        METHOD_TO_CAPABILITY.put("snapshotConfig", DeviceCapability.SNAPSHOT_CONFIG);
        METHOD_TO_CAPABILITY.put("deviceUpgrade", DeviceCapability.DEVICE_UPGRADE);
        METHOD_TO_CAPABILITY.put("targetTrack", DeviceCapability.TARGET_TRACK);
        METHOD_TO_CAPABILITY.put("alarmNotify", DeviceCapability.ALARM_NOTIFY);
        METHOD_TO_CAPABILITY.put("talk", DeviceCapability.TALK);
    }

    @Test
    @DisplayName("映射表覆盖接口的全部能力方法，且每个能力都有对应方法")
    void 映射表与接口保持同步() {
        Set<String> interfaceMethods = new LinkedHashSet<>();
        for (Method m : DeviceControl.class.getDeclaredMethods()) {
            if (!m.isDefault() || m.getParameterCount() == 0) {
                continue;
            }
            // 元信息方法不是「能力」，不参与能力表对齐
            if (META_METHODS.contains(m.getName())) {
                continue;
            }
            interfaceMethods.add(m.getName());
        }
        assertEquals(interfaceMethods, METHOD_TO_CAPABILITY.keySet(),
                "DeviceControl 新增/改名了 default 方法，请同步 METHOD_TO_CAPABILITY");

        // 每个能力都必须有方法实现它，否则能力表里会出现「永远不可达的项」
        Set<DeviceCapability> covered = EnumSet.noneOf(DeviceCapability.class);
        for (DeviceCapability c : METHOD_TO_CAPABILITY.values()) {
            covered.add(c);
        }
        assertEquals(EnumSet.allOf(DeviceCapability.class), covered,
                "有 DeviceCapability 没有被任何方法实现");
    }

    @Test
    @DisplayName("国标实现必须覆写全部方法（它是主链路，不能有漏的）")
    void 国标覆写全部方法() {
        Set<String> declared = declaredMethodNames(Gb28181DeviceControl.class);
        Set<String> missing = new LinkedHashSet<>(METHOD_TO_CAPABILITY.keySet());
        missing.removeAll(declared);
        assertTrue(missing.isEmpty(),
                "Gb28181DeviceControl 缺少这些方法的实现，国标设备会报「不支持」：" + missing);
    }

    @Test
    @DisplayName("大华：能力表与覆写的方法必须严格一一对应")
    void 大华能力表与实现一致() {
        Set<String> declared = declaredMethodNames(DahuaDeviceControl.class);
        Set<String> declaredInMap = new LinkedHashSet<>(declared);
        declaredInMap.retainAll(METHOD_TO_CAPABILITY.keySet());

        // capabilities() 与 unsupportedHint() 都是纯逻辑，不碰实例字段，
        // 因此可以传 null 依赖构造（避免为了跑测试引入 mock 框架）
        DahuaDeviceControl control = new DahuaDeviceControl(null, null, null, null);
        EnumSet<DeviceCapability> capabilities = control.capabilities();

        Set<String> expected = new LinkedHashSet<>();
        for (Map.Entry<String, DeviceCapability> e : METHOD_TO_CAPABILITY.entrySet()) {
            if (capabilities.contains(e.getValue())) {
                expected.add(e.getKey());
            }
        }

        assertEquals(expected, declaredInMap,
                "能力表与覆写的方法不一致：声明了却没实现 → 前端点了报错；"
                        + "实现了却没声明 → 前端置灰，功能白做");

        // 每个「不支持」都必须给出原因，否则用户只看到一句干巴巴的「不支持」
        for (DeviceCapability c : EnumSet.complementOf(capabilities)) {
            String hint = control.unsupportedHint(c);
            assertFalse(hint == null || hint.isBlank(),
                    "不支持的能力必须写清原因：" + c.name());
        }
    }

    @Test
    @DisplayName("不支持的能力抛出 400，且文案里既有能力名也有原因")
    void 不支持的能力抛出可读异常() {
        DahuaDeviceControl control = new DahuaDeviceControl(null, null, null, null);
        // 大华 NetSDK 确实没有强制关键帧接口
        var e = control.unsupported(DeviceCapability.FORCE_I_FRAME);
        String msg = e.getMessage();
        assertTrue(msg.contains("强制关键帧"), "文案要包含能力名，否则用户不知道是哪一项：" + msg);
        assertTrue(msg.contains("没有强制关键帧接口"), "文案要包含具体原因：" + msg);
        assertTrue(msg.contains("dahua"), "文案要包含接入方式，便于排查：" + msg);
    }

    /** 类里自己声明的方法名（含覆写的 default 方法），不含继承来的 Object 方法。 */
    private static Set<String> declaredMethodNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getParameterCount() > 0)
                .filter(m -> !m.isSynthetic())
                .forEach(m -> names.add(m.getName()));
        return names;
    }
}
