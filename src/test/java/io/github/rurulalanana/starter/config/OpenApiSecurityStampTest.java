package io.github.rurulalanana.starter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 接口文档鉴权标注的回归测试。
 *
 * <p>2026-11 定位到的问题：文档里填了 token，调试接口却不带 {@code Authorization}。
 * 根因是 Knife4j 只认 <b>operation 级</b> {@code security}（它调用
 * {@code readApiSecurity(api, operation)}，检查 {@code operation.hasOwnProperty("security")}），
 * 而原先的配置只把 {@code security} 写在文档根节点 —— 实测 124 个 operation 里 0 个带标记。
 *
 * <p>另一个坑：安全方案声明成 {@code http + bearer} 时，Knife4j 会自动补 {@code Bearer }
 * 前缀，而 Sa-Token 读的是整条头原文（{@code Authorization: Bearer xxx} 实测返回 401）。
 *
 * <p>这两个错误都不会被编译期发现，只能用断言钉住。
 */
class OpenApiSecurityStampTest {

    /** {@code stampSecurity()} 是私有辅助方法，测试里用反射取一次。 */
    private static OpenApiCustomizer stampSecurity() throws Exception {
        OpenApiConfiguration configuration = new OpenApiConfiguration();
        Method method = OpenApiConfiguration.class.getDeclaredMethod("stampSecurity");
        method.setAccessible(true);
        return (OpenApiCustomizer) method.invoke(configuration);
    }

    private static OpenAPI docWith(String... paths) {
        Paths p = new Paths();
        for (String path : paths) {
            p.addPathItem(path, new PathItem()
                    .get(new Operation().operationId("get_" + path.replace('/', '_')))
                    .post(new Operation().operationId("post_" + path.replace('/', '_'))));
        }
        return new OpenAPI().paths(p);
    }

    private static List<SecurityRequirement> securityOf(OpenAPI doc, String path, boolean get) {
        PathItem item = doc.getPaths().get(path);
        Operation op = get ? item.getGet() : item.getPost();
        return op.getSecurity();
    }

    @Test
    @DisplayName("需要登录的接口：GET 与 POST 都要带上 Authorization 标记")
    void stampsEveryHttpMethod() throws Exception {
        OpenAPI doc = docWith("/api/v1/alarm/list");
        stampSecurity().customise(doc);

        for (boolean get : new boolean[]{true, false}) {
            List<SecurityRequirement> sec = securityOf(doc, "/api/v1/alarm/list", get);
            assertNotNull(sec, "operation 缺 security，Knife4j 就不会往调试请求里加 Authorization 头");
            assertEquals(1, sec.size());
            assertEquals(List.of("Authorization"), List.copyOf(sec.get(0).keySet()));
        }
    }

    @Test
    @DisplayName("登录接口：标 X-Api-Key，不能跳过（否则调试面板发不出这个头）")
    void stampsApiKeyOnLogin() throws Exception {
        OpenAPI doc = docWith("/api/v1/login");
        stampSecurity().customise(doc);

        for (boolean get : new boolean[]{true, false}) {
            List<SecurityRequirement> sec = securityOf(doc, "/api/v1/login", get);
            assertNotNull(sec, "login 缺 security 时，Knife4j 的 Authorize 里就没有 X-Api-Key 字段，"
                    + "文档自己无法调通登录");
            assertEquals(1, sec.size());
            assertEquals(List.of("X-Api-Key"), List.copyOf(sec.get(0).keySet()));
        }
    }

    @Test
    @DisplayName("免登录接口：分享 / WebRTC / 录像鉴权子请求不打鉴权标记")
    void skipsNoAuthPaths() throws Exception {
        OpenAPI doc = docWith(
                "/api/v1/share/live",
                "/api/v1/cloudrecord/share/1",
                "/api/v1/record/auth",
                "/api/v1/webrtc/play");
        stampSecurity().customise(doc);

        for (String path : new String[]{
                "/api/v1/share/live",
                "/api/v1/cloudrecord/share/1",
                "/api/v1/record/auth",
                "/api/v1/webrtc/play"}) {
            assertNull(securityOf(doc, path, true), path + " 不该带鉴权标记");
            assertNull(securityOf(doc, path, false), path + " 不该带鉴权标记");
        }
    }

    @Test
    @DisplayName("空文档不炸：paths 为 null 时直接返回")
    void toleratesNullPaths() throws Exception {
        stampSecurity().customise(new OpenAPI());
    }
}
