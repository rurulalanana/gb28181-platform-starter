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
 * 前端单页应用（SPA）路由回退。
 *
 * <p>Vue Router 使用 history 模式，直接访问 {@code /dashboard}、{@code /devices}
 * 等前端路由时，Spring Boot 找不到对应控制器会返回 404。
 * 这里把非 API、非静态资源的路径统一转发到 {@code index.html}，由前端路由接管。
 *
 * <p>注意：只转发「无扩展名」的路径（前端路由形如 {@code /devices}），
 * 静态资源（{@code /assets/xxx.js}）与接口（{@code /api/v1/xxx}）不受影响。
 *
 * <p><b>排除清单必须覆盖所有一级静态资源目录</b>，否则形如
 * {@code /jessibuca/decoder.js} 这类「目录 + 带扩展名文件」的路径会被
 * 下面的通配规则吃掉、错误地返回 {@code index.html}
 * （表现为播放器脚本请求拿到一段 HTML，jessibuca 无法加载）。
 */
@Configuration
public class SpaWebConfiguration implements WebMvcConfigurer {

    /** 一级路径白名单：这些前缀下的请求交给静态资源处理器，不做 SPA 回退。 */
    private static final String STATIC_PREFIXES = "api|assets|record|error|jessibuca";

    private final Gb28181Properties props;

    /** 录像地址签名器：本地静态映射（{@code record-static-enabled}）也要校验。 */
    private final RecordUrlSigner recordUrlSigner;

    public SpaWebConfiguration(Gb28181Properties props, RecordUrlSigner recordUrlSigner) {
        this.props = props;
        this.recordUrlSigner = recordUrlSigner;
    }

    /**
     * 本地静态映射下的录像鉴权。
     *
     * <p>标准部署由 nginx 读磁盘，鉴权走 {@code auth_request} 子请求；
     * 但开了 {@code gb28181.record-static-enabled}（无 nginx 的单机试跑）时，
     * {@code /record/**} 改由本进程的资源处理器发出，<b>那条链路不经过
     * nginx、也不经过 Sa-Token（它只拦 {@code /api/v1/**}）</b> ——
     * 不同时在这里拦一道，就变成「换个部署形态，鉴权就没了」的隐形漏洞。
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
        /*
         * 缓存策略：这是「发版后用户是否需要强刷」的关键。
         *
         * <p><b>不配的后果</b>：Spring Boot 默认不给 Cache-Control，只给
         * Last-Modified。浏览器此时走<b>启发式缓存</b>（取
         * 「距上次修改时间的 10%」当新鲜期），于是：
         * <ul>
         *   <li>刚发版时新鲜期接近 0，看似正常；</li>
         *   <li>跑了几天后，index.html 的启发式新鲜期变成几小时 ——
         *       用户拿到的是<b>旧 index.html</b>，里面引用的是<b>已被删除</b>
         *       的旧 chunk（vite 构建用 emptyOutDir 清目录）；</li>
         *   <li>点进懒加载的路由 → chunk 404 → 页面白屏或报错，
         *       必须手动强刷才恢复。</li>
         * </ul>
         * 实测线上日志里散落着各种旧 hash 的 chunk 404（LoginView-CW1M4nM3.js 等）。
         *
         * <p><b>正确做法是分开配</b>：
         * <ul>
         *   <li>index.html → {@code no-cache}：允许缓存，但每次都要回源校验
         *       （靠 ETag/Last-Modified 协商，没变就是 304，代价极小）。
         *       这样一发版用户下次访问就拿到新页面。</li>
         *   <li>带内容 hash 的构建产物 → 一年 immutable：
         *       文件名变了内容才会变，可以放心长缓存。</li>
         * </ul>
         */
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

        // 播放器 SDK（jessibuca.js + decoder.wasm，约 1.3MB）加长缓存：
        // 避免每次进实时预览都重新下载（或 304 往返）。文件名无内容哈希，
        // 所以用 7 天而非 immutable：升级 SDK 后最多脏缓存一周。
        registry.addResourceHandler("/jessibuca/**")
                .addResourceLocations("classpath:/static/jessibuca/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());

        /*
         * 录像文件：<b>默认不在这里提供</b>，交给 nginx。
         *
         * <p>Spring Boot 的静态资源处理器处理 Range 请求极慢 —— 同一段 2MB
         * 实测 16~20 秒（110 KB/s），而 nginx（sendfile + keep-alive）只要
         * 0.06~0.15 秒（24 MB/s），<b>差 200 倍</b>。录像回放要按 GOP 分段
         * 下载很多次 Range，这个差距就是「起播 15 秒」与「起播 2 秒」的区别。
         *
         * <p>标准部署见 {@code deploy/nginx-record.conf}：nginx 监听公网端口，
         * {@code /record/**} 用 sendfile 直读磁盘，其余请求反代回本进程。
         *
         * <p>只有无 nginx 的单机/内网部署才需要把
         * {@code gb28181.record-static-enabled} 打开，回退到本进程提供。
         */
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
     * 把录像文件路径换算成 {@code /record/...} 形式的 URL。
     *
     * <p>供接口层回填给前端：前端拿到这个 URL 就能直接喂给
     * {@code <video src>} 或按需 Range 下载，不用经过控制器转发文件流。
     *
     * <p><b>与「文件由谁提供」无关</b>：这个 URL 是<b>同源相对路径</b>，
     * 标准部署下由 nginx 的 {@code location /record/} 直读磁盘，
     * 开了 {@code gb28181.record-static-enabled} 时也由本进程提供。
     * 所以这里不做开关判断 —— 地址总要给前端；拿不到数据属于部署配置问题，
     * 不该表现为「前端根本拿不到播放地址」。
     *
     * <p><b>返回值带签名</b>：{@code /record/**} 由 nginx 绕过 Spring 直接发文件，
     * Sa-Token 拦不到，不带签名就等于「知道路径就能下载」。
     * 这里是全平台产出录像地址的唯一出口，在此签名能保证
     * 列表、详情、分享页等所有场景都不遗漏；参见 {@link RecordUrlSigner}。
     *
     * @param filePath 录像文件绝对路径
     * @return 静态 URL；文件不在录像根目录下返回 {@code null}
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
                "cloud", "alarms", "cascade", "users", "oplogs", "settings"
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
