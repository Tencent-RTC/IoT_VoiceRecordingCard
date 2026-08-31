package com.recorder.client.asr;

import android.os.Handler;
import android.os.HandlerThread;

import com.recorder.client.AppLog;

/**
 * 实时音频管线：在独占工作线程中把设备上传的裸 Opus 帧解码为 VoiceAI 所需的
 * 16kHz 单声道 PCM。
 *
 * <p>设备端音频参数（帧时长、采样率、声道数、csd-0）一律以 recording.start
 * 成功 Response 上报的真实值为准。本类不执行任何本地语音识别，PCM 通过
 * {@link PcmSink} 交给腾讯云 VoiceAI SDK。
 */
public final class RealtimeAudioPipeline {
    private static final String TAG = "RealtimeAudioPipeline";

    public interface Callback {
        void onPipelineReady();

        /** 当前录音的音频解码链路不可用；不影响设备继续录音。 */
        void onPipelineError(String message);

        /** 非致命提示（如解码器自动重建），仅用于日志展示。 */
        void onNotice(String message);
    }

    /** 16kHz mono PCM 输出。在音频工作线程触发，实现方须非阻塞。 */
    public interface PcmSink {
        void onPcm(short[] samples);
    }

    private final Handler main;
    private final HandlerThread workerThread;
    private final Handler worker;
    private OpusAudioDecoder opusDecoder;
    private Callback callback;
    private volatile PcmSink pcmSink;
    private volatile boolean ready;
    private volatile boolean sessionActive;
    /** 点击停止后立即关闭；用于让已经排队的 feed 任务在执行时失效。 */
    private volatile boolean acceptingInput;
    /** 每次 begin/stop 都推进，防止上一会话的排队任务进入下一会话。 */
    private volatile long activeSessionGeneration;
    private long sessionGenerationSequence;
    /** 可观测性：本 session 已喂入 / 成功解码的音频帧数。 */
    private volatile int fedFrames;
    private volatile int decodedFrames;
    /** recording.start 上报的音频参数，仅在工作线程读写，用于解码器重建。 */
    private long frameDurationMs;
    private long sampleRateHz;
    private long channelCount;
    /** 因会话未激活/解码器缺失而丢弃的帧数，仅作诊断。 */
    private int droppedInactiveFrames;

