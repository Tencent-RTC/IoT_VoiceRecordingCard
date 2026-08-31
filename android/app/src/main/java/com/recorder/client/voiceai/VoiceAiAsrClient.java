package com.recorder.client.voiceai;

import android.os.Handler;

import com.recorder.client.AppLog;
import com.recorder.client.trtc.TrtcUserSig;
import com.recorder.client.trtc.TrtcUserSigConfig;
import com.tencent.voiceai.TXRealtimeASR;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * VoiceAI SDK（腾讯云实时流式 ASR）薄封装，与 UI/业务解耦。
 *
 * <p>外部喂 PCM 模式（enableCustomCapture=true）：复用实时音频管线产出的
 * 16kHz mono PCM，无需麦克风权限；SDK 内部自动重采样，此处固定按 16kHz/单声道喂入。
 *
 * <p>鉴权复用 trtc 包基建：voiceId 每次会话生成新 UUID，并作为 UserSig 的
 * identifier 现场签名（VoiceAI 体系以 VoiceID 代替 UserID）。
 *
 * <p>start/stop/destroy 可在任意线程调用（内部 synchronized + SDK 自身切线程）；
 * {@link #feedPcm16k} 设计为从音频解码线程高频调用。启动成功后（
 * onRealtimeASRStarted 之前）的 PCM 会在内部有界缓冲，启动回调到达时一次性冲刷，
 * 避免会话头部音频丢失。
 */
public final class VoiceAiAsrClient {

    /** 识别结果与生命周期事件，一律在主线程触发。 */
    public interface Callback {
        void onStarted(String voiceId);

        void onMessage(String segmentId, String text, boolean isCompleted, int speakerId);

        void onStopped();

        void onError(int errorCode, String errorMsg);
    }

    private static final String TAG = "VoiceAiAsrClient";
    private static final long USER_SIG_EXPIRE_SECONDS = 7L * 24 * 60 * 60;
    /** 启动期 PCM 缓冲上限：约 12 秒 16kHz mono s16le。 */
    private static final int MAX_PENDING_PCM_BYTES = 16000 * 2 * 12;

    private static final int IDLE = 0;
    private static final int STARTING = 1;
    private static final int ACTIVE = 2;
    private static final int STOPPING = 3;

    private final Handler main;
    private volatile Callback callback;
    private TXRealtimeASR engine;
    private boolean engineLoadFailed;
    private int state = IDLE;
    /** STOPPING 期间收到 start 请求时挂起，待 stopped/error 后自动重开。 */
    private boolean startPending;
    private final ByteArrayOutputStream pendingPcm = new ByteArrayOutputStream();
    private boolean pendingOverflowLogged;

    public VoiceAiAsrClient(Handler main) {
        this.main = main;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /** 当前是否有未终结的云端识别会话。 */
    public synchronized boolean isSessionActive() {
        return state == STARTING || state == ACTIVE || state == STOPPING;
    }

    /** 开启一次云端识别会话（录音开始时调用）。 */
    public synchronized void start() {
        if (state == STOPPING) {
            // 上一次会话尚未终结：挂起本次启动，终结回调到达后自动重开。
            startPending = true;
            return;
        }
        if (state != IDLE) {
            return;
        }
        if (!ensureEngine()) {
            notifyError(-1, "VoiceAI SDK 加载失败");
            return;
        }
        String voiceId = UUID.randomUUID().toString();
        String userSig = TrtcUserSig.genTestUserSig(TrtcUserSigConfig.getSdkAppId(),
                voiceId, USER_SIG_EXPIRE_SECONDS, TrtcUserSigConfig.getSecretKey());
        if (userSig.isEmpty()) {
            notifyError(-1, "VoiceAI UserSig 生成失败");
            return;
        }
        TXRealtimeASR.Params params = new TXRealtimeASR.Params();
        params.sdkAppId = String.valueOf(TrtcUserSigConfig.getSdkAppId());
        params.userSig = userSig;
        params.voiceId = voiceId;
        params.sourceLanguage = "";
        params.enableCustomCapture = true;
        state = STARTING;
        startPending = false;
        pendingPcm.reset();
        pendingOverflowLogged = false;
        try {
            // 过滤句末标点并开启实时说话人分离。须在 startRealtimeASR 之前调用；
            // 13.5 SDK 会通过 Message.speakerId 回传当前分段的说话人编号。
            String extraResult = engine.callExperimentalAPI("{\"api\":\"setExtraParams\","
                    + "\"params\":{\"extraRequestParams\":\"engine_model_type=bigmodel"
                    + "&needvad=1&filter_punc=1&speaker_diarization=1\"}}");
            AppLog.i(TAG, "云端 ASR 已开启实时说话人分离，setExtraParams 返回: " + extraResult);
            engine.startRealtimeASR(params);
            AppLog.i(TAG, "云端 ASR 启动中（voiceId " + voiceId + "）");
        } catch (RuntimeException exception) {
            state = IDLE;
            notifyError(-1, "VoiceAI 启动异常: " + exception.getMessage());
        }
    }

    /** 喂入一段 16kHz mono PCM；可从 ASR 解码线程调用。 */
    public void feedPcm16k(short[] samples) {
        if (samples == null || samples.length == 0) {
            return;
        }
        synchronized (this) {
            if (state == STARTING) {
                if (pendingPcm.size() + samples.length * 2 <= MAX_PENDING_PCM_BYTES) {
                    byte[] bytes = toBytes(samples);
                    pendingPcm.write(bytes, 0, bytes.length);
                } else if (!pendingOverflowLogged) {
                    pendingOverflowLogged = true;
                    AppLog.w(TAG, "云端 ASR 启动过慢，启动期 PCM 缓冲已溢出，超出部分丢弃");
                }
                return;
            }
            if (state != ACTIVE) {
                return;
            }
        }
        TXRealtimeASR current;
        synchronized (this) {
            current = engine;
        }
        if (current != null) {
            current.feedPcmData(toBytes(samples), 16000, 1);
        }
    }

    /** 停止云端会话；SDK 可能继续推送迟到结果，是否接纳由业务层状态门禁决定。 */
    public synchronized void stop() {
        if (state != STARTING && state != ACTIVE) {
            return;
        }
        state = STOPPING;
        pendingPcm.reset();
        TXRealtimeASR current = engine;
        if (current != null) {
            try {
                current.stopRealtimeASR();
            } catch (RuntimeException exception) {
                AppLog.w(TAG, "stopRealtimeASR failed", exception);
                enterIdle();
            }
        }
    }

    /**
     * 释放当前 SDK 实例并回到 IDLE。对象本身可复用，下次 start 会懒创建新实例；
     * 因此也可用于隔离停止超时的旧会话。
     */
    public synchronized void destroy() {
        TXRealtimeASR current = engine;
        engine = null;
        state = IDLE;
        startPending = false;
        pendingPcm.reset();
        if (current != null) {
            try {
                current.removeListener(sdkListener);
                current.destroy();
            } catch (RuntimeException exception) {
                AppLog.w(TAG, "destroy failed", exception);
            }
        }
    }

    /** 懒加载 SDK 实例；native 库加载失败会缓存失败结果，不再反复尝试。 */
    private synchronized boolean ensureEngine() {
        if (engine != null) {
            return true;
        }
        if (engineLoadFailed) {
            return false;
        }
        try {
            engine = new TXRealtimeASR();
            engine.addListener(sdkListener);
            return true;
        } catch (Throwable throwable) {
            // UnsatisfiedLinkError 等 native 加载失败也按不可用处理。
            engineLoadFailed = true;
            AppLog.e(TAG, "TXRealtimeASR init failed", throwable);
            return false;
        }
    }

    /** 会话终结（stopped/error）回到 IDLE；若有挂起的启动请求则自动重开。 */
    private void enterIdle() {
        boolean restart;
        synchronized (this) {
            state = IDLE;
            pendingPcm.reset();
            restart = startPending;
            startPending = false;
        }
        if (restart) {
            start();
        }
    }

    private final TXRealtimeASR.Listener sdkListener = new TXRealtimeASR.Listener() {
        @Override
        public void onRealtimeASRStarted(String voiceId) {
            byte[] buffered;
            boolean feed;
            synchronized (VoiceAiAsrClient.this) {
                feed = state == STARTING;
                if (feed) {
                    state = ACTIVE;
                }
                buffered = pendingPcm.toByteArray();
                pendingPcm.reset();
            }
            if (feed && buffered.length > 0) {
                TXRealtimeASR current = engine;
                if (current != null) {
                    current.feedPcmData(buffered, 16000, 1);
                }
            }
            AppLog.i(TAG, "云端 ASR 已启动");
            post(cb -> cb.onStarted(voiceId));
        }

        @Override
        public void onReceiveRealtimeASRMessage(TXRealtimeASR.Message message) {
            String segmentId = message.segmentId == null ? "" : message.segmentId;
            String text = message.sourceText == null ? "" : message.sourceText;
            boolean completed = message.isCompleted;
            int speakerId = message.speakerId;
            post(cb -> cb.onMessage(segmentId, text, completed, speakerId));
        }

        @Override
        public void onRealtimeASRStopped() {
            enterIdle();
            post(Callback::onStopped);
        }

        @Override
        public void onRealtimeASRError(int errorCode, String errorMsg) {
            String msg = errorMsg == null ? "" : errorMsg;
            enterIdle();
            post(cb -> cb.onError(errorCode, msg));
        }
    };

    private interface CallbackAction {
        void run(Callback callback);
    }

    private void post(CallbackAction action) {
        main.post(() -> {
            Callback current = callback;
            if (current != null) {
                action.run(current);
            }
        });
    }

    private void notifyError(int code, String message) {
        post(cb -> cb.onError(code, message));
    }

    /** short[] → s16le 字节（SDK feedPcmData 入参格式）。 */
    private static byte[] toBytes(short[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asShortBuffer().put(samples);
        return buffer.array();
    }
}
