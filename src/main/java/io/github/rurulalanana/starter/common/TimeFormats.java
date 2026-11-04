package io.github.rurulalanana.starter.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * 时间格式工具。GBS 在不同接口混用多种格式，这里统一收敛：
 * {@code YYYY-MM-DDTHH:mm:ss}（查询类接口、SIP MANSCDP）、{@code YYYYMMDDHHmmss}（云端录像、文件名）、
 * {@code YYYY-MM-DD HH:mm:ss}（展示）、{@code YYYYMMDD}（日期）、{@code YYYYMM}（按月统计）。
 */
public final class TimeFormats {

    /** SIP / MANSCDP 标准格式。 */
    public static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    public static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    public static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    public static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    /** 时钟格式（概览页折线图的 X 轴刻度）。 */
    public static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private TimeFormats() {
    }

    /**
     * 宽松解析：依次尝试 ISO、紧凑、展示、日期、月份。必须包含 YYYYMM：
     * {@code querymonthly/queryflags} 以月份为参数，DATE（8 位）匹配不上 6 位串。
     * 解析失败返回 {@code null}。
     */
    public static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        for (DateTimeFormatter f : new DateTimeFormatter[]{ISO, COMPACT, DISPLAY, DATE, MONTH}) {
            try {
                if (f == DATE) {
                    return LocalDate.parse(t, f).atStartOfDay();
                }
                if (f == MONTH) {
                    return YearMonth.parse(t, f).atDay(1).atStartOfDay();
                }
                return LocalDateTime.parse(t, f);
            } catch (DateTimeParseException ignore) {
                // 尝试下一种格式
            }
        }
        return null;
    }

    public static String toIso(LocalDateTime time) {
        return time == null ? null : ISO.format(time);
    }

    public static String toCompact(LocalDateTime time) {
        return time == null ? null : COMPACT.format(time);
    }

    public static String toDisplay(LocalDateTime time) {
        return time == null ? null : DISPLAY.format(time);
    }

    /** {@link Date} → {@link LocalDateTime}。 */
    public static LocalDateTime of(Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    /** {@link LocalDateTime} → {@link Date}。 */
    public static Date toDate(LocalDateTime time) {
        return time == null ? null : Date.from(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    /** epoch 毫秒 → {@link LocalDateTime}。 */
    public static LocalDateTime ofEpochMilli(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}
