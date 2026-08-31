package com.recorder.client.asr;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;

import com.recorder.client.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * MediaCodec 原生 Opus 解码器封装。
 * 设备音频参数（采样率、声道数、帧时长、csd-0）一律以 recording.start
 * 成功 Response 上报值为准。AOSP Opus 解码器通常固定输出 48kHz PCM
 * （Opus 内部原生采样率），实际输出采样率/声道数以解码器
 * INFO_OUTPUT_FORMAT_CHANGED 上报为准：先按声道均值混成单声道，
 * 再按整数倍均值抽取降至 VoiceAI 输入所需的 16kHz。
 *
 * 注意：输出排取一律使用非阻塞 dequeue（timeout=0）；解码输出通常滞后
 * 一个包，长超时阻塞会把实时音频线程冻结数秒。
 * 所有方法必须在同一线程调用（RealtimeAudioPipeline 的工作线程）。
 */
final class OpusAudioDecoder {
    private static final String TAG = "OpusAudioDecoder";
    /** VoiceAI 外部 PCM 输入采样率；属云端 SDK 输入约束，非设备参数。 */
    private static final int CLOUD_PCM_SAMPLE_RATE = 16_000;
    private static final int DEFAULT_OUTPUT_RATE = 48_000;
    /** csd-2：seek preroll（纳秒），取 80ms（与 ExoPlayer OpusUtil 默认值一致）。 */
    private static final long SEEK_PREROLL_NS = 80_000_000L;
    private static final long INPUT_WAIT_US = 5_000L;

    /** 连续 dequeue 异常达到该值即判定解码器已损坏（需重建）。 */
    private static final int BROKEN_THRESHOLD = 5;

    /**
     * 关键事件上抛（启动、输出格式变化、持续无产出、丢帧等）。
     * 事件均在解码线程触发，经 RealtimeAudioPipeline 转发至日志；
     * 所有高频事件已在内部限流，监听方无需再做节流。
     */
    interface NoticeListener {
        void onNotice(String message);
    }

    private MediaCodec decoder;
    private int outputSampleRate = DEFAULT_OUTPUT_RATE;
    private int outputChannelCount = 1;
    private long packetIndex;
    private boolean started;
    private int consecutiveErrors;
    private byte[] activeHead;
    /** 单帧时长（微秒），来自 recording.start 成功 Response 的 frame_duration_ms。 */
    private long frameDurationUs;
    private NoticeListener noticeListener;
    /** 有实际 PCM 产出的 decode 次数。 */
    private long producedPackets;
    /** 连续 decode 无产出帧数（首几包滞后属正常，故从第 10 帧起告警）。 */
    private int emptyStreak;
    /** 因输出采样率非 16kHz 整数倍被丢弃的帧数。 */
    private int droppedByRate;

    void setNoticeListener(NoticeListener listener) {
        noticeListener = listener;
    }

    private void postNotice(String message) {
        NoticeListener l = noticeListener;
        if (l != null) {
            l.onNotice(message);
        }
    }

    /**
     * 解码器是否已进入持久 error 状态。
     *
     * <p>真机教训：向 MediaCodec Opus 解码器送入一个非 Opus 数据包
     * （例如编码器的 csd-0/OpusHead 被误当音频帧下发）后，
     * 解码器会进入持久 error 状态，此后所有 dequeue 都抛异常 ——
     * 表现为"音频帧照收、解码输出恒为空"。调用方应据此重建解码器。
     */
    boolean isBroken() {
        return consecutiveErrors >= BROKEN_THRESHOLD;
    }

