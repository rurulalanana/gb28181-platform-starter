package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.starter.service.RecordUrlSigner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 前端单页应用（SPA）路由回退。Vue Router 用 history 模式，直接访问 {@code /dashboard} 等路由时
 * Spring 找不到控制器会 404，这里把非 API、非静态资源、无扩展名的路径转发到 {@code index.html}。
 *
 * <p>排除清单必须覆盖所有一级静态资源目录：否则 {@code /jessibuca/decoder.js} 这类
 * 「目录 + 带扩展名」的路径会被 {@code /{path}/**} 吃掉，返回 {@code index.html}，
 * 播放器脚本拿到 HTML 无法加载。
 */
@Configuration
public class SpaWebConfiguration implements WebMvcConfigurer {

    /**
     * 交给静态资源/框架自己处理的路径首段，不参与 SPA 兜底。
     * {@code webjars}/{@code swagger-ui}/{@code v3} 属接口文档：Knife4j 的界面在 {@code /doc.html}，
     * 脚本样式在 {@code /webjars/**}。漏了 webjars 的症状是「doc.html 打得开但白屏」——
     * 资源请求被兜底规则吞掉返回 index.html，浏览器按 JS 解析直接语法错误。
     */
    private static final String STATIC_PREFIXES =
            "api|assets|record|error|jessibuca|js|webjars|swagger-ui|v3";

    private final Gb28181Properties props;

    /** 录像地址签名器：本地静态映射（{@code record-static-enabled}）也要校验。 */
    private final RecordUrlSigner recordUrlSigner;

    public SpaWebConfiguration(Gb28181Properties props, RecordUrlSigner recordUrlSigner) {
        this.props = props;
        this.recordUrlSigner = recordUrlSigner;
    }

    /**
     * 本地静态映射下的录像鉴权。标准部署由 nginx 读磁盘、鉴权走 {@code auth_request} 子请求；
     * 但开了 {@code gb28181.record-static-enabled} 时 {@code /record/**} 由本进程发出，
     * 既不经过 nginx 也不经过 Sa-Token（它只拦 {@code /api/v1/**}），不在这里拦就是「换部署形态鉴权就没了」。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                     Object handler) {
                String query = request.getQueryString();
                String uri = request.getRequestURI() + (query == null ? "" : "?" + query);
                if (recordUrlSigner.allow(uri)) {
                    return true;
                }
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
        }).addPathPatterns("/record/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 缓存策略是「发版后用户是否需要强刷」的关键。不配 Cache-Control 时浏览器走启发式缓存
        // （取距上次修改时间的 10% 当新鲜期）：跑几天后 index.html 新鲜期变成几小时，
        // 用户拿到旧 index.html，引用已被 emptyOutDir 删掉的旧 chunk → 懒加载路由 404 白屏。
        // 实测线上散落各种旧 hash 的 chunk 404（LoginView-CW1M4nM3.js 等）。
        // 正确做法：index.html 用 no-cache（回源协商，没变 304）；带内容 hash 的产物一年 immutable。
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/index.html")
                .setCacheControl(CacheControl.noCache().mustRevalidate());

        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        // 图标：文件名固定，但内容基本不变，给一天足够（发版换图标时最多脏一天）
        registry.addResourceHandler("/favicon.svg")
                .addResourceLocations("classpath:/static/favicon.svg")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());

        // 播放器 SDK（jessibuca.js + decoder.wasm，约 1.3MB）：文件名无内容哈希，
        // 用 7 天而非 immutable，升级 SDK 后最多脏缓存一周，避免每次进实时预览都重下。
        registry.addResourceHandler("/jessibuca/**")
                .addResourceLocations("classpath:/static/jessibuca/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());

        // 第三方播放器脚本（liveplayer-lib.min.js 1.2MB + 组件 160KB）必须单独注册并入白名单：
        // /js/x.min.js 是「目录 + 带扩展名」的两段路径，会被 SPA 兜底 /{path}/** 吃掉，
        // 前端拿到 index.html，表现为 videojs 未定义、直播页起不来。文件名固定故用 7 天缓存。
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());

        // 接口文档静态资源：/{path}/** 兜底会把 /webjars/js/*.js 也转发到 index.html，
        // 文档页拿到 HTML 当脚本 → 白屏 + 语法错误。资源在 knife4j-openapi3-ui 的
        // META-INF/resources/webjars/ 下，文件名带内容哈希，可按 immutable 长缓存。
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable());

        // 录像文件默认交给 nginx：Spring 静态资源处理器处理 Range 极慢，同一段 2MB 实测
        // 16~20 秒（110 KB/s），nginx（sendfile + keep-alive）只要 0.06~0.15 秒（24 MB/s），
        // 差 200 倍，直接决定「起播 15 秒」还是「2 秒」。标准部署见 deploy/nginx-record.conf。
        // 只有无 nginx 的单机/内网部署才打开 record-static-enabled 回退到本进程。
        if (props.isRecordStaticEnabled()) {
            Path root = resolveRecordRoot();
            if (Files.isDirectory(root)) {
                String location = root.toUri().toString();
                if (!location.endsWith("/")) {
                    location = location + "/";
                }
                registry.addResourceHandler("/record/**")
                        .addResourceLocations(location)
                        // 录像文件写一次就不再变，但文件名没有内容哈希，
                        // 用较短缓存避免「删了还能播」的错觉
                        .setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
            }
        }
    }

    /** 解析录像根目录（绝对路径）。 */
    private Path resolveRecordRoot() {
        return Path.of(props.resolvedRecordRoot()).toAbsolutePath().normalize();
    }

    /**
     * 把录像文件路径换算成 {@code /record/...} URL，供接口层回填给前端直接 {@code <video src>} 或 Range 下载。
     * 返回值带签名：{@code /record/**} 由 nginx 绕过 Spring 直接发文件，Sa-Token 拦不到，
     * 不签名等于「知道路径就能下载」。这里是全平台产出录像地址的唯一出口，保证各场景不遗漏。
     * 地址与「文件由谁提供」无关（同源相对路径），文件不在录像根目录下返回 {@code null}。
     */
    public String toStaticUrl(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(filePath).toAbsolutePath().normalize();
            Path rel = resolveRecordRoot().relativize(p);
            if (rel.startsWith("..")) {
                return null;
            }
            return recordUrlSigner.sign("/record/" + rel.toString().replace('\\', '/'));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 一级路由（前端菜单路径）
        String[] routes = {
                "dashboard", "live", "control", "devices", "channels", "playback",
                "cloud", "alarms", "cascade", "users", "roles", "menus", "oplogs", "settings",
                // 免登录的对外页面：直播分享页（对齐 CMS 的 play.html）与 iframe 嵌入测试页
                "play", "iframe-test"
        };
        for (String route : routes) {
            registry.addViewController("/" + route).setViewName("forward:/index.html");
        }
        // 未知前端路由（如带参数的子路径）兜底
        registry.addViewController("/{path:^(?!" + STATIC_PREFIXES + ")[^\\.]*$}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!" + STATIC_PREFIXES + ")[^\\.]*$}/**")
                .setViewName("forward:/index.html");
    }
}
