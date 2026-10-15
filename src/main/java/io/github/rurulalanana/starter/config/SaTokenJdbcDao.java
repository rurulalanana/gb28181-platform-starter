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
 * Sa-Token 会话持久层：PostgreSQL 实现（登录 token 落库，服务重启后登录态保留）。
 *
 * <p><b>为什么需要它</b>：Sa-Token 默认的 {@code SaTokenDaoDefaultImpl} 把会话放在
 * 进程内存里，服务每次重启（部署、崩溃拉起）都会让所有用户的登录态失效，
 * 被迫重新登录。本实现把会话改存 PostgreSQL 的 {@code sa_token_store} 表，
 * 重启后 token 依然有效。
 *
 * <p><b>实现要点</b>：
 * <ul>
 *   <li>实现 {@link SaTokenDaoByStringFollowObject} 分层接口——只需提供
 *       <b>Object 层</b>的读写，String 与 Session 的读写由接口 default 方法
 *       自动委托到这里（Sa-Token 1.41+ 的官方推荐写法）。</li>
 *   <li>值统一用 <b>JDK 序列化</b>存 BYTEA（{@code SaSession} 已实现
 *       {@code Serializable} 且显式声明了 serialVersionUID，跨版本兼容性好）。</li>
 *   <li>过期时间语义与官方内存实现保持一致：
 *       {@code -1} 永不过期（{@code expire_at} 为 NULL）、{@code -2}/0 视为空操作、
 *       {@code >0} 为剩余秒数；<b>时区计算全部在 SQL 侧</b>（{@code now()}），
 *       避免 JVM 与数据库时区不一致导致的偏移。</li>
 *   <li>反序列化失败（如 Sa-Token 大版本升级后类结构变化）时清除该条目并返回
 *       null（当作未登录），用户重新登录即可，不会让请求 500。</li>
 *   <li>过期数据清理：启动后 1 分钟首跑，之后每小时一次（daemon 线程，不阻塞停机）。
 *       读取路径本身也带 {@code expire_at > now()} 过滤与懒删除，清理只是防表膨胀。</li>
 * </ul>
 *
 * <p>表结构见 {@code db/schema.sql}（启动时由 SchemaInitializer 幂等创建）。
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

    // ==================== Object 读写（String/Session 自动跟随） ====================

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

    // ==================== 会话搜索 ====================

    /**
     * 按键名搜索（前缀匹配 + 关键字包含 + 分页排序）。
     *
     * <p>语义对齐官方 {@code SaFoxUtil.searchList}：{@code start} 取非负，
     * {@code size <= 0} 表示不限条数。
     */
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

    // ==================== 生命周期与清理 ====================

    /** Sa-Token 卸载本 Dao 时停止清理线程。 */
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

    // ==================== 序列化 ====================

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
     * JDK 反序列化。
     *
     * <p>失败时（典型场景：Sa-Token 大版本升级后旧数据不兼容）清除该条目并返回 null，
     * 表现为「该 token 未登录」，用户重新登录即可，不影响服务可用性。
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
