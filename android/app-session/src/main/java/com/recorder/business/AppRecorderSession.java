package com.recorder.business;

import com.recorder.business.proto.AudioCodec;
import com.recorder.business.proto.AudioFrame;
import com.recorder.business.proto.DeviceRecordingState;
import com.recorder.business.proto.DeviceStatusRequest;
import com.recorder.business.proto.DeviceStatusResult;
import com.recorder.business.proto.FileChunk;
import com.recorder.business.proto.FileDownloadCompleteRequest;
import com.recorder.business.proto.FileDownloadCompleteResult;
import com.recorder.business.proto.FileDownloadPauseRequest;
import com.recorder.business.proto.FileDownloadPauseResult;
import com.recorder.business.proto.FileDownloadStartRequest;
import com.recorder.business.proto.FileDownloadStartResult;
import com.recorder.business.proto.FileListRequest;
import com.recorder.business.proto.FileListResult;
import com.recorder.business.proto.FileMetadata;
import com.recorder.business.proto.PacketType;
import com.recorder.business.proto.RecorderPacket;
import com.recorder.business.proto.RecordingAttachRequest;
import com.recorder.business.proto.RecordingAttachResult;
import com.recorder.business.proto.RecordingAttachResultBody;
import com.recorder.business.proto.RecordingStartedEvent;
import com.recorder.business.proto.RecordingStartRequest;
import com.recorder.business.proto.RecordingStartResult;
import com.recorder.business.proto.RecordingStoppedEvent;
import com.recorder.business.proto.RecordingStopRequest;
import com.recorder.business.proto.RecordingStopResult;
import com.recorder.business.proto.RequestHeader;
import com.recorder.business.proto.ResponseHeader;
import com.recorder.business.proto.StatusCode;
import com.recorder.request.RequestManager;
import com.recorder.transport.ReliableTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * App 侧录音笔业务会话（实时录音链路）。
 *
 * <p>实现录音主状态机：
 * {@code DISCONNECTED → READY → STARTING_RECORDING → RECEIVING_LIVE_AUDIO
 * → STOPPING_RECORDING → READY}，
 * 以及 §2「一刀切」异常处理：任何协议错误统一交给 RequestManager 清空请求并
 * 主动停止传输，再通过 {@link Listener#onProtocolError(String)} 通知上层。
 *
 * <p>Request 纪律（draft v6 §1.1）：
 * <ul>
 *   <li>任意时刻最多一个 pending Request；</li>
 *   <li>id 由 App 生成，一次连接内从 1 单调递增、不复用；</li>
 *   <li>业务超时默认 {@value #DEFAULT_REQUEST_TIMEOUT_MS} ms，
 *       取值依据见 04-业务层接手指南 §3 硬约束 4（必须大于传输层最坏送达耗时）。</li>
 * </ul>
 *
 * <p>线程模型：所有 public 方法内部 synchronized；回调可能在 transport-worker
 * 线程或超时线程上触发，上层如需刷新 UI 请自行切线程。
 * 本类不做任何阻塞 IO。
 */
public final class AppRecorderSession {

    /**
     * Request 超时（兜底机制）。取 60 秒，为同一 GATT 内的 ACK 软停滞恢复
     * 留出足够时间；物理 GATT 断开仍会立即通过 onLinkDown 结束等待。
     */
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 60_000L;

    /** 录音主状态（App设计.md §1.1）。 */
    public enum MainState {
        DISCONNECTED,
        READY,
        STARTING_RECORDING,
        RECEIVING_LIVE_AUDIO,
        STOPPING_RECORDING
    }

    /**
     * 离线文件同步状态。它与 {@link MainState} 正交：下载进行中时用户仍可
     * 发起实时录音，会话会先完成 pause 的协议握手再发送 recording.start。
     */
    public enum FileState {
        FILE_IDLE,
        FILE_LISTING,
        FILE_STARTING,
        FILE_DOWNLOADING,
        FILE_PAUSING,
        FILE_VERIFYING,
        FILE_COMPLETING
    }

    /** 设备 file.list 中的一条不可变录音元数据。 */
    public static final class RemoteFile {
        public final String fileName;
        public final long createdTimeSec;
        public final long durationMs;

        public RemoteFile(String fileName, long createdTimeSec, long durationMs) {
            this.fileName = fileName;
            this.createdTimeSec = createdTimeSec;
            this.durationMs = durationMs;
        }
    }

    /** 最近一次 device.status 的不可变设备快照。 */
    public static final class DeviceSnapshot {
        public final int batteryPercentage;
        public final long totalStorageBytes;
        public final long usedStorageBytes;
        public final DeviceRecordingState recordingState;
        public final long currentRecordingId;

        DeviceSnapshot(int batteryPercentage, long totalStorageBytes, long usedStorageBytes,
                       DeviceRecordingState recordingState, long currentRecordingId) {
            this.batteryPercentage = batteryPercentage;
            this.totalStorageBytes = totalStorageBytes;
            this.usedStorageBytes = usedStorageBytes;
            this.recordingState = recordingState;
            this.currentRecordingId = currentRecordingId;
        }
    }

    /** 当前 download.start 成功后确定的文件格式及校验信息。 */
    public static final class DownloadInfo {
        public final String fileName;
        public final long transferId;
        public final long startOffset;
        public final long fileSize;
        public final int chunkPayloadBytes;
        public final long crc32;
        public final long sampleRateHz;
        public final long channelCount;
        public final byte[] codecConfig;

        public DownloadInfo(String fileName, long transferId, long startOffset,
                            long fileSize, int chunkPayloadBytes, long crc32,
                            long sampleRateHz, long channelCount, byte[] codecConfig) {
            this.fileName = fileName;
            this.transferId = transferId;
            this.startOffset = startOffset;
            this.fileSize = fileSize;
            this.chunkPayloadBytes = chunkPayloadBytes;
            this.crc32 = crc32;
            this.sampleRateHz = sampleRateHz;
            this.channelCount = channelCount;
            this.codecConfig = codecConfig == null ? null : codecConfig.clone();
        }
    }

    public interface Listener {
        void onLog(String message);

        void onStateChanged(MainState state);

        /**
         * recording.start 成功：已取得 recording_id 与设备实际音频参数，即将收到首帧。
         *
         * @param codecConfig 设备编码器的 csd-0（OpusHead），用于初始化解码器；
         *                    设备未提供时为 null，解码器应按音频参数构造默认头。
         */
        void onRecordingStarted(long recordingId, long frameDurationMs,
                                long sampleRateHz, long channelCount, byte[] codecConfig);

        /** 实时音频帧（RECEIVING_LIVE_AUDIO 状态）。payload 为裸 Opus Packet。 */
        void onAudioFrame(byte[] opusPacket);

        /** recording.stop 成功或 stopped Event：设备已收尾，ASR 可以收尾。 */
        void onRecordingStopped(long frameCount, int receivedFrameCount);

        /** device.status 成功后发布设备电量、存储与录音状态快照。 */
        default void onDeviceStatus(DeviceSnapshot snapshot) {
        }

        /** 通过 status/Event 接入设备已存在的录音事务，供 UI 自动进入实时页。 */
        default void onRecordingAttached(long recordingId) {
        }

        /** 带设备侧当前事务时长的接入通知。 */
        default void onRecordingAttached(long recordingId, long currentDurationMs) {
            onRecordingAttached(recordingId);
        }

        /** 一刀切协议错误通知；RequestManager 已发起断链，上层不得重复 stop。 */
        void onProtocolError(String reason);

        /** 离线同步状态变化。默认实现保持已有实时录音调用方兼容。 */
        default void onFileStateChanged(FileState state) {
        }

        /** 收到并通过结构校验的设备文件列表。 */
        default void onFileList(List<RemoteFile> files) {
        }

        /** download.start 成功，后续 File Chunk 会属于该下载事务。 */
        default void onFileDownloadStarted(DownloadInfo info) {
        }

        /**
         * 收到一个连续的 File Chunk。payload 已复制，接收方应异步落盘；
         * 传输进度由 {@code offset + payload.length} 表示。
         */
        default void onFileChunk(DownloadInfo info, long offset, boolean isEnd,
                                 byte[] payload) {
        }

        /** 一个文件的所有字节已到达，应在本地执行大小/CRC/封装校验。 */
        default void onFileReadyForVerification(DownloadInfo info) {
        }

        /** pause Response 已到达；nextOffset 是本会话已连续观察到的偏移。 */
        default void onFileDownloadPaused(DownloadInfo info, long nextOffset) {
        }

        /** complete Response 已确认设备删除了源文件。 */
        default void onFileDownloadCompleted(String fileName) {
        }
    }

    private final Listener listener;
    private final RequestManager<PacketType, RecorderPacket> requestManager;

    private MainState state = MainState.DISCONNECTED;
    private boolean protocolErrorFired;
    private boolean protocolErrorNotified;

    // 当前录音事务 / 实时推送区间
    private long currentRecordingId;
    private long streamStartSequence;
    private long expectedSequence;
    private int receivedFrameCount;
    private boolean statusResolved;
    /** recording.start 已实际投递，尚未收到对应 Result。 */
    private boolean startRequestInFlight;
    /** start 在途期间由 started Event 观察到的同一竞争事务；其 attach 只用于幂等确认。 */
    private long concurrentStartRecordingId;
    /** 尚未提交的最新 attach 目标；不同于当前已经排队/在途的请求。 */
    private long pendingAttachRecordingId;
    /** 已提交到 RequestManager 的 attach 目标（可能仍在队列中，也可能已实际发出）。 */
    private long attachInFlightRecordingId;
    private long lastStoppedRecordingId;
    private DeviceSnapshot deviceSnapshot;
    /** 设备 stop Response/Event 已通过校验，正在等待客户端完成 STOPPING 阶段。 */
    private boolean recordingStopConfirmed;

    // 离线文件同步上下文。断点的持久化由 Android 调用方完成；此处只维护
    // 本连接内已经按顺序观察到的偏移，并严格校验设备流式报文。
    private FileState fileState = FileState.FILE_IDLE;
    private DownloadInfo activeDownload;
    private long expectedFileOffset;
    private int requestedMaxChunkPayloadBytes;
    private boolean queuedRecordingStart;
    private boolean verificationPendingAfterPause;
    private String pendingCompleteFileName;
    /** 最近一次合法 file.list 中的设备待同步文件名；complete 只能作用于其中一项。 */
    private final Set<String> listedRemoteFileNames = new HashSet<>();

    public AppRecorderSession(ReliableTransport transport, Listener listener) {
        this(transport, listener, DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /** @param requestTimeoutMs 测试可注入更小超时。 */
    public AppRecorderSession(ReliableTransport transport, Listener listener, long requestTimeoutMs) {
        this.listener = listener;
        this.requestManager = new RequestManager<>(transport,
                new RequestManager.Listener() {
                    @Override
                    public void onLog(String message) {
                        AppRecorderSession.this.listener.onLog(message);
                    }

                    @Override
                    public void onFatalError(String reason) {
                        onRequestManagerFatal(reason);
                    }
                }, requestTimeoutMs);
    }

    public synchronized MainState state() {
        return state;
    }

    public synchronized FileState fileState() {
        return fileState;
    }

    /** 当前下载事务；无活动事务时为 {@code null}。 */
    public synchronized DownloadInfo activeDownload() {
        return activeDownload;
    }

    public synchronized boolean canStartRecording() {
        // 首次 file.list 未完成前锁定入口，避免与列表 Request 竞争唯一 pending
        // Request。下载中允许开始录音，startRecording() 会先发 pause。
        return !protocolErrorFired && statusResolved && state == MainState.READY
                && fileState != FileState.FILE_LISTING && pendingAttachRecordingId == 0L
                && attachInFlightRecordingId == 0L;
    }

    public synchronized boolean canStopRecording() {
        return !protocolErrorFired && state == MainState.RECEIVING_LIVE_AUDIO;
    }

    public synchronized int receivedFrameCount() {
        return receivedFrameCount;
    }

    public synchronized long currentRecordingId() {
        return currentRecordingId;
    }

    // ==================== 上层动作 ====================

    public synchronized void requestDeviceStatus() {
        if (protocolErrorFired || state != MainState.READY || statusResolved) {
            return;
        }
        long timestamp = Math.max(1L, (System.currentTimeMillis() / 1000L) & 0xFFFFFFFFL);
        requestManager.submit(PacketType.DEVICE_STATUS_RESULT,
                id -> PacketCodec.encode(RecorderPacket.newBuilder()
                        .setType(PacketType.DEVICE_STATUS_REQUEST)
                        .setDeviceStatusRequest(DeviceStatusRequest.newBuilder()
                                .setHeader(RequestHeader.newBuilder().setId((int) id))
                                .setTimestamp((int) timestamp))
                        .build()),
                id -> listener.onLog("已投递 device.status (id=" + id + ")"),
                packet -> handleDeviceStatusResult(packet.getDeviceStatusResult()));
    }

    public synchronized void ensureAttach(long recordingId) {
        if (protocolErrorFired || recordingId == 0L) {
            return;
        }
        if (queuedRecordingStart) {
            queuedRecordingStart = false;
            listener.onLog("设备录音事务已抢先开始，取消尚未投递的 recording.start，"
                    + "改为接入 recordingId=" + recordingId);
        }
        if (state == MainState.RECEIVING_LIVE_AUDIO && currentRecordingId == recordingId) {
            return;
        }
        if (pendingAttachRecordingId == recordingId
                || attachInFlightRecordingId == recordingId) {
            return;
        }
        pendingAttachRecordingId = recordingId;
        boolean queueBehindActiveStart = startRequestInFlight
                && state == MainState.STARTING_RECORDING;
        if (attachInFlightRecordingId != 0L
                || (state != MainState.READY && !queueBehindActiveStart)) {
            return;
        }
        if (fileState == FileState.FILE_IDLE) {
            sendRecordingAttach(recordingId);
            return;
        }
        if (fileState == FileState.FILE_VERIFYING) {
            clearDownloadContext();
            setFileState(FileState.FILE_IDLE);
            sendRecordingAttach(recordingId);
            return;
        }
        if (fileState == FileState.FILE_DOWNLOADING) {
            sendPauseRequest();
        }
    }

    public synchronized void startRecording() {
        if (protocolErrorFired) {
            return;
        }
        if (!statusResolved || state != MainState.READY || pendingAttachRecordingId != 0L) {
            listener.onLog("当前状态不允许发起录音: " + state);
            return;
        }
        if (fileState == FileState.FILE_IDLE) {
            sendRecordingStart();
            return;
        }

        if (fileState == FileState.FILE_VERIFYING) {
            // 数据已全部到达，后续只是手机本地 CRC/Ogg 工作，不应继续占用
            // 蓝牙录音优先级。上层持有 DownloadInfo 并会在下一次 file.list
            // 对账时为校验成功的文件补发自动 complete。
            clearDownloadContext();
            setFileState(FileState.FILE_IDLE);
            listener.onLog("本地文件校验转入后台，立即启动实时录音");
            sendRecordingStart();
            return;
        }

        // 实时录音优先于离线下载。所有非空闲文件状态都先让当前唯一
        // Request 收尾；若已经在下载，则立刻发送 pause，Response 到达后
        // 再发送 recording.start，避免两条 Request 并发。
        queuedRecordingStart = true;
        if (fileState == FileState.FILE_DOWNLOADING) {
            sendPauseRequest();
            return;
        }
        listener.onLog("已排队 recording.start，等待离线文件状态收尾: " + fileState);
    }

    /** App 建链后及每次 STOPPING_RECORDING 完成后调用，拉取设备当前可同步的文件列表。 */
    public synchronized void requestFileList() {
        if (protocolErrorFired) {
            return;
        }
        if (state != MainState.READY || fileState != FileState.FILE_IDLE) {
            listener.onLog("当前状态不允许拉取文件列表: main=" + state
                    + ", file=" + fileState);
            return;
        }
        requestManager.submit(PacketType.REC_FILE_LIST_RESULT,
                id -> PacketCodec.encode(RecorderPacket.newBuilder()
                        .setType(PacketType.REC_FILE_LIST_REQUEST)
                        .setFileListRequest(FileListRequest.newBuilder()
                                .setHeader(RequestHeader.newBuilder().setId((int) id)))
                        .build()),
                id -> {
                    setFileState(FileState.FILE_LISTING);
                    listener.onLog("已投递 recording.file.list (id=" + id + ")");
                },
                packet -> handleFileListResult(packet.getFileListResult()));
    }

    /**
     * 从本地已持久化的连续 offset 开始下载一个文件。一个连接内只允许一个
     * 下载事务；maxChunkPayloadBytes 必须为正数并预留 Protobuf 开销。
     */
    public synchronized void startFileDownload(String fileName, long offset,
                                               int maxChunkPayloadBytes) {
        if (protocolErrorFired) {
            return;
        }
        if (state != MainState.READY || fileState != FileState.FILE_IDLE) {
            listener.onLog("当前状态不允许开始文件下载: main=" + state
                    + ", file=" + fileState);
            return;
        }
        if (!isSafeFileName(fileName) || offset < 0 || maxChunkPayloadBytes <= 0) {
            listener.onLog("下载参数非法: file=" + fileName + ", offset=" + offset);
            return;
        }
        requestManager.submit(PacketType.REC_FILE_DOWNLOAD_START_RESULT,
                id -> PacketCodec.encode(RecorderPacket.newBuilder()
                        .setType(PacketType.REC_FILE_DOWNLOAD_START_REQUEST)
                        .setFileDownloadStartRequest(FileDownloadStartRequest.newBuilder()
                                .setHeader(RequestHeader.newBuilder().setId((int) id))
                                .setFileName(fileName)
                                .setOffset(offset)
                                .setMaxChunkPayloadBytes(maxChunkPayloadBytes))
                        .build()),
                id -> {
                    requestedMaxChunkPayloadBytes = maxChunkPayloadBytes;
                    // activeDownload 在 Response 前只保存目标文件名/offset；正式格式信息
                    // 必须以设备 Response 为准。
                    activeDownload = new DownloadInfo(fileName, 0L, offset, 0L, 0,
                            0L, 0L, 0L, null);
                    expectedFileOffset = offset;
                    setFileState(FileState.FILE_STARTING);
                    listener.onLog("已投递 recording.file.download.start (id=" + id
                            + ", file=" + fileName + ", offset=" + offset + ")");
                },
                packet -> handleFileDownloadStartResult(packet.getFileDownloadStartResult()));
    }

    /** 主动暂停当前下载；Response 前已经提交的 File Chunk 仍是合法的。 */
    public synchronized void pauseFileDownload() {
        if (protocolErrorFired) {
            return;
        }
        if (fileState != FileState.FILE_DOWNLOADING || activeDownload == null) {
            listener.onLog("当前状态不允许暂停文件下载: " + fileState);
            return;
        }
        sendPauseRequest();
    }

    /**
     * 本地已验证文件后调用。此操作是自动投递确认，不暴露为用户删除功能。
     */
    public synchronized void completeFileDownload(String fileName) {
        if (protocolErrorFired) {
            return;
        }
        if (state != MainState.READY || fileState != FileState.FILE_IDLE
                || !isSafeFileName(fileName) || !listedRemoteFileNames.contains(fileName)) {
            listener.onLog("当前状态不允许确认文件下载完成: " + fileName);
            return;
        }
        sendCompleteRequest(fileName);
    }

    /**
     * 本地异步校验完成后回调。成功时发 complete；失败时仅清除本连接下载
     * 上下文，调用方负责重置 .part 与持久化 offset，不视为通信协议错误。
     */
    public synchronized void finishFileVerification(boolean success) {
        if (protocolErrorFired || fileState != FileState.FILE_VERIFYING
                || activeDownload == null) {
            listener.onLog("忽略不在校验状态的文件校验结果");
            return;
        }
        String fileName = activeDownload.fileName;
        if (!success) {
            clearDownloadContext();
            setFileState(FileState.FILE_IDLE);
            listener.onLog("文件校验失败，已回退为待传输: " + fileName);
            maybeStartQueuedRecording();
            return;
        }
        if (state != MainState.READY) {
            // 正常路径中校验时主状态一定 READY；若用户恰好已排队开始录音，
            // 优先执行录音，调用方会在下一次 list 对账中重试 complete。
            listener.onLog("文件校验通过，但当前不能发送 complete；下次列表对账重试");
            clearDownloadContext();
            setFileState(FileState.FILE_IDLE);
            maybeStartQueuedRecording();
            return;
        }
        sendCompleteRequest(fileName);
    }

    private void sendRecordingStart() {
        long timestamp = (System.currentTimeMillis() / 1000L) & 0xFFFFFFFFL;
        requestManager.submit(PacketType.REC_RECORDING_START_RESULT,
                id -> PacketCodec.encode(RecorderPacket.newBuilder()
                        .setType(PacketType.REC_RECORDING_START_REQUEST)
                        .setRecordingStartRequest(RecordingStartRequest.newBuilder()
                                .setHeader(RequestHeader.newBuilder().setId((int) id))
                                .setTimestamp((int) timestamp))
                        .build()),
                id -> {
                    startRequestInFlight = true;
                    concurrentStartRecordingId = 0L;
                    setState(MainState.STARTING_RECORDING);
                    listener.onLog("已投递 recording.start (id=" + id
                            + ", timestamp=" + timestamp + ")");
                },
                packet -> handleStartResult(packet.getRecordingStartResult()));
    }

    private void sendRecordingAttach(long recordingId) {
        if (attachInFlightRecordingId != 0L) {
            return;
        }
        attachInFlightRecordingId = recordingId;
        if (pendingAttachRecordingId == recordingId) {
            pendingAttachRecordingId = 0L;
        }
        requestManager.submit(PacketType.REC_RECORDING_ATTACH_RESULT,
                id -> PacketCodec.encode(RecorderPacket.newBuilder()
                        .setType(PacketType.REC_RECORDING_ATTACH_REQUEST)
                        .setRecordingAttachRequest(RecordingAttachRequest.newBuilder()
                                .setHeader(RequestHeader.newBuilder().setId((int) id))
                                .setTargetRecordingId((int) recordingId))
                        .build()),
                id -> {
                    boolean confirmsActiveStart = concurrentStartRecordingId == recordingId
                            && state == MainState.RECEIVING_LIVE_AUDIO
                            && currentRecordingId == recordingId;
                    if (!confirmsActiveStart) {
                        setState(MainState.STARTING_RECORDING);
                    }
                    listener.onLog("已投递 recording.attach (id=" + id
                            + ", recordingId=" + recordingId + ")"
                            + (confirmsActiveStart ? "，等待幂等确认" : ""));
                },
                packet -> handleAttachResult(packet.getRecordingAttachResult(), recordingId));
    }

    public synchronized void stopRecording() {
        if (protocolErrorFired) {
            return;
        }
        if (state != MainState.RECEIVING_LIVE_AUDIO) {
            listener.onLog("当前状态不允许停止录音: " + state);
            return;
        }
        long recordingId = currentRecordingId;
        requestManager.submit(PacketType.REC_RECORDING_STOP_RESULT,
                id -> PacketCodec.encode(RecorderPacket.newBuilder()
                        .setType(PacketType.REC_RECORDING_STOP_REQUEST)
                        .setRecordingStopRequest(RecordingStopRequest.newBuilder()
                                .setHeader(RequestHeader.newBuilder().setId((int) id))
                                .setRecordingId((int) recordingId))
                        .build()),
                id -> {
                    recordingStopConfirmed = false;
                    setState(MainState.STOPPING_RECORDING);
                    listener.onLog("已投递 recording.stop (id=" + id + ")");
                },
                packet -> handleStopResult(packet.getRecordingStopResult()));
    }

    /**
     * 客户端已完成 STOPPING_RECORDING 内的本地停止工作，可以恢复 READY。
     *
     * <p>设备 stop Response 只确认设备录音事务结束，不会自动结束 App 的停止阶段。
     * Android 上层应在 VoiceAI 已终止（或超时隔离）后调用本方法；该分层不让纯 Java
     * 业务状态机感知具体 ASR SDK。
     *
     * @return 是否成功完成当前停止阶段
     */
    public synchronized boolean completeRecordingStop() {
        if (protocolErrorFired || state != MainState.STOPPING_RECORDING
                || !recordingStopConfirmed) {
            listener.onLog("当前状态不允许完成录音停止: state=" + state
                    + ", deviceConfirmed=" + recordingStopConfirmed);
            return false;
        }
        recordingStopConfirmed = false;
        currentRecordingId = 0L;
        streamStartSequence = 0L;
        expectedSequence = 0L;
        setState(MainState.READY);
        listener.onLog("录音停止阶段已完成，恢复 READY");
        maybeStartQueuedRecording();
        return true;
    }

    // ==================== 传输层输入 ====================

    /** 传输层握手完成：当前业务会话正式进入可操作的 READY。 */
    public synchronized void onLinkReady() {
        if (protocolErrorFired || state != MainState.DISCONNECTED) {
            return;
        }
        setState(MainState.READY);
        requestDeviceStatus();
    }

    public synchronized void onMessage(byte[] message) {
        if (protocolErrorFired) {
            return;
        }
        final RecorderPacket packet;
        try {
            packet = PacketCodec.decode(message);
        } catch (DecodeException e) {
            protocolError("报文解码失败: " + e.getMessage());
            return;
        }

        switch (packet.getType()) {
            case DEVICE_STATUS_RESULT:
                handleManagedResponse(packet, packet.getDeviceStatusResult().getHeader());
                break;
            case REC_RECORDING_START_RESULT:
                handleManagedResponse(packet, packet.getRecordingStartResult().getHeader());
                break;
            case REC_RECORDING_ATTACH_RESULT:
                handleManagedResponse(packet, packet.getRecordingAttachResult().getHeader());
                break;
            case REC_RECORDING_STOP_RESULT:
                handleManagedResponse(packet, packet.getRecordingStopResult().getHeader());
                break;
            case REC_FILE_LIST_RESULT:
                handleManagedResponse(packet, packet.getFileListResult().getHeader());
                break;
            case REC_FILE_DOWNLOAD_START_RESULT:
                handleManagedResponse(packet, packet.getFileDownloadStartResult().getHeader());
                break;
            case REC_FILE_DOWNLOAD_PAUSE_RESULT:
                handleManagedResponse(packet, packet.getFileDownloadPauseResult().getHeader());
                break;
            case REC_FILE_DOWNLOAD_COMPLETE_RESULT:
                handleManagedResponse(packet, packet.getFileDownloadCompleteResult().getHeader());
                break;
            case REC_RECORDING_STARTED_EVENT:
                handleRecordingStartedEvent(packet.getRecordingStartedEvent());
                break;
            case REC_RECORDING_STOPPED_EVENT:
                handleRecordingStoppedEvent(packet.getRecordingStoppedEvent());
                break;
            case REC_AUDIO_FRAME:
                handleAudioFrame(packet.getAudioFrame());
                break;
            case REC_FILE_CHUNK:
                handleFileChunk(packet.getFileChunk());
                break;
            default:
                // App设计.md §2.8：收到当前 Demo 不支持的 PacketType
                protocolError("收到当前 Demo 不支持的报文类型: " + packet.getType());
                break;
        }
    }

    private void handleManagedResponse(RecorderPacket packet, ResponseHeader header) {
        boolean successful = header.getStatusCode() == StatusCode.OK;
        String failureReason = successful ? null
                : "设备返回错误 Response: " + header.getStatusCode()
                + (header.hasErrMsg() ? " - " + header.getErrMsg() : "");
        requestManager.onResponse(packet.getType(), header.getId() & 0xFFFFFFFFL,
                successful, failureReason, packet);
    }

    public synchronized void onLinkDown() {
        requestManager.close();
        invalidateConnectionState();
    }

    /** 本端发送方向软停滞：由 RequestManager 冻结 in-flight 超时。 */
    public synchronized void onTransportSendStalled() {
        requestManager.onSendStalled();
    }

    /** 本端发送方向恢复：由 RequestManager 恢复剩余超时预算。 */
    public synchronized void onTransportSendResumed() {
        requestManager.onSendResumed();
    }

    /** 释放 RequestManager 的连接级资源与定时器线程。 */
    public void shutdown() {
        requestManager.shutdown();
    }

    // ==================== Response / Event 处理 ====================

    private void handleDeviceStatusResult(DeviceStatusResult result) {
        int battery = result.getRemainingBatteryPercent();
        long total = result.getTotalStorageBytes();
        long used = result.getUsedStorageBytes();
        DeviceRecordingState recordingState = result.getRecordingState();
        boolean hasRecordingId = result.hasCurrentRecordingId();
        long recordingId = hasRecordingId
                ? result.getCurrentRecordingId() & 0xFFFFFFFFL : 0L;
        if (battery < 0 || battery > 100 || total < 0L || used < 0L || used > total
                || recordingState == DeviceRecordingState.DEVICE_RECORDING_STATE_UNSPECIFIED
                || recordingState == DeviceRecordingState.UNRECOGNIZED
                || (recordingState == DeviceRecordingState.DEVICE_RECORDING_STATE_IDLE
                && hasRecordingId)
                || (recordingState != DeviceRecordingState.DEVICE_RECORDING_STATE_IDLE
                && (!hasRecordingId || recordingId == 0L))) {
            protocolError("device.status 成功 Response 字段组合非法");
            return;
        }
        statusResolved = true;
        publishDeviceSnapshot(new DeviceSnapshot(battery, total, used,
                recordingState, recordingId));
        switch (recordingState) {
            case DEVICE_RECORDING_STATE_IDLE:
                requestFileList();
                break;
            case DEVICE_RECORDING_STATE_RECORDING:
                ensureAttach(recordingId);
                break;
            case DEVICE_RECORDING_STATE_STARTING:
            case DEVICE_RECORDING_STATE_STOPPING:
                listener.onLog("设备录音状态尚未稳定，等待 Event: " + recordingState);
                break;
            default:
                protocolError("device.status 返回未知录音状态");
                break;
        }
    }

    private void handleAttachResult(RecordingAttachResult result, long requestedRecordingId) {
        if (attachInFlightRecordingId != requestedRecordingId) {
            protocolError("recording.attach Response 的目标事务不匹配");
            return;
        }
        boolean confirmsConcurrentStart = concurrentStartRecordingId == requestedRecordingId;
        attachInFlightRecordingId = 0L;
        if (!result.getAttachedOk()) {
            if (result.hasResultBody()) {
                protocolError("recording.attach: attached_ok=false 但携带 result_body");
                return;
            }
            if (confirmsConcurrentStart) {
                protocolError("主动 start 已接入的事务未能通过幂等 attach 确认");
                return;
            }
            setState(MainState.READY);
            listener.onLog("目标录音已不可接入，恢复 READY");
            // 若 stopped Event 已先到，设备此刻必已 IDLE，可以立即同步；否则目标
            // 可能仍在 STOPPING，必须等待后续 stopped Event，避免 file.list 竞态失败。
            if (pendingAttachRecordingId != 0L) {
                maybeStartQueuedRecording();
            } else if (lastStoppedRecordingId == requestedRecordingId
                    && fileState == FileState.FILE_IDLE) {
                requestFileList();
            }
            return;
        }
        if (!result.hasResultBody()) {
            protocolError("recording.attach: attached_ok=true 但缺少 result_body");
            return;
        }
        RecordingAttachResultBody body = result.getResultBody();
        long recordingId = body.getRecordingId() & 0xFFFFFFFFL;
        if (recordingId != requestedRecordingId) {
            protocolError("recording.attach Response 的 recording_id 不匹配");
            return;
        }
        long currentDurationMs = body.getCurrentDurationMs();
        if (currentDurationMs < 0L) {
            protocolError("recording.attach current_duration_ms 超出支持范围");
            return;
        }
        long frameDurationMs = body.getFrameDurationMs() & 0xFFFFFFFFL;
        long sampleRateHz = body.getSampleRateHz() & 0xFFFFFFFFL;
        long channelCount = body.getChannelCount() & 0xFFFFFFFFL;
        long startSequence = body.getStreamStartSequence() & 0xFFFFFFFFL;
        if (confirmsConcurrentStart) {
            if (state != MainState.RECEIVING_LIVE_AUDIO
                    || currentRecordingId != requestedRecordingId) {
                protocolError("幂等 recording.attach Response 到达时当前录音事务不匹配");
                return;
            }
            if (!validStreamParameters(recordingId, frameDurationMs, body.getCodec(),
                    sampleRateHz, channelCount)) {
                protocolError("幂等 recording.attach Response 必填字段缺失/非法");
                return;
            }
            if (startSequence != streamStartSequence) {
                protocolError("幂等 recording.attach Response 未回显相同的 "
                        + "stream_start_sequence");
                return;
            }
            concurrentStartRecordingId = 0L;
            listener.onLog("recording.attach 已幂等确认当前事务 recordingId="
                    + requestedRecordingId + "，保持既有实时推流区间");
            return;
        }
        beginReceiving(recordingId, frameDurationMs, body.getCodec(), sampleRateHz,
                channelCount,
                body.hasCodecConfig() ? body.getCodecConfig().toByteArray() : null,
                startSequence, true, currentDurationMs);
    }

    private void handleStartResult(RecordingStartResult result) {
        long recordingId = result.getRecordingId() & 0xFFFFFFFFL;
        if (!startRequestInFlight) {
            protocolError("收到 recording.start Response 时没有对应的主动 start 上下文");
            return;
        }
        if (concurrentStartRecordingId != 0L
                && concurrentStartRecordingId != recordingId) {
            protocolError("recording.started Event 与 recording.start Response 的事务不一致");
            return;
        }
        startRequestInFlight = false;
        beginReceiving(recordingId,
                result.getFrameDurationMs() & 0xFFFFFFFFL, result.getCodec(),
                result.getSampleRateHz() & 0xFFFFFFFFL,
                result.getChannelCount() & 0xFFFFFFFFL,
                result.hasCodecConfig() ? result.getCodecConfig().toByteArray() : null,
                result.getStreamStartSequence() & 0xFFFFFFFFL, false, 0L);
    }

    private void beginReceiving(long recordingId, long frameDurationMs, AudioCodec codec,
                                long sampleRateHz, long channelCount, byte[] codecConfig,
                                long startSequence, boolean attached, long currentDurationMs) {
        if (!validStreamParameters(recordingId, frameDurationMs, codec,
                sampleRateHz, channelCount)) {
            protocolError("录音接入成功 Response 必填字段缺失/非法");
            return;
        }
        currentRecordingId = recordingId;
        streamStartSequence = startSequence;
        expectedSequence = startSequence;
        receivedFrameCount = 0;
        recordingStopConfirmed = false;
        setState(MainState.RECEIVING_LIVE_AUDIO);
        listener.onLog("录音已" + (attached ? "接入" : "开始") + " recordingId="
                + currentRecordingId + "，streamStartSequence=" + streamStartSequence
                + "（" + sampleRateHz + "Hz x" + channelCount + " "
                + frameDurationMs + "ms/帧，csd-0: "
                + (codecConfig == null ? "无" : codecConfig.length + "B") + "）");
        listener.onRecordingStarted(currentRecordingId, frameDurationMs,
                sampleRateHz, channelCount, codecConfig);
        if (attached) {
            listener.onRecordingAttached(currentRecordingId, currentDurationMs);
        }
    }

    private static boolean validStreamParameters(long recordingId, long frameDurationMs,
                                                 AudioCodec codec, long sampleRateHz,
                                                 long channelCount) {
        return recordingId != 0L && codec == AudioCodec.AUDIO_CODEC_OPUS
                && sampleRateHz != 0L && channelCount != 0L && frameDurationMs != 0L;
    }

    private void handleStopResult(RecordingStopResult result) {
        listener.onLog("收到有效 recording.stop Response");

        acceptStoppedSummary(result.getRecordingId() & 0xFFFFFFFFL,
                result.getFrameCount() & 0xFFFFFFFFL, result.hasLastSequence(),
                result.hasLastSequence() ? result.getLastSequence() & 0xFFFFFFFFL : 0L,
                "recording.stop Response");
    }

    private void handleRecordingStartedEvent(RecordingStartedEvent event) {
        long recordingId = event.getRecordingId() & 0xFFFFFFFFL;
        if (recordingId == 0L) {
            protocolError("recording.started Event 的 recording_id 缺失/非法");
            return;
        }
        if (state == MainState.RECEIVING_LIVE_AUDIO) {
            if (currentRecordingId == recordingId) {
                return;
            }
            protocolError("录音中收到另一事务的 recording.started Event");
            return;
        }
        if (startRequestInFlight) {
            if (concurrentStartRecordingId != 0L
                    && concurrentStartRecordingId != recordingId) {
                protocolError("主动 start 在途时收到不同事务的 recording.started Event");
                return;
            }
            concurrentStartRecordingId = recordingId;
            listener.onLog("主动 start 与设备按键发生竞争，排队幂等 attach: recordingId="
                    + recordingId);
        }
        publishRecordingState(DeviceRecordingState.DEVICE_RECORDING_STATE_RECORDING,
                recordingId);
        ensureAttach(recordingId);
    }

    private void handleRecordingStoppedEvent(RecordingStoppedEvent event) {
        long recordingId = event.getRecordingId() & 0xFFFFFFFFL;
        if (recordingId == lastStoppedRecordingId) {
            return;
        }

        listener.onLog("收到有效 recording.stopped Event");
        
        publishRecordingState(DeviceRecordingState.DEVICE_RECORDING_STATE_IDLE, 0L);
        if (recordingId == pendingAttachRecordingId && currentRecordingId == 0L) {
            if (!validStopSummary(recordingId, event.getFrameCount() & 0xFFFFFFFFL,
                    event.hasLastSequence(), event.hasLastSequence()
                            ? event.getLastSequence() & 0xFFFFFFFFL : 0L,
                    "recording.stopped Event")) {
                return;
            }
            pendingAttachRecordingId = 0L;
            lastStoppedRecordingId = recordingId;
            listener.onLog("尚未提交 attach 的目标事务已停止，已取消接入意图");
            if (state == MainState.READY && fileState == FileState.FILE_IDLE) {
                requestFileList();
            }
            return;
        }
        if (recordingId == attachInFlightRecordingId && currentRecordingId == 0L) {
            if (!validStopSummary(recordingId, event.getFrameCount() & 0xFFFFFFFFL,
                    event.hasLastSequence(), event.hasLastSequence()
                            ? event.getLastSequence() & 0xFFFFFFFFL : 0L,
                    "recording.stopped Event")) {
                return;
            }
            lastStoppedRecordingId = recordingId;
            listener.onLog("在途 attach 的目标事务已停止，等待 Result 收敛");
            return;
        }
        if (recordingId != currentRecordingId || currentRecordingId == 0L) {
            // 可能是 status 报告 STOPPING 后的稳定 Event；没有实时会话需要收尾。
            if (!validStopSummary(recordingId, event.getFrameCount() & 0xFFFFFFFFL,
                    event.hasLastSequence(), event.hasLastSequence()
                            ? event.getLastSequence() & 0xFFFFFFFFL : 0L,
                    "recording.stopped Event")) {
                return;
            }
            lastStoppedRecordingId = recordingId;
            if (state == MainState.READY && fileState == FileState.FILE_IDLE) {
                requestFileList();
            }
            return;
        }
        if (state == MainState.RECEIVING_LIVE_AUDIO) {
            setState(MainState.STOPPING_RECORDING);
        }
        acceptStoppedSummary(recordingId, event.getFrameCount() & 0xFFFFFFFFL,
                event.hasLastSequence(), event.hasLastSequence()
                        ? event.getLastSequence() & 0xFFFFFFFFL : 0L,
                "recording.stopped Event");
    }

    private void acceptStoppedSummary(long recordingId, long frameCount,
                                      boolean hasLastSequence, long lastSequence,
                                      String source) {
        if (!validStopSummary(recordingId, frameCount, hasLastSequence, lastSequence, source)) {
            return;
        }
        if (lastStoppedRecordingId == recordingId) {
            return;
        }
        if (recordingId != currentRecordingId) {
            protocolError(source + " 的 recording_id 不匹配");
            return;
        }
        if (frameCount < streamStartSequence) {
            protocolError(source + ": frame_count 小于本连接 stream_start_sequence");
            return;
        }
        long connectionFrameCount = frameCount - streamStartSequence;
        if (receivedFrameCount > connectionFrameCount) {
            protocolError(source + ": 本连接理论帧数(" + connectionFrameCount
                    + ")小于已接收帧数(" + receivedFrameCount + ")");
            return;
        }
        long lost = connectionFrameCount - receivedFrameCount;
        lastStoppedRecordingId = recordingId;
        recordingStopConfirmed = true;
        listener.onLog("录音已停止：事务共 " + frameCount + " 帧，本连接区间 "
                + connectionFrameCount + " 帧，App 接收 " + receivedFrameCount + " 帧"
                + (lost > 0 ? "（整帧丢失 " + lost + "）" : ""));
        listener.onRecordingStopped(frameCount, receivedFrameCount);
    }

    private boolean validStopSummary(long recordingId, long frameCount,
                                     boolean hasLastSequence, long lastSequence,
                                     String source) {
        if (recordingId == 0L) {
            protocolError(source + " 的 recording_id 非法");
            return false;
        }
        if (frameCount == 0L && hasLastSequence) {
            protocolError(source + ": frame_count=0 但设置了 last_sequence");
            return false;
        }
        if (frameCount > 0L && (!hasLastSequence || lastSequence != frameCount - 1L)) {
            protocolError(source + ": last_sequence 与 frame_count 矛盾");
            return false;
        }
        return true;
    }

    private void handleFileListResult(FileListResult result) {
        if (fileState != FileState.FILE_LISTING) {
            protocolError("file.list Response 到达时文件状态不匹配: " + fileState);
            return;
        }
        List<RemoteFile> files = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long previousCreated = Long.MAX_VALUE;
        for (FileMetadata metadata : result.getFilesList()) {
            String name = metadata.getFileName();
            long created = metadata.getCreatedTime() & 0xFFFFFFFFL;
            long duration = metadata.getDurationMs();
            if (!isSafeFileName(name) || !names.add(name)) {
                protocolError("file.list 包含非法或重复文件名: " + name);
                return;
            }
            if (created > previousCreated || duration < 0L) {
                protocolError("file.list 未按创建时间倒序或包含非法时长");
                return;
            }
            previousCreated = created;
            files.add(new RemoteFile(name, created, duration));
        }
        setFileState(FileState.FILE_IDLE);
        listedRemoteFileNames.clear();
        listedRemoteFileNames.addAll(names);
        listener.onFileList(Collections.unmodifiableList(files));
        maybeStartQueuedRecording();
    }

    private void handleFileDownloadStartResult(FileDownloadStartResult result) {
        if (fileState != FileState.FILE_STARTING || activeDownload == null) {
            protocolError("download.start Response 到达时文件状态不匹配: " + fileState);
            return;
        }
        long transferId = result.getTransferId() & 0xFFFFFFFFL;
        long fileSize = result.getFileSize();
        long chunkSize = result.getChunkPayloadBytes() & 0xFFFFFFFFL;
        long sampleRate = result.getSampleRateHz() & 0xFFFFFFFFL;
        long channelCount = result.getChannelCount() & 0xFFFFFFFFL;
        if (transferId == 0L || fileSize <= 0L || fileSize <= expectedFileOffset
                || chunkSize <= 0L || chunkSize > requestedMaxChunkPayloadBytes
                || chunkSize > Integer.MAX_VALUE
                || result.getCodec() != AudioCodec.AUDIO_CODEC_OPUS
                || sampleRate == 0L || channelCount == 0L) {
            protocolError("download.start 成功 Response 必填字段缺失/非法");
            return;
        }
        byte[] codecConfig = result.hasCodecConfig()
                ? result.getCodecConfig().toByteArray() : null;
        activeDownload = new DownloadInfo(activeDownload.fileName, transferId,
                expectedFileOffset, fileSize, (int) chunkSize,
                result.getCrc() & 0xFFFFFFFFL, sampleRate, channelCount, codecConfig);
        setFileState(FileState.FILE_DOWNLOADING);
        listener.onFileDownloadStarted(activeDownload);
        if (queuedRecordingStart || pendingAttachRecordingId != 0L) {
            sendPauseRequest();
        }
    }

    private void handleFileDownloadPauseResult(FileDownloadPauseResult result) {
        if (fileState != FileState.FILE_PAUSING || activeDownload == null
                || (result.getTransferId() & 0xFFFFFFFFL) != activeDownload.transferId) {
            protocolError("download.pause Response 的状态或 transfer_id 不匹配");
            return;
        }
        DownloadInfo info = activeDownload;
        long nextOffset = expectedFileOffset;
        listener.onFileDownloadPaused(info, nextOffset);
        if (verificationPendingAfterPause) {
            verificationPendingAfterPause = false;
            if (queuedRecordingStart || pendingAttachRecordingId != 0L) {
                // 实时录音优先：最后块恰好与 pause 交错时，交给上层在后台完成
                // 校验/封装；本连接立即释放文件状态并发起 recording.start。
                // 校验成功后的 complete 会在本次 STOPPING_RECORDING 完成
                // 并重新 list 对账时补发。
                clearDownloadContext();
                setFileState(FileState.FILE_IDLE);
                listener.onFileReadyForVerification(info);
                maybeStartQueuedRecording();
                return;
            }
            setFileState(FileState.FILE_VERIFYING);
            listener.onFileReadyForVerification(info);
            return;
        }
        clearDownloadContext();
        setFileState(FileState.FILE_IDLE);
        maybeStartQueuedRecording();
    }

    private boolean checkValidFileDownloadCompleteResult(FileDownloadCompleteResult result) {
        String actualFileName = result.getFileName();

        if (actualFileName == null) {
            protocolError(
                    "download.complete Response 文件名为空，期望文件名: "
                            + pendingCompleteFileName);
            return false;
        }

        if (!isSafeFileName(actualFileName)) {
            protocolError(
                    "download.complete Response 文件名非法或不安全，实际文件名: "
                            + actualFileName);
            return false;
        }

        if (!Objects.equals(actualFileName, pendingCompleteFileName)) {
            protocolError(
                    "download.complete Response 文件名与预期不一致，实际文件名: "
                            + actualFileName
                            + "，期望文件名: "
                            + pendingCompleteFileName);
            return false;
        }

        return true;
    }

    private void handleFileDownloadCompleteResult(FileDownloadCompleteResult result) {
        if (fileState != FileState.FILE_COMPLETING) {
            protocolError(
                    "download.complete Response 状态非法，当前状态: " + fileState
                            + "，期望状态: " + FileState.FILE_COMPLETING);
            return;
        }

        if (!checkValidFileDownloadCompleteResult(result)) {
            return;
        }

        String fileName = result.getFileName();
        listedRemoteFileNames.remove(fileName);
        clearDownloadContext();
        setFileState(FileState.FILE_IDLE);
        listener.onFileDownloadCompleted(fileName);
        maybeStartQueuedRecording();
    }

    private boolean checkValidFilechunk(long transferId, long offset, byte[] payload) {
        if (transferId != activeDownload.transferId) {
            protocolError(
                    "File Chunk transfer_id 非法: actual=" + transferId
                            + ", expected=" + activeDownload.transferId);
            return false;
        }

        if (payload.length == 0) {
            protocolError("File Chunk payload 为空");
            return false;
        }

        if (payload.length > activeDownload.chunkPayloadBytes) {
            protocolError(
                    "File Chunk payload 过大: actual=" + payload.length
                            + ", max=" + activeDownload.chunkPayloadBytes);
            return false;
        }

        if (offset < 0L) {
            protocolError(
                    "File Chunk offset 小于 0: actual=" + offset);
            return false;
        }

        if (offset > activeDownload.fileSize) {
            protocolError(
                    "File Chunk offset 超过文件大小: offset=" + offset
                            + ", fileSize=" + activeDownload.fileSize);
            return false;
        }

        if (offset != expectedFileOffset) {
            protocolError(
                    "File Chunk offset 与预期不一致: actual=" + offset
                            + ", expected=" + expectedFileOffset);
            return false;
        }

        long remainingBytes = activeDownload.fileSize - offset;
        if (payload.length > remainingBytes) {
            protocolError(
                    "File Chunk payload 超过剩余文件大小: payload.length=" + payload.length
                            + ", remainingBytes=" + remainingBytes
                            + ", offset=" + offset
                            + ", fileSize=" + activeDownload.fileSize);
            return false;
        }

        return true;
    }

    private void handleFileChunk(FileChunk chunk) {
        if ((fileState != FileState.FILE_DOWNLOADING && fileState != FileState.FILE_PAUSING)
                || activeDownload == null) {
            protocolError("在非下载状态收到 File Chunk: " + fileState);
            return;
        }

        long transferId = chunk.getTransferId() & 0xFFFFFFFFL;
        long offset = chunk.getOffset();
        byte[] payload = chunk.getFilePayload().toByteArray();
        if (!checkValidFilechunk(transferId, offset, payload)) {
            return;
        }

        long endOffset = offset + payload.length;
        boolean derivedEnd = endOffset == activeDownload.fileSize;
        if (chunk.getIsEnd() != derivedEnd) {
            protocolError("File Chunk 的 is_end 与文件边界不一致");
            return;
        }

        expectedFileOffset = endOffset;
        
        listener.onFileChunk(activeDownload, offset, derivedEnd, payload);
        if (!derivedEnd) {
            return;
        }
        if (fileState == FileState.FILE_PAUSING) {
            // pause Request 已发送时，最后一个已提交的 Chunk 仍可先到；必须等
            // pause Response 后才允许进入后续 Request，以维持唯一 pending 纪律。
            verificationPendingAfterPause = true;
            return;
        }
        setFileState(FileState.FILE_VERIFYING);
        listener.onFileReadyForVerification(activeDownload);
    }

    // ==================== Audio Frame 处理 ====================

    private void handleAudioFrame(AudioFrame frame) {
        if (state == MainState.STOPPING_RECORDING) {
            // App设计.md §1.1：此状态下收到的音频帧直接抛弃（不是错误）。
            return;
        }
        if (state != MainState.RECEIVING_LIVE_AUDIO) {
            protocolError("在非录音状态收到音频帧: " + state);
            return;
        }
        if ((frame.getRecordingId() & 0xFFFFFFFFL) != currentRecordingId) {
            protocolError("音频帧 recording_id(" + frame.getRecordingId() + ")不匹配");
            return;
        }
        long seq = frame.getSequence() & 0xFFFFFFFFL;
        if (seq < expectedSequence) {
            protocolError("音频帧 sequence 重复或倒退: seq=" + seq
                    + " expected=" + expectedSequence);
            return;
        }
        // seq > expected 表示整帧丢失，不属于协议错误（draft v6 §2.3）。
        expectedSequence = seq + 1;
        receivedFrameCount++;
        listener.onAudioFrame(frame.getAudioPayload().toByteArray());
    }

    // ==================== 文件 Request 构造与排队录音 ====================

    private void sendPauseRequest() {
        if (activeDownload == null || fileState != FileState.FILE_DOWNLOADING) {
            return;
        }
        long transferId = activeDownload.transferId;
        requestManager.submit(PacketType.REC_FILE_DOWNLOAD_PAUSE_RESULT,
                id -> PacketCodec.encode(RecorderPacket.newBuilder()
                        .setType(PacketType.REC_FILE_DOWNLOAD_PAUSE_REQUEST)
                        .setFileDownloadPauseRequest(FileDownloadPauseRequest.newBuilder()
                                .setHeader(RequestHeader.newBuilder().setId((int) id))
                                .setTransferId((int) transferId))
                        .build()),
                id -> {
                    setFileState(FileState.FILE_PAUSING);
                    listener.onLog("已投递 recording.file.download.pause (id=" + id
                            + ", transferId=" + transferId + ")");
                },
                packet -> handleFileDownloadPauseResult(packet.getFileDownloadPauseResult()));
    }

    private void sendCompleteRequest(String fileName) {
        requestManager.submit(PacketType.REC_FILE_DOWNLOAD_COMPLETE_RESULT,
                id -> PacketCodec.encode(RecorderPacket.newBuilder()
                        .setType(PacketType.REC_FILE_DOWNLOAD_COMPLETE_REQUEST)
                        .setFileDownloadCompleteRequest(FileDownloadCompleteRequest.newBuilder()
                                .setHeader(RequestHeader.newBuilder().setId((int) id))
                                .setFileName(fileName))
                        .build()),
                id -> {
                    pendingCompleteFileName = fileName;
                    setFileState(FileState.FILE_COMPLETING);
                    listener.onLog("已投递 recording.file.download.complete (id=" + id
                            + ", file=" + fileName + ")");
                },
                packet -> handleFileDownloadCompleteResult(
                        packet.getFileDownloadCompleteResult()));
    }

    private void maybeStartQueuedRecording() {
        if (protocolErrorFired || state != MainState.READY
                || fileState != FileState.FILE_IDLE) {
            return;
        }
        if (pendingAttachRecordingId != 0L) {
            sendRecordingAttach(pendingAttachRecordingId);
            return;
        }
        if (queuedRecordingStart) {
            queuedRecordingStart = false;
            sendRecordingStart();
        }
    }

    private void clearDownloadContext() {
        activeDownload = null;
        expectedFileOffset = 0L;
        requestedMaxChunkPayloadBytes = 0;
        verificationPendingAfterPause = false;
        pendingCompleteFileName = null;
    }

    private void setFileState(FileState newState) {
        if (fileState == newState) {
            return;
        }
        fileState = newState;
        listener.onFileStateChanged(newState);
    }

    private static boolean isSafeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty() || fileName.length() > 255) {
            return false;
        }
        // 协议中的 file_name 是逻辑主键而非路径；拒绝目录分隔符及控制符。
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c == '/' || c == '\\' || c == 0 || Character.isISOControl(c)) {
                return false;
            }
        }
        return !".".equals(fileName) && !"..".equals(fileName);
    }

    // ==================== 收尾 ====================

    private void publishDeviceSnapshot(DeviceSnapshot snapshot) {
        deviceSnapshot = snapshot;
        listener.onDeviceStatus(snapshot);
    }

    private void publishRecordingState(DeviceRecordingState recordingState, long recordingId) {
        DeviceSnapshot snapshot = deviceSnapshot;
        if (snapshot == null) {
            return;
        }
        publishDeviceSnapshot(new DeviceSnapshot(snapshot.batteryPercentage,
                snapshot.totalStorageBytes, snapshot.usedStorageBytes,
                recordingState, recordingId));
    }

    private void setState(MainState newState) {
        state = newState;
        listener.onStateChanged(newState);
    }

    private void protocolError(String reason) {
        if (protocolErrorFired) {
            return;
        }
        requestManager.fail(reason);
    }

    private synchronized void onRequestManagerFatal(String reason) {
        invalidateConnectionState();
        if (protocolErrorNotified) {
            return;
        }
        protocolErrorNotified = true;
        listener.onLog("业务协议错误：" + reason);
        listener.onProtocolError(reason);
    }

    private void invalidateConnectionState() {
        protocolErrorFired = true;   // 本会话随连接作废，重连后由上层重建
        currentRecordingId = 0L;
        streamStartSequence = 0L;
        expectedSequence = 0L;
        receivedFrameCount = 0;
        statusResolved = false;
        startRequestInFlight = false;
        concurrentStartRecordingId = 0L;
        pendingAttachRecordingId = 0L;
        attachInFlightRecordingId = 0L;
        lastStoppedRecordingId = 0L;
        deviceSnapshot = null;
        queuedRecordingStart = false;
        recordingStopConfirmed = false;
        clearDownloadContext();
        listedRemoteFileNames.clear();
        if (fileState != FileState.FILE_IDLE) {
            setFileState(FileState.FILE_IDLE);
        }
        if (state != MainState.DISCONNECTED) {
            setState(MainState.DISCONNECTED);
        }
    }
}
