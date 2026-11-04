package io.github.rurulalanana.starter.config;

import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.resource.CrudResourceStore;
import cloud.tianai.captcha.resource.ResourceStore;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * 给 tianai-captcha 注册背景图。
 *
 * <p><b>为什么不靠 {@code captcha.init-default-resource}</b>：那一项只加载
 * <b>滑块模板</b>（凹槽/拼图块的 png），背景图要另走 {@code default-resource-prefix}；
 * 而后者没有内置默认值，配了也不一定被读到 —— 实测两种配法都报
 * 「store中资源为空, type:SLIDER」。直接往 {@code CrudResourceStore} 里加最可控。
 *
 * <p>优先用 {@code classpath:captcha-bg/} 下自备的图（放几张就加载几张），
 * 没有时回退到 tianai 核心包自带的 {@code META-INF/cut-image/resource/1.jpg}，
 * 保证任何环境都能出图。背景图建议 600×360。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CaptchaResourceConfiguration {

    /** 自备背景图目录：把图片放进去即可，无需改代码。 */
    private static final String CUSTOM_DIR = "captcha-bg";
    /** tianai 核心包自带的兜底图。 */
    private static final String BUILTIN = "META-INF/cut-image/resource/1.jpg";

    private final ResourceStore resourceStore;

    @PostConstruct
    public void registerBackgrounds() {
        // 库把默认实现以 ResourceStore 接口类型注册，按 CrudResourceStore 注入会找不到 bean，
        // 所以按接口取、运行时判断能否写入
        if (!(resourceStore instanceof CrudResourceStore crud)) {
            log.error("当前 ResourceStore 不支持写入（{}），无法注册验证码背景图；"
                    + "若改用 Redis 存储，请确认它实现了 CrudResourceStore",
                    resourceStore.getClass().getName());
            return;
        }

        List<Resource> images = new ArrayList<>(scan("classpath*:" + CUSTOM_DIR + "/*.{jpg,jpeg,png}"));
        boolean usingCustom = !images.isEmpty();
        if (!usingCustom) {
            for (String name : new String[]{"1.jpg", "2.jpg", "3.jpg"}) {
                if (exists(BUILTIN.substring(0, BUILTIN.lastIndexOf('/') + 1) + name)) {
                    images.add(new Resource("classpath", BUILTIN.substring(0, BUILTIN.lastIndexOf('/') + 1) + name, "default"));
                }
            }
        }
        if (images.isEmpty()) {
            log.error("验证码背景图为空：{} 下没有图片，且核心包兜底图也不存在 —— 滑块验证码将无法生成",
                    CUSTOM_DIR);
            return;
        }
        // 四种图片类验证码共用一个 ResourceStore，缺哪个类型就在哪个类型上报错，所以逐个注册
        String[] types = {
                CaptchaTypeConstant.SLIDER,
                CaptchaTypeConstant.ROTATE,
                CaptchaTypeConstant.CONCAT,
                CaptchaTypeConstant.WORD_IMAGE_CLICK
        };
        for (String type : types) {
            for (Resource image : images) {
                crud.addResource(type, new Resource(image.getType(), image.getData(), "default"));
            }
        }
        log.info("验证码背景图已注册：{} 张（{}），覆盖 {} 种类型",
                images.size(), usingCustom ? CUSTOM_DIR : "核心包内置", types.length);
    }

    private static List<Resource> scan(String pattern) {
        List<Resource> out = new ArrayList<>();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            for (org.springframework.core.io.Resource r : resolver.getResources(pattern)) {
                String name = r.getFilename();
                if (name != null && !name.isBlank()) {
                    out.add(new Resource("classpath", CUSTOM_DIR + "/" + name, "default"));
                }
            }
        } catch (Exception e) {
            // 目录不存在是正常情况（用户没放自己的图），不当作错误
            log.debug("扫描 {} 未命中：{}", pattern, e.getMessage());
        }
        return out;
    }

    private static boolean exists(String classpath) {
        return CaptchaResourceConfiguration.class.getClassLoader().getResource(classpath) != null;
    }
}
