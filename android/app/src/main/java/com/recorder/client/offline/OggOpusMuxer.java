package com.recorder.client.offline;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 将设备的「2 字节小端 packet 长度 + 裸 Opus packet」文件转为 Ogg Opus。
 *
 * <p>这里不解码音频，因而不依赖 Android 编解码 API、libopus 或 libogg；仅按
 * Ogg RFC 写容器页并解析 Opus TOC 计算 48 kHz granule position，兼容 Android 8。
 * 下载原始字节的 CRC 校验必须在调用本类前完成。
 */
public final class OggOpusMuxer {

    private static final byte[] OPUS_HEAD = "OpusHead".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OPUS_TAGS = "OpusTags".getBytes(StandardCharsets.US_ASCII);
    private static final int[] CRC_TABLE = buildCrcTable();

    private OggOpusMuxer() {
    }

    /**
     * 创建临时文件并原子替换 output。codecConfig 为有效 OpusHead 时原样保留，
     * 否则根据 download.start 中的采样率、声道数构造标准 mapping-family-0 头。
     */
    public static void mux(File input, File output, byte[] codecConfig,
                           long sampleRateHz, long channelCount) throws IOException {
        if (input == null || output == null || sampleRateHz <= 0L || channelCount <= 0L
                || channelCount > 255L) {
            throw new IOException("Ogg Opus 转换参数非法");
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建 Ogg 输出目录: " + parent);
        }
        File temp = new File(output.getAbsolutePath() + ".tmp");
        if (temp.exists() && !temp.delete()) {
            throw new IOException("无法覆盖旧的 Ogg 临时文件");
        }

        int serial = stableSerial(input, output);
        try (InputStream in = new BufferedInputStream(new FileInputStream(input));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
            int sequence = 0;
            writePage(out, 0x02, 0L, serial, sequence++,
                    validOpusHead(codecConfig) ? codecConfig.clone()
                            : defaultOpusHead(sampleRateHz, channelCount));
            writePage(out, 0x00, 0L, serial, sequence++, opusTags());

            long granule = 0L;
            // 使用一包 look-ahead，才可在写最后一个 audio page 时正确设置 EOS。
            byte[] packet = nextPacket(in);
            if (packet == null) {
                throw new IOException("离线录音不包含任何 Opus packet");
            }
            while (true) {
                long samples = opusPacketSamples(packet);
                if (samples <= 0L || Long.MAX_VALUE - granule < samples) {
                    throw new IOException("无法解析 Opus packet 时长");
                }
                granule += samples;
                byte[] following = nextPacket(in);
                writePage(out, following == null ? 0x04 : 0x00, granule,
                        serial, sequence++, packet);
                if (following == null) {
                    break;
                }
                packet = following;
            }
        } catch (IOException e) {
            // 保留原始 .part 以供下次重试，只有 Ogg 临时文件可安全删除。
            temp.delete();
            throw e;
        }
        if (output.exists() && !output.delete()) {
            temp.delete();
            throw new IOException("无法替换已有 Ogg 文件");
        }
        if (!temp.renameTo(output)) {
            temp.delete();
            throw new IOException("无法完成 Ogg 文件原子替换");
        }
    }