    public RealtimeAudioPipeline(Handler main) {
        this.main = main;
        workerThread = new HandlerThread("realtime-audio-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setPcmSink(PcmSink sink) {
        pcmSink = sink;
    }

    /** 初始化不加载模型，只确认工作线程与实时解码管线可用。 */
    public void init() {
        worker.post(() -> {
            ready = true;
            AppLog.i(TAG, "实时音频管线就绪（Opus -> 16kHz mono PCM -> VoiceAI）");
            post(Callback::onPipelineReady);
        });
    }

    public boolean isReady() {
        return ready;
    }

    public int fedFrames() {
        return fedFrames;
    }

    public int decodedFrames() {
        return decodedFrames;
    }

    /**
     * 开始一次实时音频会话。
     *
     * @param codecConfig 设备编码器下发的 csd-0（OpusHead）；为空时按设备参数构造
     * @param frameDurationMs 设备实际上报的帧时长（毫秒）
     * @param sampleRateHz 设备实际上报的采样率
     * @param channelCount 设备实际上报的声道数
     */
    public synchronized void beginSession(byte[] codecConfig, long frameDurationMs,
                                          long sampleRateHz, long channelCount) {
        long generation = ++sessionGenerationSequence;
        activeSessionGeneration = generation;
        acceptingInput = true;
        worker.post(() -> {
            if (!ready || !isCurrentSession(generation)) {
                return;
            }
            sessionActive = false;
            releaseDecoderLocked();
            opusDecoder = new OpusAudioDecoder();
            opusDecoder.setNoticeListener(msg -> post(cb -> cb.onNotice(msg)));
            try {
                opusDecoder.start(codecConfig, frameDurationMs, sampleRateHz, channelCount);
            } catch (java.io.IOException | IllegalArgumentException exception) {
                AppLog.e(TAG, "Opus decoder init failed", exception);
                releaseDecoderLocked();
                post(cb -> cb.onPipelineError(
                        "Opus 解码器初始化失败: " + exception.getMessage()));
                return;
            }
            if (!isCurrentSession(generation)) {
                releaseDecoderLocked();
                return;
            }
            this.frameDurationMs = frameDurationMs;
            this.sampleRateHz = sampleRateHz;
            this.channelCount = channelCount;
            sessionActive = true;
            fedFrames = 0;
            decodedFrames = 0;
            droppedInactiveFrames = 0;
            AppLog.i(TAG, "Audio session begun: " + sampleRateHz + "Hz x" + channelCount
                    + ", frame=" + frameDurationMs + "ms");
        });
    }

    /** 送入一个裸 Opus Packet。可在任意线程调用。 */
    public void feed(byte[] frame) {
        long generation = activeSessionGeneration;
        if (frame == null || frame.length < 1 || !isCurrentSession(generation)) {
            return;
        }
        byte[] copy = frame.clone();
        worker.post(() -> {
            if (!isCurrentSession(generation)) {
                return;
            }
            if (!ready || !sessionActive || opusDecoder == null) {
                droppedInactiveFrames++;
                if (droppedInactiveFrames == 1 || droppedInactiveFrames % 50 == 0) {
                    post(cb -> cb.onNotice("实时音频会话未就绪，已累计丢弃 "
                            + droppedInactiveFrames + " 帧（ready=" + ready
                            + ", active=" + sessionActive
                            + ", decoder=" + (opusDecoder != null) + "）"));
                }
                return;
            }
            fedFrames++;
            if (fedFrames % 100 == 0) {
                post(cb -> cb.onNotice("实时音频统计：喂入 " + fedFrames
                        + " 帧，解码产出 " + decodedFrames + " 帧"));
            }
            if (opusDecoder.isBroken() && !recreateDecoderLocked(generation)) {
                return;
            }
            short[] samples = opusDecoder.decode(copy);
            // stopSession() 会从调用线程立即推进 generation。即使本任务已经开始
            // 解码，也不能在用户点击停止后把结果继续送给 VoiceAI。
            if (samples.length == 0 || !isCurrentSession(generation)) {
                return;
            }
            decodedFrames++;
            PcmSink sink = pcmSink;
            if (sink != null) {
                sink.onPcm(samples);
            }
        });
    }

    /**
     * 立即冻结当前会话并异步释放解码器，不冲刷尾帧。调用返回后，已排队或正在
     * 解码的旧帧也会因 generation 失效而不能进入 VoiceAI。
     */
    public synchronized void stopSession() {
        acceptingInput = false;
        activeSessionGeneration = ++sessionGenerationSequence;
        worker.post(() -> {
            sessionActive = false;
            releaseDecoderLocked();
        });
    }

    public synchronized void release() {
        acceptingInput = false;
        activeSessionGeneration = ++sessionGenerationSequence;
        worker.post(() -> {
            ready = false;
            sessionActive = false;
            releaseDecoderLocked();
            workerThread.quitSafely();
        });
    }

    private boolean recreateDecoderLocked(long generation) {
        AppLog.w(TAG, "Opus decoder broken, recreating");
        byte[] head = opusDecoder.activeHead();
        releaseDecoderLocked();
        opusDecoder = new OpusAudioDecoder();
        opusDecoder.setNoticeListener(msg -> post(cb -> cb.onNotice(msg)));
        try {
            opusDecoder.start(head, frameDurationMs, sampleRateHz, channelCount);
            if (!isCurrentSession(generation)) {
                releaseDecoderLocked();
                return false;
            }
            post(cb -> cb.onNotice("Opus 解码器持续异常，已自动重建"));
            return true;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            AppLog.e(TAG, "Opus decoder recreate failed", exception);
            releaseDecoderLocked();
            sessionActive = false;
            post(cb -> cb.onPipelineError("Opus 解码器重建失败: " + exception.getMessage()));
            return false;
        }
    }

    private void releaseDecoderLocked() {
        if (opusDecoder != null) {
            opusDecoder.release();
            opusDecoder = null;
        }
    }

    private boolean isCurrentSession(long generation) {
        return acceptingInput && activeSessionGeneration == generation;
    }

    private interface CallbackAction {
        void run(Callback callback);
    }

    private void post(CallbackAction action) {
        Callback current = callback;
        if (current != null) {
            main.post(() -> {
                Callback latest = callback;
                if (latest != null) {
                    action.run(latest);
                }
            });
        }
    }
}
