package com.recorder.client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.recorder.business.AppRecorderSession;
import com.recorder.client.asr.RealtimeAudioPipeline;
import com.recorder.client.asr.RealtimeSpeakerSegment;
import com.recorder.client.asr.RealtimeSpeakerTranscript;
import com.recorder.client.transcription.CloudTranscriptionController;
import com.recorder.client.offline.OfflineSyncController;
import com.recorder.client.offline.RecordingHistoryRepository;
import com.recorder.client.voiceai.VoiceAiAsrClient;
import com.recorder.transport.ReliableTransport;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 录音会话中枢（单例）。
 *
 * <p>连接生命周期（BLE / 传输层）由 MainActivity 管理；本类负责把
 * {@link AppRecorderSession}、{@link RealtimeAudioPipeline} 与腾讯云 VoiceAI
 * 桥接起来，持有录音过程中的实时分段与状态，并向"当前前台页面"
 * （主页或实时录音页）分发 UI 事件。
 *
 * <p>实时录音页独立于主页后，录音状态保存在这里，页面切换不丢失。
 */
public final class RecordingManager {

    private static final String TAG = "RecordingManager";

    /** 前台页面（实时录音页）订阅录音/ASR 事件。 */
    public interface UiListener {
        void onRecordingStateChanged(AppRecorderSession.MainState state);

        /** VoiceAI 说话人分离的结构化实时分段。 */
        void onAsrSegmentsChanged(List<RealtimeSpeakerSegment> segments);

        /** 云端识别或 PCM 解码失败；设备录音本身仍继续。 */
        void onRealtimeAsrError(String message);

        /** 解码后 PCM 的平滑响度，范围为 0..1；在主线程回调。 */
        void onAudioLevelChanged(float level);

        void onRecordingStopped(long frameCount, int receivedFrameCount);

        /** 协议错误或连接断开：实时录音页应退出。 */
        void onSessionGone(String reason);
    }

    public interface DeviceStatusListener {
        void onDeviceStatusChanged(AppRecorderSession.DeviceSnapshot snapshot);
    }

    public interface ReverseControlListener {
        void onRecordingAttached(long recordingId);
    }

    /** 协议错误出口（由 MainActivity 注册，执行一刀切断连）。 */
    public interface ConnectionErrorHandler {
        void onProtocolError(String reason);
    }

    private static final RecordingManager INSTANCE = new RecordingManager();

    public static RecordingManager get() {
        return INSTANCE;
    }

    private RecordingManager() {
    }

    /** STOPPING 内等待 VoiceAI 终止的上限；超时后隔离旧 SDK 实例。 */
    private static final long VOICE_AI_STOP_TIMEOUT_MS = 3_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private RealtimeAudioPipeline audioPipeline;
    private volatile VoiceAiAsrClient voiceAi;
    /** VoiceAI 13.5 说话人分段状态。 */
    private final RealtimeSpeakerTranscript speakerTranscript = new RealtimeSpeakerTranscript();
    private RecordingHistoryRepository historyRepository;
    private CloudTranscriptionController cloudTranscription;
    private OfflineSyncController offlineSync;
    private AppRecorderSession session;
    private ConnectionErrorHandler errorHandler;
    
    private volatile UiListener uiListener;
    
    private final CopyOnWriteArrayList<DeviceStatusListener> deviceStatusListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ReverseControlListener> reverseControlListeners =
            new CopyOnWriteArrayList<>();
    private volatile AppRecorderSession.DeviceSnapshot deviceSnapshot;
    
    /**
     * 合并 VoiceAI 高频通知；执行时读取最新快照，避免中间结果触发无效重绘。
     */
    private final Runnable notifyLatestTranscript = () -> {
        UiListener listener = uiListener;
        if (listener != null) {
            listener.onAsrSegmentsChanged(speakerTranscript.snapshot());
        }
    };
    /** 当前 PCM 音量的平滑值；音频线程写入，主线程读取。 */
    private volatile float latestAudioLevel;
    /** 有待处理的音量回调时为 true，用于合并高频音频帧。 */
    private volatile boolean audioLevelNotificationPending;
    private final Runnable notifyLatestAudioLevel = () -> {
        audioLevelNotificationPending = false;
        UiListener listener = uiListener;
        if (listener != null) {
            listener.onAudioLevelChanged(latestAudioLevel);
        }
    };

