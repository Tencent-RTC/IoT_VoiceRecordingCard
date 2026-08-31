package com.recorder.client.offline;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.recorder.business.AppRecorderSession;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * App 侧历史录音的统一数据入口。
 *
 * <p>本类独占 {@link RecordingHistoryStore} 与串行 IO 队列，负责把 SQLite 中的
 * 持久化记录和离线同步控制器提供的运行时状态合并为不可变 {@link HistorySnapshot}，
 * 再在主线程分发给任意数量的页面订阅者。BLE 同步和后续云端转写都是状态生产者，
 * UI 只消费这里发布的快照，不反向查询具体控制器。
 *
 * <p>包内的 {@code *OnSerial} 方法只允许由本 Repository 的串行任务调用。它们作为
 * {@link OfflineSyncController} 的持久化端口存在，使控制器不再直接持有 SQLite helper。
 */
public final class RecordingHistoryRepository {

    /** 历史快照变化均在主线程通知。 */
    public interface Listener {
        void onHistoryChanged(HistorySnapshot snapshot);
    }

    /** 单条历史记录读取结果始终在主线程回调；记录不存在时为 null。 */
    public interface EntryListener {
        void onLoaded(RecordingHistoryStore.Entry entry);
    }

    public interface EnqueueCallback {
        void onComplete(RecordingHistoryStore.EnqueueResult result);
    }

    public interface JobListListener {
        void onLoaded(List<RecordingHistoryStore.AsrJob> jobs);
    }

    public interface CancelCallback {
        void onComplete(RecordingHistoryStore.AsrJob canceledJob);
    }

    /** 重命名结果始终在主线程回调。 */
    public interface RenameCallback {
        void onRenamed();

        void onFailed(String reason);
    }

    /**
     * 列表的完整渲染输入：SQLite Entry 加上无需高频落库的同步运行时字段。
     */
    public static final class HistorySnapshot {
        public final List<RecordingHistoryStore.Entry> entries;
        public final boolean connectedToRecorder;
        public final String activeTransferFileName;
        public final double transferRateKBps;

        private HistorySnapshot(List<RecordingHistoryStore.Entry> entries,
                                boolean connectedToRecorder,
                                String activeTransferFileName,
                                double transferRateKBps) {
            this.entries = entries;
            this.connectedToRecorder = connectedToRecorder;
            this.activeTransferFileName = activeTransferFileName;
            this.transferRateKBps = transferRateKBps;
        }

        public boolean isActivelyDownloading(RecordingHistoryStore.Entry entry) {
            return entry != null && activeTransferFileName != null
                    && activeTransferFileName.equals(entry.fileName);
        }

