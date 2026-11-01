package io.github.rurulalanana.starter.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档（OpenAPI 3 + Knife4j）。
 *
 * <h3>为什么要做</h3>
 *
 * <p>平台的最终形态是「被业务平台调用」：上层 CMS / 指挥调度 / 行业应用按接口拿设备树、
 * 取流地址、查录像、订阅报警。手写 Markdown 一定会与代码脱节，
 * 所以接口清单必须是<b>从 Controller 反射出来的活文档</b>——
 * 改了参数签名，文档自动跟着变，试调也能直接打真接口。
 *
 * <h3>访问地址</h3>
 *
 * <ul>
 *   <li>{@code /doc.html} —— Knife4j 中文界面（推荐）</li>
 *   <li>{@code /swagger-ui.html} —— 原生 Swagger UI</li>
 *   <li>{@code /v3/api-docs} —— 原始 OpenAPI JSON（可导入 Postman / Apifox / 生成 SDK）</li>
 * </ul>
 *
 * <h3>鉴权</h3>
 *
 * <p>除 {@code /api/v1/login} 与分享类接口外，全部要求把登录拿到的 token
 * 放进 {@code Authorization} 请求头（Sa-Token 的 {@code token-name}）。
 * 界面上点右上角 <b>Authorize</b> 填一次即可，之后的「调试」会带上它。
 *
 * <p><b>开关</b>：{@code gb28181.api-docs.enabled=false} 时整个文档端点不注册
 * （纯内网生产可以彻底关掉）；{@code gb28181.api-docs.protect=true} 时
 * 文档本身要 HTTP Basic 口令（接口清单会暴露全部路径与参数，公网建议开启）。
 */