    /** 当前会话实时识别错误，供页面重建后恢复提示。 */
    private volatile String realtimeAsrError;
    /** 只在 RECEIVING_LIVE_AUDIO 期间为 true；STOPPING 后所有迟到结果均丢弃。 */
    private volatile boolean acceptRealtimeAsrResults;

    /** STOPPING_RECORDING 的双路完成屏障。 */
    private final Object stopPhaseLock = new Object();
    private boolean stopPhaseActive;
    private boolean deviceStopConfirmed;
    private boolean voiceAiStopConfirmed;
    private boolean stopCompletionPosted;

    private volatile boolean audioPipelineReady;
    private volatile long recordingStartedAtMs;
    /** 当前录音时间轴起点；点击停止后立即清零。 */
    private volatile long asrTimelineStartedAtMs;
    private int frameCountForLog;
    private volatile long pendingStopFrameCount;
    private volatile int pendingStopReceivedFrameCount;

    // ==================== 生命周期 ====================

    public synchronized void init(Context context) {
        initBackground(context);
        if (audioPipeline != null) {
            return;
        }
        if (offlineSync == null) {
            offlineSync = new OfflineSyncController(context.getApplicationContext(),
                    historyRepository);
        }
        audioPipeline = new RealtimeAudioPipeline(main);
        audioPipeline.setCallback(audioPipelineCallback);
        audioPipeline.init();
        // VoiceAI 外部采集模式：复用设备 Opus 解码后的 16kHz mono PCM。
        VoiceAiAsrClient cloud = new VoiceAiAsrClient(main);
        cloud.setCallback(voiceAiCallback);
        voiceAi = cloud;
        audioPipeline.setPcmSink(samples -> {
            onAudioSamples(samples);
            VoiceAiAsrClient v = voiceAi;
            if (v != null) {
                v.feedPcm16k(samples);
            }
        });
    }

    /**
     * 初始化与页面/蓝牙无关的持久化任务基础设施。WorkManager 在冷进程中可单独调用，
     * 不会创建实时音频管线、VoiceAI 实例或 BLE 同步控制器。
     */
    public synchronized void initBackground(Context context) {
        if (historyRepository == null) {
            historyRepository = new RecordingHistoryRepository(context.getApplicationContext());
        }
        if (cloudTranscription == null) {
            cloudTranscription = new CloudTranscriptionController(
                    context.getApplicationContext(), historyRepository);
        }
    }

    public void release() {
        VoiceAiAsrClient v = voiceAi;
        voiceAi = null;
        if (v != null) {
            v.destroy();
        }
        cancelStopPhase();
        if (audioPipeline != null) {
            audioPipeline.release();
            audioPipeline = null;
        }
        audioPipelineReady = false;
    }

    public void setConnectionErrorHandler(ConnectionErrorHandler handler) {
        errorHandler = handler;
    }

    // ==================== 会话挂载 ====================

    /**
     * 为连接创建带 owner 标识的业务监听器。旧连接的迟到回调不会进入当前
     * RecordingManager / OfflineSyncController，从而不能影响新连接。
     */
    public synchronized AppRecorderSession createAndAttachSession(ReliableTransport transport) {
        SessionListener listener = new SessionListener();
        AppRecorderSession created = new AppRecorderSession(transport, listener);
        listener.bind(created);
        session = created;
        if (offlineSync != null) {
            // Demo 约定 file_name 即录音 UUID，且只存在一台录音设备；BLE 地址
            // 不参与历史记录或断点缓存的身份判断。
            offlineSync.attachSession(created);
        }
        return created;
    }