        public RecordingHistoryStore.Entry findEntry(String fileName) {
            if (fileName == null) {
                return null;
            }
            for (RecordingHistoryStore.Entry entry : entries) {
                if (fileName.equals(entry.fileName)) {
                    return entry;
                }
            }
            return null;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService serial = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "recording-history-io");
        thread.setDaemon(true);
        return thread;
    });
    private final RecordingHistoryStore store;
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Map<String, CopyOnWriteArraySet<EntryListener>> entryListeners =
            new ConcurrentHashMap<>();

    private volatile HistorySnapshot snapshot = new HistorySnapshot(
            Collections.emptyList(), false, null, 0.0);

    /** 以下字段只在 recording-history-io 串行队列中读写。 */
    private boolean includeQueuedRecords;
    private boolean connectedToRecorder;
    private String activeTransferFileName;
    private double transferRateKBps;

    public RecordingHistoryRepository(Context context) {
        store = new RecordingHistoryStore(context.getApplicationContext());
    }

    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
        HistorySnapshot current = snapshot;
        main.post(() -> {
            if (listeners.contains(listener)) {
                listener.onHistoryChanged(current);
            }
        });
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public HistorySnapshot snapshot() {
        return snapshot;
    }

    /** 冷启动或订阅建立时，从 SQLite 重新生成当前连接模式下的快照。 */
    public void refresh() {
        executeSerial(() -> publishSnapshotOnSerial(includeQueuedRecords, null));
    }

    public void loadEntry(String fileName, EntryListener callback) {
        if (fileName == null || callback == null) {
            return;
        }
        executeSerial(() -> {
            RecordingHistoryStore.Entry entry = store.get(fileName);
            main.post(() -> callback.onLoaded(entry));
        });
    }

    /** 订阅单条录音；初始值和后续任务状态/结果均在主线程回调。 */
    public void addEntryListener(String fileName, EntryListener listener) {
        if (fileName == null || listener == null) {
            return;
        }
        entryListeners.computeIfAbsent(fileName, ignored -> new CopyOnWriteArraySet<>())
                .add(listener);
        RecordingHistoryStore.Entry cached = snapshot.findEntry(fileName);
        if (cached != null) {
            main.post(() -> {
                if (containsEntryListener(fileName, listener)) {
                    listener.onLoaded(cached);
                }
            });
            return;
        }
        executeSerial(() -> {
            RecordingHistoryStore.Entry entry = store.get(fileName);
            main.post(() -> {
                if (containsEntryListener(fileName, listener)) {
                    listener.onLoaded(entry);
                }
            });
        });
    }

    public void removeEntryListener(String fileName, EntryListener listener) {
        if (fileName == null || listener == null) {
            return;
        }
        CopyOnWriteArraySet<EntryListener> perFile = entryListeners.get(fileName);
        if (perFile != null) {
            perFile.remove(listener);
            if (perFile.isEmpty()) {
                entryListeners.remove(fileName, perFile);
            }
        }
    }

    private boolean containsEntryListener(String fileName, EntryListener listener) {
        CopyOnWriteArraySet<EntryListener> perFile = entryListeners.get(fileName);
        return perFile != null && perFile.contains(listener);
    }

    // ==================== CloudTranscriptionController / Worker 端口 ====================

    public void enqueueCloudTranscription(String fileName, String audioPath, long sourceSize,
                                          long sourceModifiedMs, String workId,
                                          EnqueueCallback callback) {
        executeSerial(() -> {
            RecordingHistoryStore.EnqueueResult result = store.enqueueAsrJob(fileName, audioPath,
                    sourceSize, sourceModifiedMs, workId);
            if (result.accepted) {
                publishSnapshotOnSerial(includeQueuedRecords, null);
            }
            if (callback != null) {
                main.post(() -> callback.onComplete(result));
            }
        });
    }

    public void loadRecoverableAsrJobs(JobListListener listener) {
        if (listener == null) {
            return;
        }
        executeSerial(() -> {
            List<RecordingHistoryStore.AsrJob> jobs = Collections.unmodifiableList(
                    store.listRecoverableAsrJobs());
            main.post(() -> listener.onLoaded(jobs));
        });
    }

    public void cancelCloudTranscription(String fileName, CancelCallback callback) {
        executeSerial(() -> {
            RecordingHistoryStore.AsrJob canceled = store.cancelAsrJob(fileName);
            if (canceled != null) {
                publishSnapshotOnSerial(includeQueuedRecords, null);
            }
            if (callback != null) {
                main.post(() -> callback.onComplete(canceled));
            }
        });
    }

    /**
     * 仅修改本地 {@code recording_name}，并在成功后发布新的不可变历史快照。
     * 不触及 file_name、离线文件或设备侧状态。
     */
    public void renameRecording(String fileName, String recordingName, RenameCallback callback) {
        final String normalized = RecordingNameFormatter.normalizeUserName(recordingName);
        if (fileName == null || normalized == null) {
            if (callback != null) {
                main.post(() -> callback.onFailed("录音名称不能为空"));
            }
            return;
        }
        executeSerial(() -> {
            boolean renamed = store.renameRecording(fileName, normalized);
            if (renamed) {
                publishSnapshotOnSerial(includeQueuedRecords, null);
            }
            if (callback != null) {
                main.post(() -> {
                    if (renamed) {
                        callback.onRenamed();
                    } else {
                        callback.onFailed("录音记录不存在或名称无效");
                    }
                });
            }
        });
    }

    /** 以下阻塞端口只能由 WorkManager 后台线程调用。 */
    public RecordingHistoryStore.AsrJob getAsrJobBlocking(String fileName, long generation) {
        return callSerial(() -> store.getAsrJob(fileName, generation));
    }

    public boolean claimAsrJobBlocking(String fileName, long generation) {
        return mutateAsrJobBlocking(() -> store.claimAsrJob(fileName, generation));
    }

    public boolean markAsrRetryWaitingBlocking(String fileName, long generation,
                                               int retryCount, String errorMessage) {
        return mutateAsrJobBlocking(() -> store.markAsrRetryWaiting(fileName, generation,
                retryCount, errorMessage));
    }

    public boolean markAsrFailedBlocking(String fileName, long generation, int retryCount,
                                         String errorMessage) {
        return mutateAsrJobBlocking(() -> store.markAsrFailed(fileName, generation, retryCount,
                errorMessage));
    }

    public List<RecordingHistoryStore.AsrChunkTask> listAsrChunksBlocking(
            String fileName, long generation) {
        return callSerial(() -> store.listAsrChunks(fileName, generation));
    }

    public boolean saveAsrChunkSubmittedBlocking(String fileName, long generation,
                                                 int chunkIndex, String recTaskId) {
        return callSerial(() -> store.saveAsrChunkSubmitted(fileName, generation, chunkIndex,
                recTaskId));
    }

    public boolean saveAsrChunkCompletedBlocking(String fileName, long generation,
                                                 int chunkIndex, String resultText,
                                                 String resultDetailJson, long durationMs,
                                                 int maxSpeakerId) {
        return callSerial(() -> store.saveAsrChunkCompleted(fileName, generation, chunkIndex,
                resultText, resultDetailJson, durationMs, maxSpeakerId));
    }

    public boolean completeAsrJobBlocking(String fileName, long generation, String asrText,
                                          String asrDetailJson) {
        return mutateAsrJobBlocking(() -> store.completeAsrJob(fileName, generation, asrText,
                asrDetailJson));
    }

    private boolean mutateAsrJobBlocking(Callable<Boolean> mutation) {
        return callSerial(() -> {
            boolean changed = mutation.call();
            if (changed) {
                publishSnapshotOnSerial(includeQueuedRecords, null);
            }
            return changed;
        });
    }

    private <T> T callSerial(Callable<T> callable) {
        try {
            return serial.submit(callable).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待历史数据库操作时被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("历史数据库操作失败", e.getCause());
        }
    }

    // ==================== OfflineSyncController 持久化端口 ====================

    void executeSerial(Runnable task) {
        serial.execute(task);
    }

    void mergeRemoteOnSerial(List<AppRecorderSession.RemoteFile> files) {
        store.mergeRemote(files);
    }

    void confirmLegacyDeviceDeletionsOnSerial() {
        store.confirmLegacyDeviceDeletionsAfterEmptyRemoteList();
    }

    void beginDownloadOnSerial(AppRecorderSession.DownloadInfo info) {
        store.beginDownload(info);
    }

    void updateProgressOnSerial(String fileName, long offset) {
        store.updateProgress(fileName, offset);
    }

    void resetTransferOnSerial(String fileName) {
        store.resetTransfer(fileName);
    }

    void markTransferredOnSerial(String fileName, String oggPath) {
        store.markTransferred(fileName, oggPath);
    }

    void markDeviceDeletionConfirmedOnSerial(String fileName) {
        store.markDeviceDeletionConfirmed(fileName);
    }

    int deleteConfirmedLocalHistoryOnSerial(String fileName) {
        return store.deleteConfirmedLocalHistory(fileName);
    }

    RecordingHistoryStore.Entry getOnSerial(String fileName) {
        return store.get(fileName);
    }

    List<RecordingHistoryStore.Entry> listNewestFirstOnSerial() {
        return store.listNewestFirst();
    }

    List<RecordingHistoryStore.Entry> listAllNewestFirstOnSerial() {
        return store.listAllNewestFirst();
    }

    void replaceAliasesForFileNameOnSerial(RecordingHistoryStore.Entry source,
                                           RecordingHistoryStore.TransferState transferState,
                                           long transferOffset, String canonicalLocalPath,
                                           boolean deviceDeletionConfirmed) {
        store.replaceAliasesForFileName(source, transferState, transferOffset,
                canonicalLocalPath, deviceDeletionConfirmed);
    }

    boolean ensureFileNameUniqueConstraintOnSerial() {
        return store.ensureFileNameUniqueConstraint();
    }

    /** 更新不需要写入 SQLite 的同步运行时字段；调用者随后选择发布缓存或重读 DB。 */
    void setSyncRuntimeOnSerial(boolean connected, String activeFileName, double rateKBps) {
        connectedToRecorder = connected;
        activeTransferFileName = activeFileName;
        transferRateKBps = Math.max(0.0, rateKBps);
    }

    /** 仅运行时状态变化时复用当前 Entry 列表，避免无意义的 SQLite 查询。 */
    void publishCachedSnapshotOnSerial() {
        publishEntriesOnSerial(snapshot.entries);
    }

    /**
     * 从 SQLite 生成历史快照。condition 在读取完成后校验，防止旧 BLE session 的迟到
     * IO 覆盖当前连接或断开态快照。
     */
    void publishSnapshotOnSerial(boolean includeQueued, BooleanSupplier condition) {
        List<RecordingHistoryStore.Entry> entries = Collections.unmodifiableList(
                store.listForHistoryScreen(includeQueued));
        if (condition != null && !condition.getAsBoolean()) {
            return;
        }
        includeQueuedRecords = includeQueued;
        publishEntriesOnSerial(entries);
    }

    private void publishEntriesOnSerial(List<RecordingHistoryStore.Entry> entries) {
        HistorySnapshot next = new HistorySnapshot(entries, connectedToRecorder,
                activeTransferFileName, transferRateKBps);
        snapshot = next;
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onHistoryChanged(next);
            }
            for (Map.Entry<String, CopyOnWriteArraySet<EntryListener>> subscription
                    : entryListeners.entrySet()) {
                RecordingHistoryStore.Entry entry = next.findEntry(subscription.getKey());
                for (EntryListener listener : subscription.getValue()) {
                    listener.onLoaded(entry);
                }
            }
        });
    }
}
