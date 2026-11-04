package io.github.rurulalanana.starter.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 接口文档（OpenAPI 3 + Knife4j）。
 *
 * <p>{@code /doc.html} 中文界面，{@code /v3/api-docs} 原始 JSON（可导入 Postman/Apifox）。
 *
 * <p><b>鉴权两个坑</b>（改这里之前必读，都是实测踩过的）：
 * <ol>
 *   <li>安全方案必须是 {@code apiKey}，不能是 {@code http+bearer} —— Knife4j 会自动补
 *       {@code Bearer } 前缀，而 Sa-Token 没配 {@code token-prefix}，读整条头原文，
 *       于是 {@code Bearer xxx} 被判未登录（实测裸 token 200、带前缀 401）。</li>
 *   <li>{@code security} 必须挂在每个 operation 上 —— Knife4j 只读
 *       {@code readApiSecurity(api, operation)}，写根节点等于没写，表现为
 *       「界面能填 token，调试却不带 Authorization 头」。</li>
 * </ol>
 *
 * <p><b>两个安全方案</b>：{@code Authorization}（会话 token）与 {@code X-Api-Key}（机器通行证）。
 * 后者<b>必须</b>声明，否则接口文档自己无法调通登录：浏览器登录要拖滑块，
 * 文档调试面板做不了，只能用 API 密钥。实测前没声明时，Knife4j 的 Authorize 里没有这个字段，
 * login 的调试请求发出去永远回「请先完成验证码」。
 *
 * <p>开关：{@code api-docs.enabled=false} 不注册端点；{@code api-docs.protect=true} 文档要 HTTP Basic 口令。
 */
