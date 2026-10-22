package io.github.rurulalanana.starter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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

    public SpaWebConfiguration(Gb28181Properties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 播放器 SDK（jessibuca.js + decoder.wasm，约 1.3MB）加长缓存：
        // 避免每次进实时预览都重新下载（或 304 往返）。文件名无内容哈希，
        // 所以用 7 天而非 immutable：升级 SDK 后最多脏缓存一周。
        registry.addResourceHandler("/jessibuca/**")
                .addResourceLocations("classpath:/static/jessibuca/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());

        /*
         * 录像文件直接暴露成静态资源：/record/**  →  <record-root>/
         *
         * <p><b>为什么要有这条</b>：{@code /api/v1/cloudrecord/file/{id}} 是走
         * 控制器转发文件流，虽然也支持 Range，但前端要拿它做「浏览器端重封装」
         * 就得先整段下载（57MB 的录像要等 20 多秒才出画面）。
         * 交给 Spring 的静态资源处理器后：
         * <ul>
         *   <li>浏览器原生 {@code <video src>} 直接播，Range 由容器实现，
         *       首帧只需下载文件头 + 少量数据，秒开；</li>
         *   <li>进度条拖动是浏览器原生行为，不需要任何服务端 seek 逻辑；</li>
         *   <li>不会经过 Sa-Token 拦截器，媒体请求不用带 token。</li>
         * </ul>
         *
         * <p><b>安全提示</b>：这条映射会把录像目录整个暴露出去（只要知道路径就能下载）。
         * 适合内网/单机部署；公网部署请配合 Nginx 限制来源 IP，或把
         * {@code gb28181.record-static-enabled} 关掉，改用需要登录的 API 接口。
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

    /**
     * 解析录像根目录。
     *
     * <p>与 {@code Gb28181PlatformConfiguration} 保持一致：配置为空时
     * 用「工作目录 + /record」。
     *
     * @return 录像根目录（绝对路径）
     */
    private Path resolveRecordRoot() {
        String root = props.getRecordRoot();
        Path p = (root == null || root.isBlank())
                ? Path.of(System.getProperty("user.dir"), "record")
                : Path.of(root);
        return p.toAbsolutePath().normalize();
    }

    /**
     * 把录像文件路径换算成 {@code /record/...} 形式的静态 URL。
     *
     * <p>供接口层回填给前端：前端拿到这个 URL 就能直接喂给
     * {@code <video src>}，不用再经过控制器转发文件流。
     *
     * @param filePath 录像文件绝对路径
     * @return 静态 URL；静态映射关闭、或文件不在录像根目录下返回 {@code null}
     */
    public String toStaticUrl(String filePath) {
        if (!props.isRecordStaticEnabled() || filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(filePath).toAbsolutePath().normalize();
            Path rel = resolveRecordRoot().relativize(p);
            if (rel.startsWith("..")) {
                return null;
            }
            return "/record/" + rel.toString().replace('\\', '/');
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