    /**
     * @param opusHead 设备编码器下发的 csd-0（OpusHead），为空时按设备
     *                 上报的采样率/声道数构造默认头。
     * @param frameDurationMs 设备实际帧时长（毫秒），来自 recording.start 成功
     *                        Response 的 frame_duration_ms，必须为正数；
     *                        用于生成解码输入时间戳。
     * @param sampleRateHz 设备实际采样率，来自同一 Response 的 sample_rate_hz；
     *                      用于构造默认 OpusHead。解码器 MediaFormat 的采样率
     *                      固定为 48000——Opus 一律在 48kHz 域解码
     *                      （RFC 6716；ExoPlayer 同样按 48000 配置），
     *                      部分 codec 不接受非 48k 的 format 采样率。
     * @param channelCount 设备实际声道数，来自同一 Response 的 channel_count。
     */
    void start(byte[] opusHead, long frameDurationMs,
               long sampleRateHz, long channelCount) throws IOException {
        if (frameDurationMs <= 0) {
            throw new IllegalArgumentException(
                    "frameDurationMs must be positive: " + frameDurationMs);
        }
        if (sampleRateHz <= 0 || sampleRateHz > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("bad sampleRateHz: " + sampleRateHz);
        }
        if (channelCount <= 0 || channelCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("bad channelCount: " + channelCount);
        }
        frameDurationUs = frameDurationMs * 1000L;
        outputChannelCount = (int) channelCount;
        byte[] head = opusHead == null || opusHead.length == 0
                ? defaultOpusHead(sampleRateHz, channelCount) : opusHead;
        activeHead = head.clone();
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, DEFAULT_OUTPUT_RATE, (int) channelCount);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(head));
        // csd-1 = codec delay（OpusHead pre-skip 按 48kHz 域换算为纳秒），
        // csd-2 = seek preroll（纳秒）。
        // 真机排障：Android 12+ CCodec 路径的 c2.android.opus.decoder 缺失
        // csd-1/csd-2 时会静默吞包、零产出（旧 OMX 路径无此问题），必须附带。
        long codecDelayNs = preSkipSamples(head) * 1_000_000_000L / DEFAULT_OUTPUT_RATE;
        format.setByteBuffer("csd-1", int64Bytes(codecDelayNs));
        format.setByteBuffer("csd-2", int64Bytes(SEEK_PREROLL_NS));
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        decoder.configure(format, null, null, 0);
        decoder.start();
        started = true;
        AppLog.i(TAG, "Opus decoder started, head=" + head.length + "B, "
                + sampleRateHz + "Hz x" + channelCount
                + ", frame=" + frameDurationMs + "ms"
                + ", fmtRate=" + DEFAULT_OUTPUT_RATE
                + ", csd-1=" + codecDelayNs + "ns, csd-2=" + SEEK_PREROLL_NS + "ns");
        postNotice("Opus 解码器已启动：" + sampleRateHz + "Hz x" + channelCount
                + "，" + frameDurationMs + "ms/帧，csd-0 " + head.length + "B "
                + hexPrefix(head, 19)
                + "，format=" + DEFAULT_OUTPUT_RATE + "Hz，csd-1=" + codecDelayNs
                + "ns，csd-2=" + SEEK_PREROLL_NS + "ns");
        postNotice("decoder.getName()=" + decoder.getName());
    }

    /** 当前生效的 OpusHead（用于解码器重建）。 */
    byte[] activeHead() {
        return activeHead == null ? null : activeHead.clone();
    }

    /** 解码一个裸 Opus Packet，返回 16kHz 单声道 PCM 采样（可能滞后一个包）。 */
    short[] decode(byte[] packet) {
        if (!started || packet == null || packet.length == 0) {
            return new short[0];
        }
        queueInput(packet);
        short[] out = downsample(drain());
        if (out.length == 0) {
            emptyStreak++;
            if (emptyStreak == 10 || emptyStreak % 50 == 0) {
                postNotice("解码连续 " + emptyStreak + " 帧无产出（已送入 " + packetIndex
                        + " 包，输出 " + outputSampleRate + "Hz x" + outputChannelCount
                        + "，解码器异常计数 " + consecutiveErrors + "）");
            }
        } else {
            if (emptyStreak >= 10) {
                postNotice("解码恢复产出（此前连续 " + emptyStreak + " 帧无产出）");
            }
            emptyStreak = 0;
            producedPackets++;
            if (producedPackets <= 3) {
                postNotice("解码产出 #" + producedPackets + "：" + out.length + " samples");
            }
        }
        return out;
    }

    /** 会话结束前有界轮询取出滞留的解码输出。 */
    short[] drainPending(long maxWaitMs) {
        if (!started) {
            return new short[0];
        }
        ByteArrayOutputStream collector = new ByteArrayOutputStream();
        long deadline = SystemClock.elapsedRealtime() + maxWaitMs;
        int idleRounds = 0;
        while (SystemClock.elapsedRealtime() < deadline && idleRounds < 3) {
            int before = collector.size();
            byte[] chunk = drain();
            if (chunk.length > 0) {
                collector.write(chunk, 0, chunk.length);
            }
            idleRounds = collector.size() > before ? 0 : idleRounds + 1;
            if (idleRounds > 0) {
                SystemClock.sleep(10L);
            }
        }
        byte[] bytes = collector.toByteArray();
        short[] samples = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return downsample(samples);
    }

    private void queueInput(byte[] packet) {
        int inputIndex;
        try {
            inputIndex = decoder.dequeueInputBuffer(INPUT_WAIT_US);
        } catch (RuntimeException exception) {
            consecutiveErrors++;
            AppLog.e(TAG, "Opus dequeue input failed (" + consecutiveErrors + ")", exception);
            if (consecutiveErrors == 1 || consecutiveErrors == BROKEN_THRESHOLD) {
                postNotice("Opus dequeueInputBuffer 异常(" + consecutiveErrors + ")："
                        + exception);
            }
            return;
        }
        if (inputIndex < 0) {
            // 输入 buffer 持续不可用意味着解码器已停滞（不再消费输入），
            // 与异常一样计入损坏判定。
            consecutiveErrors++;
            AppLog.w(TAG, "Opus decoder input buffer unavailable, drop packet ("
                    + consecutiveErrors + ")");
            if (consecutiveErrors == 1 || consecutiveErrors == BROKEN_THRESHOLD) {
                postNotice("Opus 解码器输入 buffer 不可用(" + consecutiveErrors + ")，丢包");
            }
            return;
        }
        ByteBuffer input = decoder.getInputBuffer(inputIndex);
        if (input == null) {
            return;
        }
        input.clear();
        input.put(packet);
        if (packetIndex == 0) {
            // 首包解析 TOC：估算单包实际时长，与设备声明的 frame_duration_ms 比对。
            postNotice("首包送入解码器：" + packet.length + "B，"
                    + describePacket(packet) + "，hex "
                    + hexPrefix(packet, 12));
        }
        decoder.queueInputBuffer(inputIndex, 0, packet.length,
                packetIndex * frameDurationUs, 0);
        packetIndex++;
    }

    /** 非阻塞排取所有当前可用输出，返回 48kHz（或实现报告的采样率）s16le 字节。 */
    private byte[] drain() {
        ByteArrayOutputStream collector = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outputIndex;
            try {
                outputIndex = decoder.dequeueOutputBuffer(info, 0L);
            } catch (RuntimeException exception) {
                consecutiveErrors++;
                AppLog.e(TAG, "Opus dequeue output failed (" + consecutiveErrors + ")", exception);
                if (consecutiveErrors == 1 || consecutiveErrors == BROKEN_THRESHOLD) {
                    postNotice("Opus dequeueOutputBuffer 异常(" + consecutiveErrors + ")："
                            + exception);
                }
                break;
            }
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat changed = decoder.getOutputFormat();
                outputSampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (changed.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    outputChannelCount = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
                AppLog.i(TAG, "Opus decoder output: " + outputSampleRate
                        + "Hz x" + outputChannelCount);
                postNotice("Opus 解码输出格式：" + outputSampleRate
                        + "Hz x" + outputChannelCount);
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }
            ByteBuffer output = decoder.getOutputBuffer(outputIndex);
            if (output != null && info.size > 0) {
                byte[] chunk = new byte[info.size];
                output.get(chunk);
                collector.write(chunk, 0, chunk.length);
                consecutiveErrors = 0;   // 仅真实产出时复位损坏判定
            }
            decoder.releaseOutputBuffer(outputIndex, false);
        }
        return collector.toByteArray();
    }

    /**
     * 将解码输出（outputSampleRate / outputChannelCount 交织 s16）转换为
     * VoiceAI 所需的 16kHz 单声道：先按声道均值混音，再按整数倍均值抽取。
     * 输出采样率非 16kHz 整数倍时无法对齐，丢帧并告警。
     */
    private short[] downsample(short[] pcmOut) {
        if (pcmOut.length == 0) {
            return pcmOut;
        }
        short[] mono = pcmOut;
        int channels = outputChannelCount;
        if (channels > 1) {
            int frames = pcmOut.length / channels;
            mono = new short[frames];
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += pcmOut[i * channels + c];
                }
                mono[i] = (short) (sum / channels);
            }
        }
        if (outputSampleRate == CLOUD_PCM_SAMPLE_RATE) {
            return mono;
        }
        if (outputSampleRate % CLOUD_PCM_SAMPLE_RATE == 0) {
            int ratio = outputSampleRate / CLOUD_PCM_SAMPLE_RATE;
            int outLength = mono.length / ratio;
            short[] downsampled = new short[outLength];
            for (int i = 0; i < outLength; i++) {
                int sum = 0;
                for (int j = 0; j < ratio; j++) {
                    sum += mono[i * ratio + j];
                }
                downsampled[i] = (short) (sum / ratio);
            }
            return downsampled;
        }
        AppLog.w(TAG, "Unexpected decoder output rate " + outputSampleRate + ", drop");
        droppedByRate++;
        if (droppedByRate == 1 || droppedByRate % 50 == 0) {
            postNotice("解码输出 " + outputSampleRate + "Hz 非 16kHz 整数倍，"
                    + "已累计丢弃 " + droppedByRate + " 帧");
        }
        return new short[0];
    }

    private short[] downsample(byte[] pcmBytes) {
        short[] samples = new short[pcmBytes.length / 2];
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return downsample(samples);
    }

    void release() {
        started = false;
        if (decoder != null) {
            try {
                decoder.stop();
            } catch (RuntimeException ignored) {
            }
            decoder.release();
            decoder = null;
        }
    }

    /**
     * 解析 Opus Packet 的 TOC（RFC 6716 §3.1），估算单包实际时长，
     * 并与设备声明的 frame_duration_ms 比对——不符会导致 PTS 失真，
     * 也可能提示设备并未按声明参数编码。
     */
    private String describePacket(byte[] packet) {
        int toc = packet[0] & 0xFF;
        int config = toc >> 3;
        int code = toc & 0x3;
        double frameMs;
        if (config < 12) {          // SILK NB/MB/WB
            frameMs = new double[]{10, 20, 40, 60}[config & 3];
        } else if (config < 24) {   // SILK SWB/FB、HYBRID
            frameMs = (config & 1) == 0 ? 10 : 20;
        } else {                    // CELT-only
            frameMs = new double[]{2.5, 5, 10, 20}[config & 3];
        }
        int frames = 1;
        if (code == 1 || code == 2) {
            frames = 2;
        } else if (code == 3 && packet.length > 1) {
            frames = packet[1] & 0x3F;
        }
        double totalMs = frameMs * frames;
        double declaredMs = frameDurationUs / 1000.0;
        String desc = "TOC=0x" + String.format(Locale.US, "%02x", toc)
                + " cfg=" + config + " code=" + code + " frames=" + frames
                + " → " + totalMs + "ms/包";
        if (Math.abs(totalMs - declaredMs) > 0.01) {
            desc += "（与设备声明 " + declaredMs + "ms 不符！）";
        }
        return desc;
    }

    private static String hexPrefix(byte[] data, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(data.length, maxBytes);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "%02x", data[i]));
            if (i + 1 < n) {
                sb.append(' ');
            }
        }
        if (data.length > n) {
            sb.append("…");
        }
        return sb.toString();
    }

    /** 从 OpusHead 解析 pre-skip 样本数（offset 10，小端 uint16）；头过短取常见默认 312。 */
    private static long preSkipSamples(byte[] head) {
        if (head == null || head.length < 12) {
            return 312L;
        }
        return ((head[11] & 0xFFL) << 8) | (head[10] & 0xFFL);
    }

    /**
     * csd-1/csd-2 的 64 位整数编码，字节序与 ExoPlayer OpusUtil 一致
     * （nativeOrder，Android ARM 设备上为小端）。
     */
    private static ByteBuffer int64Bytes(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0, value);
    }

    /**
     * 按设备上报的采样率/声道数构造 OpusHead
     * （version 1, pre-skip 312, gain 0, mapping family 0）。
     * mapping family 0 仅支持 1~2 声道；更多声道必须由设备下发 csd-0。
     */
    private static byte[] defaultOpusHead(long sampleRateHz, long channelCount) {
        if (channelCount < 1 || channelCount > 2) {
            throw new IllegalArgumentException(
                    "default OpusHead supports 1~2 channels: " + channelCount);
        }
        return new byte[]{
                'O', 'p', 'u', 's', 'H', 'e', 'a', 'd',
                1,
                (byte) channelCount,
                (byte) 0x38, (byte) 0x01,
                (byte) sampleRateHz,
                (byte) (sampleRateHz >> 8),
                (byte) (sampleRateHz >> 16),
                (byte) (sampleRateHz >> 24),
                0, 0,
                0
        };
    }
}
