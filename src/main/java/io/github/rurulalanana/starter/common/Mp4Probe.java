package io.github.rurulalanana.starter.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * MP4 文件的编码探测。
 *
 * <p>只做一件事：<b>从文件头里读出视频轨的编码标识</b>（{@code avc1} / {@code hvc1} …），
 * 好让上层决定「要不要转码」。
 *
 * <p><b>为什么自己解析而不用 ffmpeg</b>：ffprobe 在服务器上没装（只有 ffmpeg），
 * 而调 ffmpeg 探一次要起一个进程、几十毫秒。录像文件动辄几百 MB，
 * 每次播都探一遍太浪费。直接读 box 头是纯内存操作，微秒级。
 *
 * <p><b>MP4 box 结构</b>（只用到本类关心的部分）：
 * <pre>
 * [size(4)][type(4)][payload]
 *   size == 1 → 后面跟 8 字节的 64 位长度
 *   size == 0 → 一直到文件末尾
 *
 * moov
 *  └ trak（一个轨道）
 *     └ mdia
 *        ├ hdlr  → handlerType == "vide" 表示视频轨
 *        └ minf
 *           └ stbl
 *              └ stsd  → 第一个 sample entry 的前 4 字节就是编码标识
 * </pre>
 */
public final class Mp4Probe {

    /** H264（浏览器普遍支持）。 */
    public static final String AVC = "avc1";

    /** H265/HEVC（Chrome for Linux 不带专有解码器，播不了）。 */
    public static final String HEVC = "hvc1";

    /** MP4 容器：{@code moov} 在前（faststart），浏览器可以边下边播。 */
    private static final String MOOV = "moov";

    /** 采样描述表：里面第一个 sample entry 的格式即编码标识。 */
    private static final String STSD = "stsd";

    private Mp4Probe() {
    }

    /**
     * 探测视频轨的编码标识。
     *
     * @param file MP4 文件
     * @return {@code "avc1"} / {@code "hvc1"} 等；探测不出时返回 {@code null}
     */
    public static String videoCodec(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            List<Box> tops = readBoxes(ch, 0, ch.size());
            for (Box top : tops) {
                if (MOOV.equals(top.type())) {
                    String codec = codecOfMoov(ch, top);
                    if (codec != null) {
                        return codec;
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 该文件能否被浏览器直接播放。
     *
     * <p>判据是「视频轨是 H264」——H265 虽然 {@code canPlayType} 返回
     * {@code probably}，实测播放时报 {@code PIPELINE_ERROR_DECODE}，
     * Chrome for Linux 不带 HEVC 专有解码器，必须转码。
     *
     * @param file MP4 文件
     * @return 能直播返回 {@code true}
     */
    public static boolean browserPlayable(Path file) {
        String codec = videoCodec(file);
        return AVC.equals(codec) || "avc3".equals(codec);
    }

    /** 从 moov 里找视频轨的编码标识。 */
    private static String codecOfMoov(FileChannel ch, Box moov) throws IOException {
        for (Box trak : readBoxes(ch, moov.start(), moov.end())) {
            if (!"trak".equals(trak.type())) {
                continue;
            }
            for (Box mdia : readBoxes(ch, trak.start(), trak.end())) {
                if (!"mdia".equals(mdia.type())) {
                    continue;
                }
                String handler = null;
                for (Box box : readBoxes(ch, mdia.start(), mdia.end())) {
                    if ("hdlr".equals(box.type())) {
                        handler = readHandlerType(ch, box);
                    } else if ("minf".equals(box.type())) {
                        String codec = codecOfMinf(ch, box);
                        // 视频轨才认；音频轨的 stsd 是 mp4a
                        if (codec != null && "vide".equals(handler)) {
                            return codec;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 从 minf → stbl → stsd 里取第一个 sample entry 的格式。 */
    private static String codecOfMinf(FileChannel ch, Box minf) throws IOException {
        for (Box stbl : readBoxes(ch, minf.start(), minf.end())) {
            if (!"stbl".equals(stbl.type())) {
                continue;
            }
            for (Box stsd : readBoxes(ch, stbl.start(), stbl.end())) {
                if (!STSD.equals(stsd.type())) {
                    continue;
                }
                // stsd 载荷：version/flags(4) + entryCount(4)，之后是 sample entry
                long pos = stsd.start() + 8;
                if (pos + 8 > stsd.end()) {
                    continue;
                }
                ByteBuffer head = ByteBuffer.allocate(8);
                readFully(ch, pos, head);
                head.flip();
                int size = head.getInt();
                String format = new String(head.array(), 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if (size >= 8 && !format.isBlank()) {
                    return format.trim();
                }
            }
        }
        return null;
    }

    /** 读 hdlr 的 handlerType（载荷偏移 8 处的 4 字节）。 */
    private static String readHandlerType(FileChannel ch, Box hdlr) throws IOException {
        long pos = hdlr.start() + 8;
        if (pos + 4 > hdlr.end()) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(4);
        readFully(ch, pos, buf);
        buf.flip();
        byte[] b = new byte[4];
        buf.get(b);
        return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * 读取 [start, end) 区间内的 box 列表。
     *
     * <p>只下钻一层，够用了；解析失败就返回空列表（探测不出编码，
     * 上层按「需要转码」处理，最坏情况是多转一道，不会出错）。
     */
    private static List<Box> readBoxes(FileChannel ch, long start, long end) throws IOException {
        List<Box> out = new ArrayList<>();
        long pos = start;
        while (pos + 8 <= end) {
            ByteBuffer head = ByteBuffer.allocate(16);
            readFully(ch, pos, head);
            head.flip();
            long size = head.getInt() & 0xFFFFFFFFL;
            String type = new String(head.array(), 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long headerLen = 8;
            if (size == 1) {
                if (head.limit() < 16) {
                    break;
                }
                size = head.getLong();
                headerLen = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < headerLen || pos + size > end) {
                break;
            }
            out.add(new Box(type, pos + headerLen, pos + size));
            pos += size;
        }
        return out;
    }

    /** 定点读取，读不满就返回已有部分（不抛，避免探测失败影响主流程）。 */
    private static void readFully(FileChannel ch, long pos, ByteBuffer buf) throws IOException {
        long p = pos;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, p);
            if (n < 0) {
                return;
            }
            p += n;
        }
    }

    /** 一个 box：类型 + 载荷起止位置。 */
    private record Box(String type, long start, long end) {
    }
}
