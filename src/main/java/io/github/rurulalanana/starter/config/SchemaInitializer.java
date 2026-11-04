package io.github.rurulalanana.starter.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库表结构初始化：启动时执行 {@code classpath:db/schema.sql}。脚本分建表（IF NOT EXISTS）、
 * 注释、初始数据（ON CONFLICT DO NOTHING）三段且整体幂等，可重复执行。
 *
 * <p>执行方式是整份脚本一次发送（PG 驱动支持多语句，远程库能把 N 次往返降为 1，启动快十几秒）；
 * 整体失败则降级为逐条执行以定位具体语句。
 * 需要重建时清空库再启动：{@code DROP SCHEMA public CASCADE; CREATE SCHEMA public;}。
 * DBA 管理表结构时设 {@code gb28181.starter.init-schema=false} 关闭。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SchemaInitializer {

    private static final String[] SCRIPTS = {"db/schema.sql"};

    private final Gb28181Properties props;
    private final DataSource dataSource;

    @Bean
    public ApplicationRunner schemaInitRunner() {
        return args -> {
            if (!props.getStarter().isInitSchema()) {
                log.info("已跳过数据库表结构初始化（gb28181.starter.init-schema=false）");
                return;
            }
            int ok = 0;
            int failed = 0;
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                conn.setAutoCommit(true);
                for (String script : SCRIPTS) {
                    ClassPathResource resource = new ClassPathResource(script);
                    if (!resource.exists()) {
                        log.warn("未找到 {}，跳过", script);
                        continue;
                    }
                    String sql = StreamUtils.copyToString(resource.getInputStream(),
                            StandardCharsets.UTF_8);
                    List<String> statements = splitStatements(sql);
                    // 快路径：整份脚本一次发送（远程库启动能快十几秒）。
                    try {
                        stmt.execute(sql);
                        ok = statements.size();
                    } catch (Exception batchError) {
                        // 慢路径：整体失败时逐条执行，定位到具体语句（幂等脚本通常不该走到这里）
                        log.debug("整份脚本执行失败，改为逐条执行定位：{}", batchError.getMessage());
                        for (String statement : statements) {
                            try {
                                stmt.execute(statement);
                                ok++;
                            } catch (Exception e) {
                                failed++;
                                log.warn("执行 SQL 失败: {} —— {}", firstLine(statement), e.getMessage());
                            }
                        }
                    }
                    log.info("已执行 {}", script);
                }
            } catch (Exception e) {
                log.error("数据库表结构初始化失败（请检查连接与权限）：{}", e.getMessage());
                return;
            }
            log.info("数据库表结构初始化完成：成功 {} 条，失败 {} 条", ok, failed);
            if (failed > 0) {
                log.warn("有 {} 条语句失败，请核对上面列出的语句；"
                        + "脚本是幂等的，正常情况下不应出现失败", failed);
            }
        };
    }

    /**
     * 按分号切分 SQL，供降级路径逐条执行与统计条数。必须跳过 {@code DO $$ ... $$} 块内的分号，
     * 否则会把 DO 块切碎导致语法错误。当前脚本没有 DO 块，但保留这段逻辑方便以后加数据订正块。
     */
    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String dollarTag = null;
        for (String rawLine : sql.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }
            current.append(rawLine).append('\n');
            int i = 0;
            while (i < line.length()) {
                char ch = line.charAt(i);
                if (ch == '$') {
                    int end = line.indexOf('$', i + 1);
                    if (end > i) {
                        String tag = line.substring(i, end + 1);
                        // 只认 $ 或 $identifier$ 形式，避免误判普通字符串里的 $
                        if (tag.length() == 2 || tag.substring(1, tag.length() - 1)
                                .matches("[A-Za-z_][A-Za-z0-9_]*")) {
                            if (dollarTag == null) {
                                dollarTag = tag;
                            } else if (dollarTag.equals(tag)) {
                                dollarTag = null;
                            }
                            i = end + 1;
                            continue;
                        }
                    }
                }
                i++;
            }
            // 不在美元块内且行尾是分号 → 一条完整语句
            if (dollarTag == null && line.endsWith(";")) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private static String firstLine(String statement) {
        int idx = statement.indexOf('\n');
        String head = idx > 0 ? statement.substring(0, idx) : statement;
        return head.length() > 120 ? head.substring(0, 120) + "…" : head;
    }
}
