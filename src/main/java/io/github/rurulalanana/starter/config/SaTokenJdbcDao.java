package io.github.rurulalanana.starter.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.auto.SaTokenDaoByStringFollowObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sa-Token 会话持久层：PostgreSQL 实现。默认实现把会话放进程内存，每次重启都让所有用户掉登录；
 * 这里改存 {@code sa_token_store}，重启后 token 仍有效。
 *
 * <p>只实现 Object 层（String/Session 由 default 方法自动委托）；值用 JDK 序列化存 BYTEA；
 * 过期语义 {@code -1} 永久、{@code -2}/0 空操作、{@code >0} 剩余秒，时区计算全在 SQL 侧（{@code now()}）；
 * 反序列化失败（大版本升级后类结构变化）清条目返回 null，不让请求 500。
 */
@Slf4j
public class SaTokenJdbcDao implements SaTokenDaoByStringFollowObject {

    private final JdbcTemplate jdbc;

    /** 过期数据清理线程（daemon，不影响 JVM 退出）。 */
    private final ScheduledExecutorService cleaner;

    public SaTokenJdbcDao(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sa-token-store-cleaner");
            t.setDaemon(true);
            return t;
        });
        // 延迟 1 分钟首跑（让应用先完成启动），之后每小时清理一次
        this.cleaner.scheduleWithFixedDelay(this::purgeExpired, 1, 60, TimeUnit.MINUTES);
    }

    @Override
    public Object getObject(String key) {
        List<byte[]> rows = jdbc.query(
                "SELECT obj FROM sa_token_store WHERE k = ? AND (expire_at IS NULL OR expire_at > now())",
                (rs, i) -> rs.getBytes(1), key);
        return rows.isEmpty() ? null : deserialize(rows.get(0), key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(String key, Class<T> classType) {
        return (T) getObject(key);
    }

    @Override
    public void setObject(String key, Object object, long timeout) {
        // 与官方内存实现一致：0 与 -2（立即过期）视为空操作，不写入也不删除
        if (timeout == 0 || timeout <= SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        byte[] data = serialize(object, key);
        if (data == null) {
            return;
        }
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            jdbc.update("""
                    INSERT INTO sa_token_store (k, obj, expire_at, updated_at)
                    VALUES (?, ?, NULL, now())
                    ON CONFLICT (k) DO UPDATE
                        SET obj = EXCLUDED.obj, expire_at = NULL, updated_at = now()
                    """, key, data);
        } else {
            jdbc.update("""
                    INSERT INTO sa_token_store (k, obj, expire_at, updated_at)
                    VALUES (?, ?, now() + ? * interval '1 second', now())
                    ON CONFLICT (k) DO UPDATE
                        SET obj = EXCLUDED.obj, expire_at = EXCLUDED.expire_at, updated_at = now()
                    """, key, data, timeout);
        }
    }

    @Override
    public void updateObject(String key, Object object) {
        // 与官方内存实现一致：仅当键存在且未过期时更新（保留原过期时间）
        byte[] data = serialize(object, key);
        if (data == null) {
            return;
        }
        jdbc.update("""
                UPDATE sa_token_store SET obj = ?, updated_at = now()
                 WHERE k = ? AND (expire_at IS NULL OR expire_at > now())
                """, data, key);
    }

    @Override
    public void deleteObject(String key) {
        jdbc.update("DELETE FROM sa_token_store WHERE k = ?", key);
    }

    @Override
    public long getObjectTimeout(String key) {
        List<Object[]> rows = jdbc.query(
                "SELECT expire_at, FLOOR(EXTRACT(EPOCH FROM (expire_at - now())))::bigint "
                        + "FROM sa_token_store WHERE k = ?",
                (rs, i) -> new Object[]{rs.getTimestamp(1), rs.getObject(2)}, key);
        if (rows.isEmpty()) {
            return SaTokenDao.NOT_VALUE_EXPIRE;
        }
        Object[] row = rows.get(0);
        if (row[0] == null) {
            return SaTokenDao.NEVER_EXPIRE;
        }
        long remain = row[1] == null ? 0 : ((Number) row[1]).longValue();
        if (remain <= 0) {
            // 已过期：懒删除，下次查询即清理
            deleteObject(key);
            return SaTokenDao.NOT_VALUE_EXPIRE;
        }
        return remain;
    }

    @Override
    public void updateObjectTimeout(String key, long timeout) {
        // 与官方内存实现一致：直接设置过期点，不检查键是否存在
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            jdbc.update("UPDATE sa_token_store SET expire_at = NULL, updated_at = now() WHERE k = ?", key);
        } else if (timeout > 0) {
            jdbc.update("UPDATE sa_token_store SET expire_at = now() + ? * interval '1 second', updated_at = now() "
                    + "WHERE k = ?", timeout, key);
        }
    }

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        String p = prefix == null ? "" : prefix;
        StringBuilder sql = new StringBuilder("SELECT k FROM sa_token_store WHERE k LIKE ?");
        List<Object> args = new ArrayList<>();
        args.add(p + "%");
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND k LIKE ?");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY k ").append(sortType ? "ASC" : "DESC");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(size <= 0 ? Integer.MAX_VALUE : size);
        args.add(Math.max(start, 0));
        return jdbc.queryForList(sql.toString(), String.class, args.toArray());
    }

    @Override
    public void destroy() {
        cleaner.shutdownNow();
    }

    /** 删除已过期的会话（读取路径已做过滤，这里只是防止表无限增长）。 */
    private void purgeExpired() {
        try {
            int n = jdbc.update(
                    "DELETE FROM sa_token_store WHERE expire_at IS NOT NULL AND expire_at <= now()");
            if (n > 0) {
                log.info("已清理 {} 条过期的 Sa-Token 会话", n);
            }
        } catch (Exception e) {
            log.warn("清理过期 Sa-Token 会话失败：{}", e.toString());
        }
    }

    /** JDK 序列化；失败时记日志并返回 null（调用方跳过本次写入）。 */
    private byte[] serialize(Object object, String key) {
        if (object == null) {
            deleteObject(key);
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(object);
            return bos.toByteArray();
        } catch (IOException e) {
            log.error("Sa-Token 会话序列化失败（key={}，type={}）：{}",
                    key, object.getClass().getName(), e.toString());
            return null;
        }
    }

    /**
     * JDK 反序列化。失败时（典型：Sa-Token 大版本升级后旧数据不兼容）清除该条目并返回 null，
     * 表现为「该 token 未登录」，用户重新登录即可。
     */
    private Object deserialize(byte[] data, String key) {
        if (data == null || data.length == 0) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return ois.readObject();
        } catch (Exception e) {
            log.warn("Sa-Token 会话反序列化失败，已清除该条目（key={}）：{}", key, e.toString());
            deleteObject(key);
            return null;
        }
    }
}
