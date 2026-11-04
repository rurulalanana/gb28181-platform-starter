package io.github.rurulalanana.starter.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间展示格式的回归测试。
 *
 * <p>后端所有出参里的时间字段都是 {@code String}（见 {@code vo} 包），
 * 靠调用方自觉用 {@link TimeFormats#toDisplay} 转换。一旦有人写成
 * {@code LocalDateTime.toString()}，JSON 里就会冒出
 * {@code 2026-11-02T15:48:51.624609865}（T 分隔符 + 纳秒），
 * <b>而编译期完全发现不了</b> —— 曾经在「API 密钥生成时间」和「操作日志时间」上各踩一次。
 *
 * <p>所以这里既钉住 {@link TimeFormats#toDisplay} 的输出形态，也做一次源码扫描，
 * 拦住新写的 {@code toString()}。
 */
class TimeFormatsTest {

    /** 纳秒长度不固定，只要求「没有 T、没有小数点」。 */
    private static final Pattern DISPLAY_SHAPE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

    @Test
    @DisplayName("toDisplay 输出 yyyy-MM-dd HH:mm:ss，不带 T 与纳秒")
    void toDisplayHasNoIsoMarker() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 2, 15, 48, 51, 624_609_865);
        String out = TimeFormats.toDisplay(t);

        assertEquals("2026-11-02 15:48:51", out);
        assertTrue(DISPLAY_SHAPE.matcher(out).matches(), "实际：" + out);
        assertTrue(out.indexOf('T') < 0, "不能有 T 分隔符：" + out);
        assertTrue(out.indexOf('.') < 0, "不能有纳秒小数：" + out);
    }

    @Test
    @DisplayName("null 安全：toDisplay(null) 返回 null 而不是 \"null\"")
    void toDisplayHandlesNull() {
        assertNull(TimeFormats.toDisplay(null));
        assertNull(TimeFormats.toIso(null));
        assertNull(TimeFormats.toCompact(null));
    }

    @Test
    @DisplayName("与 toString 对比：证明直接 toString 确实不合规（这条是本次 bug 的成因）")
    void localDateTimeToStringIsNotAcceptable() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 2, 15, 48, 51, 624_609_865);
        String raw = t.toString();

        assertTrue(raw.indexOf('T') > 0, "LocalDateTime.toString() 一定带 T：" + raw);
        assertTrue(raw.indexOf('.') > 0, "LocalDateTime.toString() 带纳秒：" + raw);
        assertTrue(!DISPLAY_SHAPE.matcher(raw).matches(), "不应误判为合规格式");
    }

    /**
     * 源码扫描：{@code service} 与 {@code web} 包下不允许把时间 getter 直接 {@code toString()}。
     *
     * <p>为什么用源码扫描而不是类型系统：出参字段是 {@code String}，
     * 赋什么都不会编译报错，只能靠约定；而约定靠人记一定会漏。
     *
     * <p>允许的写法是 {@code TimeFormats.toDisplay(...)}。
     */
    @Test
    @DisplayName("源码扫描：没有时间字段被直接 toString()")
    void noRawToStringOnTimeGetters() throws IOException {
        Path base = Paths.get("src", "main", "java", "io", "github", "rurulalanana", "starter");
        if (!Files.exists(base)) {
            // 从 IDE 以仓库根目录为工作目录跑时也能正常跳过，不误报
            return;
        }

        // 时间 getter 的典型形态：getCreateTime / getUpdatedAt / getLastRegisterAt ...
        Pattern dangerous = Pattern.compile(
                "get\\w*(?:Time|At|Date)\\w*\\(\\)\\s*\\.\\s*toString\\s*\\(");

        List<String> hits = new ArrayList<>();
        for (String pkg : new String[]{"service", "web"}) {
            Path dir = base.resolve(pkg);
            if (!Files.exists(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    String[] lines = text.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (dangerous.matcher(lines[i]).find()) {
                            hits.add(f.getFileName() + ":" + (i + 1) + "  " + lines[i].trim());
                        }
                    }
                }
            }
        }

        assertTrue(hits.isEmpty(),
                "时间字段必须用 TimeFormats.toDisplay()，不能 toString()（会输出带 T 与纳秒的 ISO 串）：\n  "
                        + String.join("\n  ", hits));
    }
}