    /** 从设备流中读取一包；正常 EOF 只允许出现在新 packet 长度之前。 */
    private static byte[] nextPacket(InputStream in) throws IOException {
        int lo = in.read();
        if (lo < 0) {
            return null;
        }
        int hi = in.read();
        if (hi < 0) {
            throw new EOFException("离线文件截断在 Opus packet 长度字段中");
        }
        int length = lo | (hi << 8);
        if (length <= 0) {
            throw new IOException("离线文件包含空 Opus packet");
        }
        byte[] packet = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(packet, read, length - read);
            if (n < 0) {
                throw new EOFException("离线文件截断在 Opus packet 中");
            }
            read += n;
        }
        return packet;
    }

    private static byte[] defaultOpusHead(long sampleRateHz, long channelCount) {
        byte[] head = new byte[19];
        System.arraycopy(OPUS_HEAD, 0, head, 0, OPUS_HEAD.length);
        head[8] = 1; // version
        head[9] = (byte) channelCount;
        // pre-skip = 0，input sample rate 是原始编码输入率。
        putLe32(head, 12, (int) sampleRateHz);
        head[18] = 0; // mapping family 0
        return head;
    }

    private static byte[] opusTags() throws IOException {
        byte[] vendor = "Recorder client".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(OPUS_TAGS);
        writeLe32(data, vendor.length);
        data.write(vendor);
        writeLe32(data, 0); // user_comment_list_length
        return data.toByteArray();
    }

    private static boolean validOpusHead(byte[] data) {
        if (data == null || data.length < 19) {
            return false;
        }
        for (int i = 0; i < OPUS_HEAD.length; i++) {
            if (data[i] != OPUS_HEAD[i]) {
                return false;
            }
        }
        return data[8] == 1 && (data[9] & 0xFF) > 0;
    }

    /** Ogg Opus 的 granule position 固定使用 48 kHz 采样数。 */
    private static long opusPacketSamples(byte[] packet) throws IOException {
        if (packet.length < 1) {
            throw new IOException("空 Opus packet");
        }
        int toc = packet[0] & 0xFF;
        int framesCode = toc & 0x03;
        int frames;
        switch (framesCode) {
            case 0:
                frames = 1;
                break;
            case 1:
            case 2:
                frames = 2;
                break;
            default:
                if (packet.length < 2) {
                    throw new IOException("Opus code-3 packet 缺失帧数");
                }
                frames = packet[1] & 0x3F;
                if (frames == 0) {
                    throw new IOException("Opus code-3 packet 帧数为 0");
                }
                break;
        }
        long perFrame;
        if ((toc & 0x80) != 0) {
            perFrame = (48_000L << ((toc >> 3) & 0x03)) / 400L;
        } else if ((toc & 0x60) == 0x60) {
            perFrame = (toc & 0x08) != 0 ? 48_000L / 50L : 48_000L / 100L;
        } else {
            perFrame = (48_000L << ((toc >> 3) & 0x03)) / 100L;
        }
        long total = perFrame * frames;
        if (total <= 0L || total > 5_760L) { // Opus 单包最大 120 ms
            throw new IOException("Opus packet 时长超出规范范围");
        }
        return total;
    }

    private static void writePage(OutputStream out, int headerType, long granule,
                                  int serial, int sequence, byte[] packet) throws IOException {
        byte[] lacing = lacingValues(packet.length);
        byte[] header = new byte[27 + lacing.length];
        header[0] = 'O';
        header[1] = 'g';
        header[2] = 'g';
        header[3] = 'S';
        header[4] = 0;
        header[5] = (byte) headerType;
        putLe64(header, 6, granule);
        putLe32(header, 14, serial);
        putLe32(header, 18, sequence);
        // bytes 22..25 remain zero during checksum calculation
        header[26] = (byte) lacing.length;
        System.arraycopy(lacing, 0, header, 27, lacing.length);
        int crc = oggCrc(header, packet);
        putLe32(header, 22, crc);
        out.write(header);
        out.write(packet);
    }

    private static byte[] lacingValues(int length) throws IOException {
        if (length < 0) {
            throw new IOException("负的 packet 长度");
        }
        int count = length / 255 + 1;
        if (count > 255) {
            throw new IOException("单个 Opus packet 过大，无法放入一个 Ogg page");
        }
        byte[] lacing = new byte[count];
        int remaining = length;
        for (int i = 0; i < count; i++) {
            int value = Math.min(255, remaining);
            lacing[i] = (byte) value;
            remaining -= value;
        }
        return lacing;
    }

    private static int oggCrc(byte[] header, byte[] payload) {
        int crc = 0;
        for (byte value : header) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) & 0xFF) ^ (value & 0xFF)];
        }
        for (byte value : payload) {
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

    private static int stableSerial(File input, File output) {
        int value = input.getAbsolutePath().hashCode() * 31 + output.getName().hashCode();
        return value == 0 ? 1 : value;
    }

    private static void putLe32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static void putLe64(byte[] target, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            target[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }
}
