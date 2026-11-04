package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.starter.common.Permissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 云台权限拆分的回归测试：把「接口路径 → 权限点」钉死。
 *
 * <p>拆成四个平级权限点后，任何一条路径映射错了都会造成两种事故之一：
 * 授权了却调不通，或没授权却能调通（尤其 {@code formatsdcard} / {@code teleboot} 这类破坏性操作）。
 * 这类错误不会在编译期暴露，只能用断言守住。
 */
class ControlPermissionSplitTest {

    /** {@code PREFIX_RULES} 与 {@code Rule} 都是私有的，测试用反射读一次。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> rules() throws Exception {
        Field f = WritePermissionInterceptor.class.getDeclaredField("PREFIX_RULES");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(null);
    }

    private static String writeCodeOf(Object rule) throws Exception {
        Method m = rule.getClass().getDeclaredMethod("write");
        m.setAccessible(true);
        return (String) m.invoke(rule);
    }

    private static String codeFor(String path) throws Exception {
        Object rule = rules().get(path);
        assertNotNull(rule, "路径没有登记权限规则：" + path);
        return writeCodeOf(rule);
    }

    @Test
    @DisplayName("方向盘/变倍/光圈/拉框/喊话 → channel:ptz")
    void basicPtzPaths() throws Exception {
        for (String p : new String[]{
                "/api/v1/control/ptz",
                "/api/v1/control/ptzprecise",
                "/api/v1/control/fi",
                "/api/v1/control/dragzoomin",
                "/api/v1/control/dragzoomout",
                "/api/v1/control/navigate",
                "/api/v1/control/talk"}) {
            assertEquals(Permissions.CHANNEL_PTZ, codeFor(p), p);
        }
    }

    @Test
    @DisplayName("预置位 → channel:preset")
    void presetPaths() throws Exception {
        for (String p : new String[]{"/api/v1/control/preset", "/api/v1/control/homeposition"}) {
            assertEquals(Permissions.CHANNEL_PRESET, codeFor(p), p);
        }
    }

    @Test
    @DisplayName("设备 tab → channel:ptz:device")
    void deviceTabPaths() throws Exception {
        for (String p : new String[]{
                "/api/v1/control/wiper",
                "/api/v1/control/filllight",
                "/api/v1/control/record",
                "/api/v1/control/guard",
                "/api/v1/control/alarmnotify",
                "/api/v1/control/alarmreset"}) {
            assertEquals(Permissions.CHANNEL_PTZ_DEVICE, codeFor(p), p);
        }
    }

    @Test
    @DisplayName("高级 tab → channel:ptz:advanced")
    void advancedTabPaths() throws Exception {
        for (String p : new String[]{
                "/api/v1/control/iframe",
                "/api/v1/control/teleboot",
                "/api/v1/control/formatsdcard",
                "/api/v1/control/deviceupgrade",
                "/api/v1/control/synctime",
                "/api/v1/control/deviceconfig",
                "/api/v1/control/devicesubscribe",
                "/api/v1/control/osdconfig",
                "/api/v1/control/snapshotconfig",
                "/api/v1/control/targettrack"}) {
            assertEquals(Permissions.CHANNEL_PTZ_ADVANCED, codeFor(p), p);
        }
    }

    @Test
    @DisplayName("四个云台权限点平级：互不隐含")
    void ptzPointsAreSiblings() {
        for (String code : new String[]{
                Permissions.CHANNEL_PRESET,
                Permissions.CHANNEL_PTZ_DEVICE,
                Permissions.CHANNEL_PTZ_ADVANCED}) {
            assertEquals(null, Permissions.parentOf(code), code + " 不应有上级");
        }
        assertTrue(Permissions.childrenOf(Permissions.CHANNEL_PTZ).isEmpty(),
                "channel:ptz 不能隐含有副作用的设备/高级操作");
    }

    @Test
    @DisplayName("/api/v1/record 与 /api/v1/control/record 不互相串味")
    void recordPathsDoNotCollide() throws Exception {
        // /control/record 是「设备本地录像开关」，属设备 tab；
        // /record 是「平台侧手动启停录像」，属 cloud:record。两者前缀相近，极易写错。
        assertEquals(Permissions.CHANNEL_PTZ_DEVICE, codeFor("/api/v1/control/record"));
        assertEquals(Permissions.CLOUD_RECORD, codeFor("/api/v1/record"));
    }
}
