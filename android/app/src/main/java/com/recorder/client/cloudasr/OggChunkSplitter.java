package com.recorder.client.cloudasr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 将整段 Ogg Opus 字节流按 Ogg page 边界拆分为若干不超过指定大小的分片。
 *
 * <p>工程要点：云端转码服务要求每个上传文件自身可解码，因此绝不能在任意字节
 * 处硬切。本实现先解析出全部 page 边界；每个分片都以原文件的 OpusHead /
 * OpusTags 两个头页开头，再跟随连续的音频页。分片内所有页的 page sequence
 * 重新从 0 连续编号并重算 Ogg CRC（算法与项目内 {@code OggOpusMuxer} 相同，
 * 多项式 0x04C11DB7、无反射），保证每一片都是结构合法、校验自洽的 Ogg 流；
 * granule position 保持原值，使各片时间轴与原文件一致。
 *
 * <p>按任务约定，现阶段不评估拆分对识别准确率的影响（切点可能落在词语中间）。
 */
public final class OggChunkSplitter {

    private static final int[] CRC_TABLE = buildCrcTable();

    private OggChunkSplitter() {
    }

    /**
     * 拆分 Ogg 字节流。
     *
     * @param data          完整 Ogg 文件字节
     * @param maxChunkBytes 单个分片允许的最大原始字节数（未 base64）
     * @return 有序分片列表；原文件不超限或本就无多页时返回单元素列表
     * @throws IOException 输入不是合法的 Ogg page 序列
     */
    public static List<byte[]> split(byte[] data, long maxChunkBytes) throws IOException {
        List<int[]> pages = indexPages(data);
        if (data.length <= maxChunkBytes) {
            return Collections.singletonList(data);
        }
        if (pages.size() < 3) {
            throw new IOException("Ogg 文件过小，无法按页拆分");
        }
        int[] headPage = pages.get(0);
        int[] tagsPage = pages.get(1);
        long headerBytes = (long) headPage[1] + tagsPage[1];
        if (headerBytes >= maxChunkBytes) {
            throw new IOException("Ogg 头页已超过单片大小上限");
        }

        List<byte[]> chunks = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        int sequence = 0;
        for (int i = 0; i < pages.size(); i++) {
            int[] page = pages.get(i);
            boolean headerPage = i < 2;
            if (!headerPage && current.size() > headerBytes
                    && current.size() + page[1] > maxChunkBytes) {
                chunks.add(current.toByteArray());
                current = new ByteArrayOutputStream();
                sequence = 0;
                writeRewrittenPage(current, data, headPage, sequence++);
                writeRewrittenPage(current, data, tagsPage, sequence++);
            }
            writeRewrittenPage(current, data, page, sequence++);
        }
        if (current.size() > 0) {
            chunks.add(current.toByteArray());
        }
        return chunks;
    }

    /** 返回 {@code {offset, length}} 列表；任何字节对不上页结构都视为非法输入。 */
    private static List<int[]> indexPages(byte[] data) throws IOException {
        List<int[]> pages = new ArrayList<>();
        int pos = 0;
        while (pos < data.length) {
            if (pos + 27 > data.length) {
                throw new IOException("Ogg 页头截断");
            }
            if (data[pos] != 'O' || data[pos + 1] != 'g'
                    || data[pos + 2] != 'g' || data[pos + 3] != 'S') {
                throw new IOException("非 Ogg 页（缺少 OggS 捕获模式）");
            }
            int segments = data[pos + 26] & 0xFF;
            if (pos + 27 + segments > data.length) {
                throw new IOException("Ogg 段表截断");
            }
            int bodyLength = 0;
            for (int i = 0; i < segments; i++) {
                bodyLength += data[pos + 27 + i] & 0xFF;
            }
            int pageLength = 27 + segments + bodyLength;
            if (pos + pageLength > data.length) {
                throw new IOException("Ogg 页体截断");
            }
            pages.add(new int[]{pos, pageLength});
            pos += pageLength;
        }
        if (pages.isEmpty()) {
            throw new IOException("空 Ogg 文件");
        }
        return pages;
    }

    /** 复制一页、重写分片内连续 sequence 并重算 CRC 后写出。 */
    private static void writeRewrittenPage(ByteArrayOutputStream out, byte[] data,
                                           int[] page, int newSequence) {
        byte[] copy = new byte[page[1]];
        System.arraycopy(data, page[0], copy, 0, page[1]);
        putLe32(copy, 18, newSequence);
        putLe32(copy, 22, 0);
        putLe32(copy, 22, oggCrc(copy));
        out.write(copy, 0, copy.length);
    }

    private static int oggCrc(byte[] page) {
        int crc = 0;
        for (byte value : page) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) & 0xFF) ^ (value & 0xFF)];
        }
        return crc;
    }

    private static int[] buildCrcTable() {
        int[] table = new int[256];
        for (int i = 0; i < table.length; i++) {
            int value = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                value = (value << 1) ^ ((value & 0x80000000) != 0 ? 0x04C11DB7 : 0);
            }
            table[i] = value;
        }
        return table;
    }

    private static void putLe32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