@Configuration
@ConditionalOnProperty(name = "gb28181.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfiguration {

    /** 会话 token 方案：在 {@code components} 里注册一次，operation 上按名引用。 */
    private static final String TOKEN_SCHEME = "Authorization";

    /** 机器通行证方案：登录接口用它（拖不了滑块）。 */
    private static final String API_KEY_SCHEME = "X-Api-Key";

    /**
     * 真正不需要任何凭据的路径：由播放器 / {@code <img>} / nginx 子请求发起，或凭签名令牌访问。
     *
     * <p><b>注意 login 不在这里</b>：它对浏览器是「密码 + 验证码」、对机器是 {@code X-Api-Key}，
     * 两者都是凭据，必须标上 security，否则调试面板发不出头。
     */
    private static final String[] NO_AUTH_PREFIXES = {
            "/api/v1/share/",
            "/api/v1/cloudrecord/share/",
            "/api/v1/record/auth",
            "/api/v1/webrtc/"
    };

    /** 用 {@code X-Api-Key} 而不是会话 token 的路径（目前只有登录）。 */
    private static final String[] API_KEY_PATHS = {
            "/api/v1/login"
    };

    @Value("${info.app.version:1.0.2}")
    private String version;

    /** 全局文档：标题、描述（对接方最关心的信封/鉴权/分页约定）与安全方案。 */
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
                        // apiKey 而非 http+bearer：后者会被 Knife4j 自动补 "Bearer "，
                        // 与 Sa-Token 读裸 token 的行为冲突，详见类注释第 1 条
                        .addSecuritySchemes(TOKEN_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                                .description("登录接口返回的 data.token，"
                                        + "原样作为 Authorization 头的值，不要加 Bearer 前缀"))
                        // 机器通行证：登录接口用。密钥在「设置 → 账号安全 → API 密钥」里生成
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")
                                .description("账号自己生成的 API 密钥（设置 → 账号安全 → API 密钥）。"
                                        + "只能用于登录接口，作用是替代「密码 + 滑块验证码」")));
        // 不调 addSecurityItem：根节点 security 被 Knife4j 忽略，
        // 且会让免登录接口错误地显示成需要鉴权。改为 stampSecurity() 逐 operation 标注。
    }

    /**
     * 给每个 operation 补 {@code security} 标记（原因见类注释第 2 条）。
     *
     * <p>三类分法：
     * <ul>
     *   <li>{@link #API_KEY_PATHS} —— 标 {@code X-Api-Key}（登录：机器客户端只能靠它）；</li>
     *   <li>{@link #NO_AUTH_PREFIXES} —— 不标（分享 / WebRTC / 录像鉴权子请求）；</li>
     *   <li>其余 —— 标 {@code Authorization}（会话 token）。</li>
     * </ul>
     */
    private OpenApiCustomizer stampSecurity() {
        return openApi -> {
            Paths paths = openApi.getPaths();
            if (paths == null) {
                return;
            }
            paths.forEach((path, item) -> {
                if (isNoAuth(path)) {
                    return;
                }
                String scheme = isApiKeyPath(path) ? API_KEY_SCHEME : TOKEN_SCHEME;
                for (Operation operation : item.readOperations()) {
                    operation.setSecurity(List.of(
                            new SecurityRequirement().addList(scheme)));
                }
            });
        };
    }

    private static boolean isNoAuth(String path) {
        for (String prefix : NO_AUTH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isApiKeyPath(String path) {
        for (String p : API_KEY_PATHS) {
            if (path.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** 鉴权与会话。单独成组是因为「怎么拿 token」是对接方第一件事，混在 system 里找不到。 */
    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
                .group("auth")
                .addOpenApiCustomizer(api -> api.setInfo(new Info()
                        .title("鉴权与会话")
                        .version(version)
                        .description("""
                                **接口文档调试登录（推荐）**

                                1. 在平台「设置 → 账号安全 → API 密钥」点「生成密钥」，复制那把明文；
                                2. 点本页右上角 **Authorize**，在 `X-Api-Key` 一栏填入该密钥；
                                3. 调 `POST /api/v1/login`，**请求体留空即可** —— 密钥已标识账号；
                                4. 从响应 `data.token` 取值，回到 **Authorize** 填进 `Authorization` 一栏；
                                5. 之后别的分组就能直接「调试」。

                                **为什么不用账号密码调这里**：浏览器登录要求拖滑块验证码，
                                而文档调试面板是脚本行为、拿不到验证码凭证（会一直回「请先完成验证码」）。
                                API 密钥就是为机器调用准备的，作用等同「账号密码 + 验证码」，
                                可随时在页面上重新生成或撤销，撤销后立即失效。

                                **用 curl / 业务平台对接**：

                                1. `POST /api/v1/login`，请求头 `X-Api-Key: <密钥>`，body 可省略；
                                2. 从响应的 `data.token` 取值；
                                3. 之后所有请求带 `Authorization: <token>`（**不要**加 `Bearer ` 前缀）。

                                token 有效期 7 天、服务重启不失效；`/api/v1/logout` 会主动作废当前 token。
                                """)))
                .addOpenApiCustomizer(stampSecurity())
                .pathsToMatch(
                        "/api/v1/login",
                        "/api/v1/logout",
                        "/api/v1/userinfo",
                        "/api/v1/modifypassword")
                .build();
    }

    /** 业务对接接口：业务平台 90% 的调用落在这里，单独成组以免在 170 多个接口里翻管理类。 */
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
                .addOpenApiCustomizer(stampSecurity())
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
                .addOpenApiCustomizer(stampSecurity())
                .pathsToMatch("/api/v1/cascade/**")
                .build();
    }

    /** 免登录接口：单独成组，让对接方一眼看到哪些地址能直接塞进 iframe / {@code <img>}。 */
    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .addOpenApiCustomizer(api -> api.setInfo(new Info()
                        .title("免登录接口")
                        .version(version)
                        .description("分享播放页、分享录像、录像地址鉴权（凭签名令牌，不需要登录态）")))
                .addOpenApiCustomizer(stampSecurity())
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
                .addOpenApiCustomizer(stampSecurity())
                .pathsToMatch(
                        // 登录/登出/当前用户/改密码归 auth 组
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
                .addOpenApiCustomizer(stampSecurity())
                .pathsToMatch("/api/v1/webrtc/**")
                .build();
    }
}
