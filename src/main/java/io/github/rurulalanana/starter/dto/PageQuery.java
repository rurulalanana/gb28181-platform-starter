package io.github.rurulalanana.starter.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页查询参数基类（游标分页而非页码分页）。GBS 的 {@code start} 是偏移量（从 0 开始）不是页码，
 * {@code limit} 为每页大小，{@code limit=0} 表示不分页。
 */
@Data
public class PageQuery {

    /** 分页游标开始（偏移量，从 0 开始）。 */
    private Integer start = 0;

    /** 分页大小；0 表示不分页。 */
    private Integer limit = 1000;

    private String q;

    public int offset() {
        return start == null || start < 0 ? 0 : start;
    }

    public int size() {
        return limit == null || limit < 0 ? 0 : limit;
    }

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
     * 安全游标切片。必须做边界收敛：控制器原先直接 {@code subList(min(start,size), ...)}，
     * 传 {@code start=-5} 会抛 {@code IndexOutOfBoundsException} 并被转成 {@code code=500}，
     * 而负偏移是探测/误操作常见输入，不该表现为服务端异常。
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
