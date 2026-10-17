package io.github.rurulalanana.starter.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页查询参数基类（游标分页而非页码分页）。
 *
 * <p>GBS 的 {@code start} 是<b>偏移量</b>（从 0 开始），不是页码；
 * {@code limit} 为每页大小，{@code limit=0} 表示不分页。
 */
@Data
public class PageQuery {

    /** 分页游标开始（偏移量，从 0 开始）。 */
    private Integer start = 0;

    /** 分页大小；0 表示不分页。 */
    private Integer limit = 1000;

    /** 搜索关键字。 */
    private String q;

    /** 偏移量（负数归零）。 */
    public int offset() {
        return start == null || start < 0 ? 0 : start;
    }

    /** 每页大小（负数归零）。 */
    public int size() {
        return limit == null || limit < 0 ? 0 : limit;
    }

    /** 是否分页。 */
    public boolean paged() {
        return size() > 0;
    }

    /** 关键字是否命中（对多个字段做包含匹配，忽略大小写）。 */
    public boolean matches(String... values) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String needle = q.trim().toLowerCase();
        for (String v : values) {
            if (v != null && v.toLowerCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 对列表做游标切片。 */
    public <T> List<T> slice(List<T> all) {
        return slice(all, offset(), size());
    }

    /**
     * 安全游标切片（静态工具）。
     *
     * <p><b>为什么必须有这个方法</b>：控制器里原先直接写
     * {@code list.subList(Math.min(start, size), Math.min(start + limit, size))}，
     * 一旦调用方传 {@code start=-5}，{@code Math.min(-5, size)} 仍是 -5，
     * {@code subList(-5, …)} 直接抛 {@code IndexOutOfBoundsException: fromIndex = -5}，
     * 被全局异常处理器转成 {@code code=500}。负偏移是探测/误操作常见输入，
     * 不该表现为服务端异常。
     *
     * @param all   全量列表（可为 null，视为空）
     * @param start 偏移量（负数归零；超出长度则返回空列表）
     * @param limit 页大小（0 或负数表示不分页，返回全部）
     * @return 切片结果（新列表，避免持有原列表视图）
     */
    public static <T> List<T> slice(List<T> all, int start, int limit) {
        if (all == null || all.isEmpty()) {
            return new ArrayList<>();
        }
        if (limit <= 0) {
            return all;
        }
        int from = Math.max(start, 0);
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        int to = Math.min(from + limit, all.size());
        return new ArrayList<>(all.subList(from, to));
    }
}
