package io.github.rurulalanana.starter.common;

/**
 * 码流索引（点播时选择哪一路码流）。
 *
 * <p><b>标准依据</b>：GB/T 28181—2022 附录 G 规定 SSRC 的格式为
 * {@code 1 位实时/历史 + 5 位域标识 + 4 位域内流标识}，
 * 其中最后 4 位在 2022 版中明确为<b>码流编号</b>：
 * 0 表示主码流、1 表示子码流。
 *
 * <p><b>为什么要有这个开关</b>：同一路通道通常同时提供主码流（高分辨率）与
 * 子码流（低分辨率）。点播时若不指定，平台只能用域内自增序号填 SSRC 后 4 位，
 * 设备只能按默认策略推流 —— 想看子码流省带宽就做不到。
 * 显式指定后，SSRC 后 4 位即码流编号，设备据此选择对应码流。
 *
 * <p><b>兼容性</b>：{@link #AUTO} 保持原有行为（域内自增序号），
 * 是默认值，不改变任何现有点播行为；只有显式选主/子码流才会改变 SSRC。
 */
public enum StreamIndex {

    /**
     * 自动选择（默认）。
     *
     * <p>SSRC 后 4 位用域内自增序号，即 2016 版的做法。
     * 设备侧按自己的默认策略推流，兼容性最好。
     */
    AUTO("auto", "自动选择", -1),

    /** 2022 版：主码流，SSRC 后 4 位填 0。 */
    STREAM_NUMBER_0("streamnumber:0", "主码流", 0),

    /** 2022 版：子码流，SSRC 后 4 位填 1。 */
    STREAM_NUMBER_1("streamnumber:1", "子码流", 1);

    /** 配置值（存库 / 传输用）。 */
    private final String value;

    /** 界面显示文案。 */
    private final String label;

    /**
     * 码流编号（SSRC 后 4 位）。
     *
     * <p>{@code -1} 表示自动，用域内自增序号而非固定编号。
     */
    private final int streamNumber;

    StreamIndex(String value, String label, int streamNumber) {
        this.value = value;
        this.label = label;
        this.streamNumber = streamNumber;
    }

    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    public int getStreamNumber() {
        return streamNumber;
    }

    /**
     * 按配置值解析。
     *
     * @param value 配置值；{@code null} / 空 / 非法值都按 {@link #AUTO} 处理
     * @return 对应的码流索引
     */
    public static StreamIndex of(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String v = value.trim();
        for (StreamIndex s : values()) {
            if (s.value.equalsIgnoreCase(v)) {
                return s;
            }
        }
        return AUTO;
    }
}
