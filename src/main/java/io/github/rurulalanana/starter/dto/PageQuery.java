package io.github.rurulalanana.starter.dto;

import lombok.Data;

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
        if (!paged()) {
            return all;
        }
        int from = Math.min(offset(), all.size());
        int to = Math.min(from + size(), all.size());
        return all.subList(from, to);
    }
}