@Configuration
@ConditionalOnProperty(name = "gb28181.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfiguration {

    /** 安全方案名：在 {@code components} 里注册一次，全局引用。 */
    private static final String TOKEN_SCHEME = "Authorization";

    @Value("${info.app.version:1.0.2}")
    private String version;

    /**
     * 全局文档：标题、说明与鉴权方式。
     *
     * <p>这里写的「怎么调」比接口本身更重要——对接方第一个问题永远是
     * 「token 放哪、怎么续期、失败长什么样」，所以三条都写进描述里。
     */
    @Bean
    public OpenAPI platformOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("GB/T 28181 视频监控平台 API")
                        .version(version)
                        .description("""
                                面向业务平台的 REST 接口。全部响应为统一信封 `{code, msg, data}`，
                                **HTTP 状态码恒为 200**，业务成败看 `code`：0 成功 / 400 参数错 / 401 未登录 / 403 无权限 / 500 服务端异常。

                                **鉴权**：先 `POST /api/v1/login` 拿到 `token`（见 **auth** 分组），
                                之后所有请求带 `Authorization: <token>` 请求头。token 有效期 7 天，服务重启不失效。

                                **字段风格**：请求参数（query/form）为 snake_case，
                                请求体（JSON）与响应字段为 camelCase；null 字段默认省略。

                                **分页语义**：`start` 为偏移量、`limit` 为页大小，`limit=0` 表示不分页；
                                列表响应统一为 `xxxCount + xxxList`。

                                **分享类接口**（`/api/v1/share/**`、`/api/v1/cloudrecord/share/**`、
                                `/api/v1/record/auth`）设计上免登录：它们要么凭签名令牌访问，
                                要么由播放器/`<img>`/nginx 子请求发起，带不了登录态。
                                """)
                        .contact(new io.swagger.v3.oas.models.info.Contact()
                                .name("GB28181 平台接口支持")))
                .components(new Components()
                        .addSecuritySchemes(TOKEN_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                                .description("登录接口返回的 token，直接作为 Authorization 头的值（不带 Bearer 前缀）")))
                // 全局要求鉴权：登录与分享接口在文档里仍会显示锁图标，
                // 这是刻意的（宁可多提示一次），实际是否放行由后端拦截器决定
                .addSecurityItem(new SecurityRequirement().addList(TOKEN_SCHEME));
    }

    /**
     * 鉴权与会话：登录换 token、登出、当前用户、改密码。
     *
     * <p><b>单独成组的原因</b>：对接方第一件事就是「怎么拿 token」，
     * 而它原本混在 {@code system}（账号/权限/配置共 38 个接口）里，
     * 在 {@code business} 分组下根本找不到 —— 实测被问过一次。
     * 放在下拉框第一个，进文档就能看到完整流程。
     */
    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
                .group("auth")
                .addOpenApiCustomizer(api -> api.setInfo(new Info()
                        .title("鉴权与会话")
                        .version(version)
                        .description("""
                                对接流程：
                                1. `POST /api/v1/login`（body：`{"username":"...","password":"<MD5 32 位小写>"}`）
                                2. 从响应的 `data.token` 取值
                                3. 点本页右上角 **Authorize** 填入该 token（或在请求头带 `Authorization: <token>`）
                                4. 之后即可在其它分组里直接「调试」

                                token 有效期 7 天、服务重启不失效；`/api/v1/logout` 会主动作废当前 token。
                                """)))
                .pathsToMatch(
                        "/api/v1/login",
                        "/api/v1/logout",
                        "/api/v1/userinfo",
                        "/api/v1/modifypassword")
                .build();
    }

    /**
     * 业务对接接口：设备、通道、实时点播、录像、报警、云台。
     *
     * <p>业务平台 90% 的调用都落在这组里，所以单独成组——
     * 让对接方不用在 170 多个接口里翻管理类接口。
     */
    @Bean
    public GroupedOpenApi businessApi() {
        return GroupedOpenApi.builder()
                .group("business")
                .addOpenApiCustomizer(api -> api.setInfo(new Info()
                        .title("业务对接接口")
                        .version(version)
                        .description("设备 / 通道 / 实时点播 / 录像 / 报警 / 云台控制。"
                                + "\n\n> 本组所有接口都要带 `Authorization` 请求头，"
                                + "token 的获取方式见 **auth** 分组。")))
                .pathsToMatch(
                        "/api/v1/device/**",
                        "/api/v1/stream/**",
                        "/api/v1/playback/**",
                        "/api/v1/cloudrecord/**",
                        "/api/v1/alarm/**",
                        "/api/v1/control/**",
                        "/api/v1/decode/**",
                        "/api/v1/record/**")
                .build();
    }

    /** 级联对接：上下级平台配置、目录与通道推送、级联点播。 */
    @Bean
    public GroupedOpenApi cascadeApi() {
        return GroupedOpenApi.builder()
                .group("cascade")
                .addOpenApiCustomizer(api -> api.setInfo(new Info()
                        .title("级联对接接口")
                        .version(version)
                        .description("上级/下级平台配置、目录推送、级联控制")))
                .pathsToMatch("/api/v1/cascade/**")
                .build();
    }

    /**
     * 免登录接口：分享播放、分享录像、录像地址鉴权。
     *
     * <p>单独成组是为了让对接方一眼看到「哪些接口不用带 token」——
     * 这决定了他们能不能把播放地址直接塞进 iframe 或 `<img>`。
     */
    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .addOpenApiCustomizer(api -> api.setInfo(new Info()
                        .title("免登录接口")
                        .version(version)
                        .description("分享播放页、分享录像、录像地址鉴权（凭签名令牌，不需要登录态）")))
                .pathsToMatch("/api/v1/share/**", "/api/v1/cloudrecord/share/**", "/api/v1/record/auth")
                .build();
    }

    /** 平台管理接口：登录、账号、角色权限、菜单、运行时配置、证书、审计。 */
    @Bean
    public GroupedOpenApi systemApi() {
        return GroupedOpenApi.builder()
                .group("system")
                .addOpenApiCustomizer(api -> api.setInfo(new Info()
                        .title("平台管理接口")
                        .version(version)
                        .description("登录与会话、用户、角色权限、菜单、运行时配置、黑白名单、证书、审计、指标")))
                .pathsToMatch(
                        // 登录/登出/当前用户/改密码在 auth 分组，这里不重复
                        "/api/v1/getserverinfo",
                        "/api/v1/system/**",
                        "/api/v1/user/**",
                        "/api/v1/role/**",
                        "/api/v1/menu/**",
                        "/api/v1/config/**",
                        "/api/v1/acl/**",
                        "/api/v1/tls/**",
                        "/api/v1/oplog/**",
                        "/api/v1/restart")
                .build();
    }

    /** WebRTC 协商：播放器直接交换 SDP，免登录。 */
    @Bean
    public GroupedOpenApi webrtcApi() {
        return GroupedOpenApi.builder()
                .group("webrtc")
                .addOpenApiCustomizer(api -> api.setInfo(new Info()
                        .title("WebRTC 协商接口")
                        .version(version)
                        .description("SDP 交换（播放器无法携带登录态，故免登录，凭会话标识约束）")))
                .pathsToMatch("/api/v1/webrtc/**")
                .build();
    }
}
