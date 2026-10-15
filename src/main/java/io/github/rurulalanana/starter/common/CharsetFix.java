package io.github.rurulalanana.starter.common;

import java.nio.charset.Charset;

/**
 * GBK 字节被误按 UTF-8 解码后的乱码修复。
 *
 * <p><b>问题成因</b>：GB/T 28181 标准 6.10 规定信令字符集为 GB 18030，
 * 设备实际发的中文多为 GBK 字节。而 MANSCDP 解码器（{@code gb28181-sip} 的
 * {@code ManscdpXml.decode}）先按 UTF-8 尝试，仅当出现替换字符 {@code U+FFFD}
 * 时才回退 GBK。
 *
 * <p>该判据存在盲区：<b>GBK 汉字的两字节序列常恰好是合法 UTF-8</b>。
 * 例如「平台」的 GBK 字节是 {@code C6 BD CC A8}，
 * 按 UTF-8 解码得到 {@code U+01BD}(ƽ) + {@code U+0328}(̨) ——
 * 全程无替换字符，于是被误判为 UTF-8，永远不回退 GBK，中文即变成 {@code ƽ̨}。
 *
 * <p>反解可行：把已误解的字符串按 UTF-8 编回字节，再用 GBK 解码即可还原。
 * 本类负责判断「是否属于这种乱码」并还原。
 */
public final class CharsetFix {

    private static final Charset GBK = Charset.forName("GBK");

    private CharsetFix() {
    }

    /**
     * 修复「GBK 字节被当 UTF-8 解码」的乱码。
     *
     * <p>只在高置信度时改写，其余原样返回，避免误伤合法文本：
     * <ol>
     *   <li>文本含 ASCII 之外的字符（纯 ASCII 无需处理）</li>
     *   <li>按 UTF-8 编回字节后，用 GBK 解码<b>不</b>产生替换字符
     *       （否则说明原本就不是 GBK 字节）</li>
     *   <li>还原结果与原文不同，且还原后<b>不再</b>含「拉丁扩展 + 组合附加符号」
     *       这类 GBK 被误读的典型特征</li>
     * </ol>
     *
     * @param text 可能乱码的文本
     * @return 修复后的文本；无需修复时返回原值
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
     * 是否具备「GBK 被误读为 UTF-8」的特征。
     *
     * <p><b>码点推导</b>：只有当 GBK 的双字节 {@code (b1,b2)} 恰好构成合法 UTF-8 两字节序列
     * （{@code b1∈C2~DF} 且 {@code b2∈80~BF}）时才会漏检；此时误解码结果为
     * {@code ((b1&0x1F)<<6)|(b2&0x3F)}，即落在 {@code U+0080~U+07FF}。
     * 例如「平台」{@code C6 BD CC A8} → {@code U+01BD}(ƽ) + {@code U+0328}(̨)。
     *
     * <p><b>为何上界取 {@code U+036F} 而非 {@code U+07FF}</b>：需要排除那些
     * 「整串都落在误解码区间」的合法外文，否则会被误改：
     * <ul>
     *   <li>{@code U+0370~U+03FF} 希腊字母 —— {@code Αθήνα} 会被误改成 {@code 铻掕儍钀庤皳浼}</li>
     *   <li>{@code U+0400~U+04FF} 西里尔字母 —— {@code Москва} 同理</li>
     *   <li>{@code U+0590} 起为希伯来/阿拉伯文等</li>
     * </ul>
     * 收窄到 {@code U+0080~U+036F}（拉丁补充/扩展 A/B + 组合附加符号）后，
     * 上述文本自然排除。GBK 汉字误解码的产物绝大多数落在此区间。
     *
     * <p><b>为何要求「全部非 ASCII 都可疑、至少 2 个、且为偶数」</b>：
     * 一个 GBK 汉字误解码后必为 2 个字符，故真乱码的非 ASCII 字符数恒为偶数且 ≥2。
     * 这排除 {@code Café}、{@code Müller} 这类仅 1 个重音字母的合法名称；
     * 偶数约束进一步排除含奇数个可疑字符的混合文本。
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

    /**
     * 还原结果是否含明显的「二次乱码」痕迹。
     *
     * <p>把非 GBK 文本的 UTF-8 字节按 GBK 解码，产物常落在 CJK 兼容形式区
     * （如 {@code U+FE41 ﹁}），正常中文名不会出现这些字符。
     */
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
