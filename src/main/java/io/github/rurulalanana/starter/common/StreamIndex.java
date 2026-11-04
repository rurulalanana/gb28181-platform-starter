package io.github.rurulalanana.starter.common;

/**
 * 码流索引（点播时选哪一路码流）。GB/T 28181—2022 附录 G 规定 SSRC 为
 * {@code 1 位实时/历史 + 5 位域标识 + 4 位域内流标识}，最后 4 位即码流编号：0 主码流、1 子码流。
 * 不指定时只能用域内自增序号，设备按默认策略推流，无法主动选子码流省带宽。
 * {@link #AUTO} 保持原有行为（域内自增序号），不改变现有点播；显式选主/子码流才会改 SSRC。
 */
public enum StreamIndex {

    /** 自动选择（默认）：SSRC 后 4 位用域内自增序号，即 2016 版做法，兼容性最好。 */
    AUTO("auto", "自动选择", -1),

    /** 2022 版：主码流，SSRC 后 4 位填 0。 */
    STREAM_NUMBER_0("streamnumber:0", "主码流", 0),

    /** 2022 版：子码流，SSRC 后 4 位填 1。 */
    STREAM_NUMBER_1("streamnumber:1", "子码流", 1);

    /** 配置值（存库 / 传输用）。 */
    private final String value;

    /** 界面显示文案。 */
    private final String label;

    /** 码流编号（SSRC 后 4 位）；{@code -1} 表示自动，用域内自增序号。 */
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

    /** 按配置值解析；{@code null} / 空 / 非法值都按 {@link #AUTO} 处理。 */
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

    /**
     * 设备是否声明了多路码流（即界面上该不该给「码流切换」这个选项）。
     *
     * <p>依据是国标目录里的 {@code StreamNumberList}（GB/T 28181—2022 A.2.4.3）。
     * <b>这是设备自己声明的能力，是唯一可靠依据</b>：
     * 2016 版设备不认 SSRC 后 4 位的码流编号，即使平台发出子码流请求，
     * 设备照样推主码流 —— 用户切了却看不出任何变化，比不给这个选项更糟。
     *
     * <p>因此<b>没上报一律按「不支持」处理</b>：宁可少一个选项，
     * 也不要给一个点了没反应的开关。
     *
     * @param streamNumberList 设备上报的码流编号列表，如 {@code "0,1"}；可为 {@code null}
     * @return 是否支持切换码流
     */
    public static boolean supportsMultiStream(String streamNumberList) {
        if (streamNumberList == null || streamNumberList.isBlank()) {
            return false;
        }
        /*
         * 数【不同】编号的个数而不是字符串里逗号的个数：
         * 设备可能重复上报同一个编号（实测有型号发 "0,0"），
         * 按逗号数会误判成「有两路码流」。
         */
        java.util.Set<String> numbers = new java.util.HashSet<>();
        for (String part : streamNumberList.split("[,，;；\\s]+")) {
            if (!part.isBlank()) {
                numbers.add(part.trim());
            }
        }
        return numbers.size() >= 2;
    }
}
