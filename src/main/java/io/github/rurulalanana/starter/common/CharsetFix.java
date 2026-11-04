package io.github.rurulalanana.starter.common;

import java.nio.charset.Charset;

/**
 * GBK 字节被误按 UTF-8 解码后的乱码修复。标准 6.10 规定信令字符集为 GB 18030，设备实际多发 GBK；
 * {@code ManscdpXml.decode} 先试 UTF-8、仅当出现 {@code U+FFFD} 才回退 GBK。
 *
 * <p>盲区：<b>GBK 汉字的两字节常恰好是合法 UTF-8</b>。例如「平台」{@code C6 BD CC A8} 按 UTF-8
 * 解出 {@code U+01BD}(ƽ) + {@code U+0328}(̨)，全程无替换字符，永不回退 GBK。
 * 反解：把误解字符串按 UTF-8 编回字节、再用 GBK 解码，即可还原。
 */
public final class CharsetFix {

    private static final Charset GBK = Charset.forName("GBK");

    private CharsetFix() {
    }

    /**
     * 修复「GBK 字节被当 UTF-8 解码」的乱码，只在高置信度时改写：
     * 含非 ASCII；按 UTF-8 编回字节后用 GBK 解码不产生 {@code U+FFFD}；
     * 还原结果不同、不再带乱码特征、不含异常字符且至少含一个汉字。
     */
    public static String repair(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (isAscii(text) || !looksLikeMojibake(text)) {
            return text;
        }
        try {
            byte[] raw = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String gbk = new String(raw, GBK);
            // GBK 解码出现替换字符 → 原始字节不是合法 GBK，放弃
            if (gbk.indexOf('\uFFFD') >= 0) {
                return text;
            }
            // 还原后仍带乱码特征 → 不是这种乱码
            if (looksLikeMojibake(gbk)) {
                return text;
            }
            // 还原出 CJK 兼容形式等异常字符 → 原文并非 GBK 字节
            if (hasGarbage(gbk)) {
                return text;
            }
            // 还原后应至少含一个中日韩汉字，否则可能误伤（如法语、越南语等合法拉丁文本）
            if (!containsCjk(gbk)) {
                return text;
            }
            return gbk;
        } catch (Exception ignore) {
            return text;
        }
    }

    /**
     * 是否具备「GBK 被误读为 UTF-8」的特征。只有 GBK 双字节恰好构成合法 UTF-8 两字节序列
     * 时才会漏检，此时结果落在 {@code U+0080~U+07FF}。上界取 {@code U+036F} 而非 {@code U+07FF}，
     * 是为了排除整串落在误解码区间的合法外文：{@code U+0370~U+03FF} 希腊字母、
     * {@code U+0400~U+04FF} 西里尔字母会被误改成汉字。
     * 另要求全部非 ASCII 都可疑、至少 2 个且为偶数：一个 GBK 汉字误解后必为 2 字符，
     * 这排除 {@code Café}、{@code Müller} 这类单个重音字母的合法名称。
     */
    private static boolean looksLikeMojibake(String text) {
        int nonAscii = 0;
        int suspicious = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x7F) {
                continue;
            }
            nonAscii++;
            if (c <= 0x036F) {
                suspicious++;
            }
        }
        return suspicious >= 2 && suspicious == nonAscii && (suspicious & 1) == 0;
    }

    /** 还原结果是否含二次乱码痕迹：非 GBK 文本按 GBK 解码常落在 CJK 兼容区（如 {@code U+FE41 ﹁}）。 */
    private static boolean hasGarbage(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xFE30 && c <= 0xFE6F) {
                return true;
            }
        }
        return false;
    }

    /** 是否至少含一个中日韩统一表意文字。 */
    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