    public synchronized void detachSession(String reason) {
        AppRecorderSession old = session;
        session = null;
        if (offlineSync != null && old != null) {
            offlineSync.detachSession(old);
        }
        stopCloudTrackQuietly();
        RealtimeAudioPipeline pipeline = audioPipeline;
        if (pipeline != null) {
            pipeline.stopSession();
        }
        recordingStartedAtMs = 0;
        asrTimelineStartedAtMs = 0;
        deviceSnapshot = null;
        notifyDeviceStatus(null);
        UiListener l = uiListener;
        if (l != null) {
            main.post(() -> l.onSessionGone(reason));
        }
    }

    public synchronized AppRecorderSession session() {
        return session;
    }

    public OfflineSyncController offlineSync() {
        return offlineSync;
    }

    public RecordingHistoryRepository historyRepository() {
        return historyRepository;
    }

    public CloudTranscriptionController cloudTranscription() {
        return cloudTranscription;
    }

    /** 在传输层 READY 后拉取首次列表；该 Request 同时锁定首页录音入口。 */
    public void refreshOfflineHistory() {
        OfflineSyncController controller = offlineSync;
        if (controller != null) {
            controller.refreshFromDevice();
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 是否允许发起 recording.start。
     *
     * <p>必须同时满足：连接会话 READY 且 VoiceAI 所需的实时音频管线已就绪。
     */
    public boolean canStartRecording() {
        AppRecorderSession s;
        synchronized (this) {
            s = session;
        }
        return audioPipelineReady && s != null && s.canStartRecording();
    }

    public long recordingStartedAtMs() {
        return recordingStartedAtMs;
    }

    // ==================== 上层动作 ====================

    public void startRecording() {
        AppRecorderSession s;
        synchronized (this) {
            s = session;
        }
        if (s == null || !s.canStartRecording()) {
            return;
        }
        // RealtimeRecordingActivity 会在 onCreate 发起录音、到 onResume 才绑定 UI
        // listener。若等 recording.start 成功 Response 才清空，onResume 会先拿到
        // 上一场录音的快照，并持续显示到设备回包。接受本次启动意图时立即清空，
        // 使页面首次订阅就获得空态；成功回包处仍保留一次防御性 reset。
        speakerTranscript.reset();
        realtimeAsrError = null;
        asrTimelineStartedAtMs = 0L;
        resetAudioLevel();
        notifyTranscript();
        s.startRecording();
    }

    public void stopRecording() {
        AppRecorderSession s;
        synchronized (this) {
            s = session;
        }
        if (s != null) {
            s.stopRecording();
        }
    }

    // ==================== UI 订阅 ====================

    public void addDeviceStatusListener(DeviceStatusListener listener) {
        if (listener == null) {
            return;
        }
        deviceStatusListeners.addIfAbsent(listener);
        listener.onDeviceStatusChanged(deviceSnapshot);
    }

    public void removeDeviceStatusListener(DeviceStatusListener listener) {
        deviceStatusListeners.remove(listener);
    }

    public void addReverseControlListener(ReverseControlListener listener) {
        if (listener != null) {
            reverseControlListeners.addIfAbsent(listener);
        }
    }

    public void removeReverseControlListener(ReverseControlListener listener) {
        reverseControlListeners.remove(listener);
    }

    public AppRecorderSession.DeviceSnapshot deviceSnapshot() {
        return deviceSnapshot;
    }

    private void notifyDeviceStatus(AppRecorderSession.DeviceSnapshot snapshot) {
        main.post(() -> {
            for (DeviceStatusListener listener : deviceStatusListeners) {
                listener.onDeviceStatusChanged(snapshot);
            }
        });
    }

    private void notifyRecordingAttached(long recordingId) {
        main.post(() -> {
            for (ReverseControlListener listener : reverseControlListeners) {
                listener.onRecordingAttached(recordingId);
            }
        });
    }

    public void setUiListener(UiListener listener) {
        uiListener = listener;
        if (listener != null) {
            // 立即同步当前状态与文本，避免页面打开时一片空白
            AppRecorderSession.MainState state;
            synchronized (this) {
                state = session != null ? session.state() : null;
            }
            if (state != null) {
                listener.onRecordingStateChanged(state);
            }
            listener.onAsrSegmentsChanged(speakerTranscript.snapshot());
            listener.onAudioLevelChanged(latestAudioLevel);
            String error = realtimeAsrError;
            if (error != null) {
                listener.onRealtimeAsrError(error);
            }
        }
    }

    public void clearUiListener(UiListener listener) {
        if (uiListener == listener) {
            uiListener = null;
        }
    }

    /** 音频工作线程计算 RMS，并采用快起慢落平滑，保留说话节奏而避免柱形抖动。 */
    private void onAudioSamples(short[] samples) {
        if (!acceptRealtimeAsrResults || samples == null || samples.length == 0) {
            return;
        }
        double squareSum = 0d;
        for (short sample : samples) {
            float normalized = sample / 32_768f;
            squareSum += normalized * normalized;
        }
        float rms = (float) Math.sqrt(squareSum / samples.length);
        float target = Math.max(0f, Math.min(1f, (rms - 0.008f) / 0.16f));
        // 开平方扩大正常说话时的中低响度区间，避免视觉长期贴近基线。
        target = (float) Math.sqrt(target);
        float previous = latestAudioLevel;
        float smoothing = target > previous ? 0.45f : 0.12f;
        latestAudioLevel = previous + (target - previous) * smoothing;
        if (!audioLevelNotificationPending && uiListener != null) {
            audioLevelNotificationPending = true;
            main.post(notifyLatestAudioLevel);
        }
    }

    private void resetAudioLevel() {
        latestAudioLevel = 0f;
        main.removeCallbacks(notifyLatestAudioLevel);
        audioLevelNotificationPending = false;
        if (uiListener != null) {
            audioLevelNotificationPending = true;
            main.post(notifyLatestAudioLevel);
        }
    }

    private void notifyState(AppRecorderSession.MainState state) {
        UiListener l = uiListener;
        if (l != null) {
            main.post(() -> l.onRecordingStateChanged(state));
        }
    }

    /** 推送最新 VoiceAI 分段；同一主线程周期内的高频更新只渲染最后一版。 */
    private void notifyTranscript() {
        main.removeCallbacks(notifyLatestTranscript);
        main.post(notifyLatestTranscript);
    }

    private void notifyRealtimeAsrError(String message) {
        realtimeAsrError = message;
        UiListener listener = uiListener;
        if (listener != null) {
            main.post(() -> {
                UiListener latest = uiListener;
                if (latest != null) {
                    latest.onRealtimeAsrError(message);
                }
            });
        }
    }

    // ==================== 业务层回调（transport-worker 线程） ====================

    /** 每个 AppRecorderSession 独占一个 listener，避免旧连接的回调串线。 */
    private final class SessionListener implements AppRecorderSession.Listener {
                private volatile AppRecorderSession owner;

                void bind(AppRecorderSession session) {
                    owner = session;
                }

                private boolean isActive() {
                    AppRecorderSession current = owner;
                    synchronized (RecordingManager.this) {
                        return current != null && session == current;
                    }
                }

                @Override
                public void onLog(String message) {
                    AppLog.i("BusinessSession", message);
                }

                @Override
                public void onStateChanged(AppRecorderSession.MainState state) {
                    if (!isActive()) {
                        return;
                    }
                    if (state == AppRecorderSession.MainState.STOPPING_RECORDING) {
                        beginStopPhase();
                    }
                    OfflineSyncController controller = offlineSync;
                    if (controller != null) {
                        controller.onMainStateChanged(owner, state);
                    }
                    notifyState(state);
                }

                @Override
                public void onRecordingStarted(long recordingId, long frameDurationMs,
                                               long sampleRateHz, long channelCount,
                                               byte[] codecConfig) {
                    if (!isActive()) {
                        return;
                    }
                    speakerTranscript.reset();
                    realtimeAsrError = null;
                    resetStopPhaseForNewRecording();
                    acceptRealtimeAsrResults = true;
                    frameCountForLog = 0;
                    recordingStartedAtMs = System.currentTimeMillis();
                    asrTimelineStartedAtMs = recordingStartedAtMs;
                    if (audioPipeline != null && audioPipelineReady) {
                        audioPipeline.beginSession(
                                codecConfig == null ? null : codecConfig.clone(),
                                frameDurationMs, sampleRateHz, channelCount);
                    } else {
                        AppLog.w(TAG, "实时音频管线未就绪，忽略本次识别会话启动");
                    }
                    VoiceAiAsrClient v = voiceAi;
                    if (v != null) {
                        v.start();
                    }
                    notifyTranscript();
                }

                @Override
                public void onAudioFrame(byte[] opusPacket) {
                    if (!isActive() || !acceptRealtimeAsrResults) {
                        return;
                    }
                    frameCountForLog++;
                    if (frameCountForLog <= 3) {
                        AppLog.d(TAG, "音频帧#" + frameCountForLog + "："
                                + opusPacket.length + "B " + hexPrefix(opusPacket, 12));
                    }
                    if (audioPipeline != null) {
                        audioPipeline.feed(opusPacket);
                    }
                }

                @Override
                public void onRecordingStopped(long frameCount, int receivedFrameCount) {
                    if (!isActive()) {
                        return;
                    }
                    recordingStartedAtMs = 0;
                    pendingStopFrameCount = frameCount;
                    pendingStopReceivedFrameCount = receivedFrameCount;
                    // stop Response 只完成设备侧分支；VoiceAI 分支可能已先完成。
                    beginStopPhase();
                    markDeviceStopConfirmed();
                }

                @Override
                public void onDeviceStatus(AppRecorderSession.DeviceSnapshot snapshot) {
                    if (!isActive()) {
                        return;
                    }
                    deviceSnapshot = snapshot;
                    notifyDeviceStatus(snapshot);
                }

                @Override
                public void onRecordingAttached(long recordingId, long currentDurationMs) {
                    if (isActive()) {
                        long now = System.currentTimeMillis();
                        recordingStartedAtMs = Math.max(1L, now - currentDurationMs);
                        asrTimelineStartedAtMs = recordingStartedAtMs;
                        notifyRecordingAttached(recordingId);
                    }
                }

                @Override
                public void onProtocolError(String reason) {
                    if (!isActive()) {
                        return;
                    }
                    recordingStartedAtMs = 0;
                    if (audioPipeline != null) {
                        audioPipeline.stopSession();
                    }
                    stopCloudTrackQuietly();
                    ConnectionErrorHandler h = errorHandler;
                    if (h != null) {
                        main.post(() -> h.onProtocolError(reason));
                    }
                }

                @Override
                public void onFileStateChanged(AppRecorderSession.FileState state) {
                    AppRecorderSession current = owner;
                    if (!isActive()) {
                        return;
                    }
                    OfflineSyncController controller = offlineSync;
                    if (controller != null) {
                        controller.onFileStateChanged(current, state);
                    }
                }

                @Override
                public void onFileList(java.util.List<AppRecorderSession.RemoteFile> files) {
                    AppRecorderSession current = owner;
                    if (!isActive()) {
                        return;
                    }
                    OfflineSyncController controller = offlineSync;
                    if (controller != null) {
                        controller.onFileList(current, files);
                    }
                }

                @Override
                public void onFileDownloadStarted(AppRecorderSession.DownloadInfo info) {
                    AppRecorderSession current = owner;
                    if (!isActive()) {
                        return;
                    }
                    OfflineSyncController controller = offlineSync;
                    if (controller != null) {
                        controller.onFileDownloadStarted(current, info);
                    }
                }

                @Override
                public void onFileChunk(AppRecorderSession.DownloadInfo info, long offset,
                                        boolean isEnd, byte[] payload) {
                    AppRecorderSession current = owner;
                    if (!isActive()) {
                        return;
                    }
                    OfflineSyncController controller = offlineSync;
                    if (controller != null) {
                        controller.onFileChunk(current, info, offset, isEnd, payload);
                    }
                }

                @Override
                public void onFileReadyForVerification(AppRecorderSession.DownloadInfo info) {
                    AppRecorderSession current = owner;
                    if (!isActive()) {
                        return;
                    }
                    OfflineSyncController controller = offlineSync;
                    if (controller != null) {
                        controller.onFileReadyForVerification(current, info);
                    }
                }

                @Override
                public void onFileDownloadPaused(AppRecorderSession.DownloadInfo info,
                                                  long nextOffset) {
                    AppRecorderSession current = owner;
                    if (!isActive()) {
                        return;
                    }
                    OfflineSyncController controller = offlineSync;
                    if (controller != null) {
                        controller.onFileDownloadPaused(current, info, nextOffset);
                    }
                }

                @Override
                public void onFileDownloadCompleted(String fileName) {
                    AppRecorderSession current = owner;
                    if (!isActive()) {
                        return;
                    }
                    OfflineSyncController controller = offlineSync;
                    if (controller != null) {
                        controller.onFileDownloadCompleted(current, fileName);
                    }
                }
            }

    // ==================== 实时音频管线回调（主线程） ====================

    private final RealtimeAudioPipeline.Callback audioPipelineCallback =
            new RealtimeAudioPipeline.Callback() {
                @Override
                public void onPipelineReady() {
                    audioPipelineReady = true;
                    AppLog.i(TAG, "VoiceAI 实时音频管线就绪");
                }

                @Override
                public void onPipelineError(String message) {
                    AppLog.e(TAG, message);
                    if (acceptRealtimeAsrResults) {
                        notifyRealtimeAsrError(message);
                    }
                }

                @Override
                public void onNotice(String message) {
                    AppLog.i(TAG, message);
                }
            };

    // ==================== 云端 ASR 轨（VoiceAI SDK） ====================

    private final VoiceAiAsrClient.Callback voiceAiCallback = new VoiceAiAsrClient.Callback() {
        @Override
        public void onStarted(String voiceId) {
            // 启动日志已由 VoiceAiAsrClient 输出，无需重复。
        }

        @Override
        public void onMessage(String segmentId, String text, boolean isCompleted, int speakerId) {
            if (!acceptRealtimeAsrResults) {
                return;
            }
            long startedAt = asrTimelineStartedAtMs;
            long approximateStartMs = startedAt <= 0L ? 0L
                    : Math.max(0L, System.currentTimeMillis() - startedAt);
            speakerTranscript.onMessage(segmentId, text, isCompleted, speakerId,
                    approximateStartMs);
            notifyTranscript();
        }

        @Override
        public void onStopped() {
            markVoiceAiStopConfirmed();
        }

        @Override
        public void onError(int errorCode, String errorMsg) {
            if (isStopPhaseActive() || !acceptRealtimeAsrResults) {
                AppLog.w(TAG, "VoiceAI 在 STOPPING 阶段终止(" + errorCode + ")：" + errorMsg);
                markVoiceAiStopConfirmed();
                return;
            }
            String message = "实时识别失败(" + errorCode + ")：" + errorMsg;
            AppLog.e(TAG, message + "；设备录音仍继续");
            notifyRealtimeAsrError(message);
        }
    };

    // ==================== STOPPING_RECORDING 双路屏障 ====================

    /** 点击停止后立即冻结音频/文本，同时并行等待设备和 VoiceAI 两路终止。 */
    private void beginStopPhase() {
        synchronized (stopPhaseLock) {
            if (stopPhaseActive) {
                return;
            }
            stopPhaseActive = true;
            deviceStopConfirmed = false;
            voiceAiStopConfirmed = false;
            stopCompletionPosted = false;
        }
        acceptRealtimeAsrResults = false;
        recordingStartedAtMs = 0L;
        asrTimelineStartedAtMs = 0L;
        resetAudioLevel();
        // 冻结点击前最后一版 UI；已经排队但尚未渲染的通知不应在 STOPPING 后落屏。
        main.removeCallbacks(notifyLatestTranscript);
        RealtimeAudioPipeline pipeline = audioPipeline;
        if (pipeline != null) {
            pipeline.stopSession();
        }

        VoiceAiAsrClient v = voiceAi;
        if (v != null && v.isSessionActive()) {
            v.stop();
            main.removeCallbacks(voiceAiStopTimeout);
            main.postDelayed(voiceAiStopTimeout, VOICE_AI_STOP_TIMEOUT_MS);
            return;
        }
        markVoiceAiStopConfirmed();
    }

    private void markDeviceStopConfirmed() {
        synchronized (stopPhaseLock) {
            if (!stopPhaseActive) {
                return;
            }
            deviceStopConfirmed = true;
            maybePostStopCompletionLocked();
        }
    }

    private void markVoiceAiStopConfirmed() {
        synchronized (stopPhaseLock) {
            if (!stopPhaseActive) {
                return;
            }
            voiceAiStopConfirmed = true;
            maybePostStopCompletionLocked();
        }
        main.removeCallbacks(voiceAiStopTimeout);
    }

    private void maybePostStopCompletionLocked() {
        if (!deviceStopConfirmed || !voiceAiStopConfirmed || stopCompletionPosted) {
            return;
        }
        stopCompletionPosted = true;
        main.post(this::completeStopPhaseOnMain);
    }

    /** 两路都结束后，才让纯业务状态机从 STOPPING_RECORDING 恢复 READY。 */
    private void completeStopPhaseOnMain() {
        synchronized (stopPhaseLock) {
            if (!stopPhaseActive || !deviceStopConfirmed || !voiceAiStopConfirmed) {
                return;
            }
        }
        AppRecorderSession current;
        synchronized (this) {
            current = session;
        }
        if (current == null || !current.completeRecordingStop()) {
            cancelStopPhase();
            return;
        }
        synchronized (stopPhaseLock) {
            stopPhaseActive = false;
            deviceStopConfirmed = false;
            voiceAiStopConfirmed = false;
            stopCompletionPosted = false;
        }
        main.removeCallbacks(voiceAiStopTimeout);
        finishStoppedRecording(current.state() == AppRecorderSession.MainState.READY);
    }

    private boolean isStopPhaseActive() {
        synchronized (stopPhaseLock) {
            return stopPhaseActive;
        }
    }

    private void resetStopPhaseForNewRecording() {
        cancelStopPhase();
        synchronized (stopPhaseLock) {
            deviceStopConfirmed = false;
            voiceAiStopConfirmed = false;
            stopCompletionPosted = false;
        }
    }

    private void cancelStopPhase() {
        synchronized (stopPhaseLock) {
            stopPhaseActive = false;
            deviceStopConfirmed = false;
            voiceAiStopConfirmed = false;
            stopCompletionPosted = false;
        }
        main.removeCallbacks(voiceAiStopTimeout);
    }

    private final Runnable voiceAiStopTimeout = () -> {
        synchronized (stopPhaseLock) {
            if (!stopPhaseActive || voiceAiStopConfirmed) {
                return;
            }
        }
        AppLog.w(TAG, "VoiceAI 停止超时，隔离旧 SDK 实例并继续 STOPPING 流程");
        VoiceAiAsrClient v = voiceAi;
        if (v != null) {
            // destroy 后同一封装对象可在下次 start 时懒创建全新 SDK 实例，避免旧
            // 会话永久卡在 STOPPING 并吞掉下一次录音的开头 PCM。
            v.destroy();
        }
        markVoiceAiStopConfirmed();
    };

    /** 断连/协议错误/会话拆除时静默停掉 VoiceAI，不推进正常停止屏障。 */
    private void stopCloudTrackQuietly() {
        acceptRealtimeAsrResults = false;
        resetAudioLevel();
        cancelStopPhase();
        VoiceAiAsrClient v = voiceAi;
        if (v != null && v.isSessionActive()) {
            v.stop();
        }
    }

    /** STOPPING_RECORDING 完整结束后按需刷新文件列表并退出实时页。 */
    private void finishStoppedRecording(boolean shouldRefreshFiles) {
        OfflineSyncController controller = offlineSync;
        if (shouldRefreshFiles && controller != null) {
            controller.refreshFromDevice();
        }
        UiListener l = uiListener;
        if (l != null) {
            l.onRecordingStopped(pendingStopFrameCount, pendingStopReceivedFrameCount);
        }
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
        return sb.toString();
    }
}
