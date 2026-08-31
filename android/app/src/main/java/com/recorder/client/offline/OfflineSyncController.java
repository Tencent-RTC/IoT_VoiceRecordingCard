package com.recorder.client.offline;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.recorder.business.AppRecorderSession;
import com.recorder.client.AppLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Android 侧离线同步编排器。
 *
 * <p>协议状态仍由纯 Java {@link AppRecorderSession} 维护；本类仅负责将其事件
 * 异步持久化至 SQLite / .part 文件，做全文件校验及 Ogg 封装。历史数据访问、
 * 串行任务队列与快照发布统一委托给 {@link RecordingHistoryRepository}；本类只保留
 * BLE 文件同步和本地文件处理编排，绝不阻塞 transport-worker。
 */
public final class OfflineSyncController {

    private static final String TAG = "OfflineSync";

    /** 本地历史删除结果始终在主线程回调。 */
    public interface LocalHistoryDeletionListener {
        void onDeleted();

        void onFailed(String reason);
    }

    private static final int MAX_CHUNK_PAYLOAD_BYTES = 4096;
    /** 固定本地命名空间，不表达设备身份；file_name UUID 才是录音记录的唯一身份。 */
    private static final String HISTORY_NAMESPACE = RecordingHistoryStore.HISTORY_NAMESPACE;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RecordingHistoryRepository history;
    private final File rawRootDir;
    private final File oggRootDir;

    private volatile AppRecorderSession session;
    /** 每次连接均创建新 token，绝不让旧连接的异步任务驱动新会话。 */
    private volatile SessionToken activeToken;
    private long nextSessionGeneration;
    private volatile Set<String> remoteFileNames = Collections.emptySet();
    private volatile AppRecorderSession.FileState fileState = AppRecorderSession.FileState.FILE_IDLE;
    private volatile double rateKBps;
    private volatile ActiveWrite activeWrite;
    private volatile boolean lastListHadFiles;
    /** 旧版按 BLE 地址分目录的缓存是否已归并到单设备命名空间。仅在 IO 线程访问。 */
    private boolean legacyHistoryMigrated;

    private long rateWindowStartedMs;
    private long rateWindowBytes;

    private static final class SessionToken {
        final AppRecorderSession session;
        final long generation;

        SessionToken(AppRecorderSession session, long generation) {
            this.session = session;
            this.generation = generation;
        }
    }

    private static final class ActiveWrite {
        final SessionToken owner;
        final AppRecorderSession.DownloadInfo info;
        final File partFile;
        final File oggFile;
        long expectedOffset;
        boolean failed;

        ActiveWrite(SessionToken owner, AppRecorderSession.DownloadInfo info,
                    File partFile, File oggFile) {
            this.owner = owner;
            this.info = info;
            this.partFile = partFile;
            this.oggFile = oggFile;
            this.expectedOffset = info.startOffset;
        }
    }

    public OfflineSyncController(Context context, RecordingHistoryRepository history) {
        Context app = context.getApplicationContext();
        this.history = history;
        File base = app.getExternalFilesDir(null);
        if (base == null) {
            base = app.getFilesDir();
        }
        rawRootDir = new File(base, "offline-recordings/raw");
        oggRootDir = new File(base, "offline-recordings/ogg");
        // 不等待 BLE 建链：冷启动立即迁移旧缓存并发布本地可见历史。
        history.executeSerial(this::publishLocalHistoryOnIo);
    }

    /** 绑定当前唯一录音设备的连接；BLE 地址不会参与历史记录或缓存身份。 */
    public synchronized void attachSession(AppRecorderSession session) {
        SessionToken token = new SessionToken(session, ++nextSessionGeneration);
        this.session = session;
        activeToken = token;
        activeWrite = null;
        remoteFileNames = Collections.emptySet();
        lastListHadFiles = false;
        fileState = AppRecorderSession.FileState.FILE_IDLE;
        history.executeSerial(() -> {
            if (!isCurrent(token)) {
                return;
            }
            updateRepositoryRuntimeOnSerial();
            publishSnapshotOnIo(token);
        });
    }

    public synchronized void detachSession(AppRecorderSession session) {
        if (this.session == session) {
            activeToken = null;
            this.session = null;
            remoteFileNames = Collections.emptySet();
            lastListHadFiles = false;
            fileState = AppRecorderSession.FileState.FILE_IDLE;
            // 断开后首页只保留本地完成记录与可恢复断点，不显示待传输的远端记录。
            history.executeSerial(this::publishLocalHistoryOnIo);
        }
        // 已排队的 IO 仍会安全落盘到单设备命名空间；但旧 token 已失效，绝不会
        // 再向随后连接的新会话发 Request。
        activeWrite = null;
    }

    /**
     * 永久删除手机上的已同步历史记录和音频缓存。
     *
     * <p>这完全是本地操作：不会发送 {@code FileDownloadCompleteRequest}，也不会
     * 改变设备端任何文件。只有设备端源文件删除已确认、且当前没有活动转写任务的
     * 记录才会通过持久化条件校验；因此即使 UI 使用了旧快照，也不会误删正在同步
     * 或正在被 Worker 读取的条目。
     */
    public void deleteCompletedLocalHistory(RecordingHistoryStore.Entry entry,
                                            LocalHistoryDeletionListener callback) {
        if (entry == null) {
            postLocalHistoryDeletionFailed(callback, "找不到要删除的本地历史记录");
            return;
        }
        final String fileName = entry.fileName;
        history.executeSerial(() -> deleteCompletedLocalHistoryOnIo(fileName, callback));
    }

    /** 建链完成或 STOPPING_RECORDING 完成后由上层调用。 */
    public void refreshFromDevice() {
        SessionToken token = activeToken;
        if (isCurrent(token)) {
            token.session.requestFileList();
        }
    }

    // ==================== AppRecorderSession.Listener 转发入口 ====================

    public void onFileStateChanged(AppRecorderSession owner, AppRecorderSession.FileState state) {
        if (!isCurrent(tokenFor(owner))) {
            return;
        }
        fileState = state;
        SessionToken token = tokenFor(owner);
        history.executeSerial(() -> {
            if (!isCurrent(token)) {
                return;
            }
            updateRepositoryRuntimeOnSerial();
            history.publishCachedSnapshotOnSerial();
        });
    }

    /** 主业务连接态变化后，发布最新同步运行时快照以便列表选择活动传输项。 */
    public void onMainStateChanged(AppRecorderSession owner,
                                   AppRecorderSession.MainState state) {
        SessionToken token = tokenFor(owner);
        if (token == null) {
            return;
        }
        history.executeSerial(() -> {
            if (!isCurrent(token)) {
                return;
            }
            updateRepositoryRuntimeOnSerial();
            history.publishCachedSnapshotOnSerial();
        });
    }

    public void onFileList(AppRecorderSession owner, List<AppRecorderSession.RemoteFile> files) {
        SessionToken token = tokenFor(owner);
        if (token == null) {
            return;
        }
        List<AppRecorderSession.RemoteFile> copy = new ArrayList<>(files);
        Set<String> names = new HashSet<>();
        for (AppRecorderSession.RemoteFile file : copy) {
            names.add(file.fileName);
        }
        remoteFileNames = Collections.unmodifiableSet(names);
        lastListHadFiles = !copy.isEmpty();
        history.executeSerial(() -> {
            if (!isCurrent(token)) {
                return;
            }
            migrateLegacyHistoryOnIo();
            history.mergeRemoteOnSerial(copy);
            if (copy.isEmpty()) {
                // v2 迁移记录没有保存 complete Response。只有当前设备已完成所有
                // 分批列表对账并明确返回空列表后，才可安全解锁这些旧本地副本。
                history.confirmLegacyDeviceDeletionsOnSerial();
            }
            publishSnapshotOnIo(token);
            driveNextOnIo(token);
        });
    }

    public void onFileDownloadStarted(AppRecorderSession owner,
                                      AppRecorderSession.DownloadInfo info) {
        SessionToken token = tokenFor(owner);
        if (token == null) {
            return;
        }
        ActiveWrite write = new ActiveWrite(token, info, partFile(token, info.fileName),
                oggFile(token, info.fileName));
        activeWrite = write;
        history.executeSerial(() -> prepareDownloadOnIo(write));
    }

    public void onFileChunk(AppRecorderSession owner, AppRecorderSession.DownloadInfo info,
                            long offset,
                            boolean isEnd, byte[] payload) {
        ActiveWrite write = activeWrite;
        if (write == null || write.owner.session != owner
                || write.info.transferId != info.transferId) {
            AppLog.w(TAG, "忽略已失效下载事务的 File Chunk：" + info.fileName);
            return;
        }
        byte[] copy = payload.clone();
        history.executeSerial(() -> appendChunkOnIo(write, info, offset, isEnd, copy));
    }

    public void onFileReadyForVerification(AppRecorderSession owner,
                                           AppRecorderSession.DownloadInfo info) {
        SessionToken token = tokenFor(owner);
        if (token == null) {
            return;
        }
        ActiveWrite write = activeWrite;
        if (write != null && (write.owner != token || write.info.transferId != info.transferId)) {
            write = null;
        }
        ActiveWrite finalWrite = write;
        history.executeSerial(() -> verifyAndConvertOnIo(finalWrite, token, info));
    }

    public void onFileDownloadPaused(AppRecorderSession owner,
                                     AppRecorderSession.DownloadInfo info, long nextOffset) {
        SessionToken token = tokenFor(owner);
        if (token == null) {
            return;
        }
        ActiveWrite write = activeWrite;
        // 如果最后一个已提交 chunk 恰好在 pause Response 前到达，会话随后会
        // 进入 FILE_VERIFYING；此时保留写入上下文供校验任务使用。
        if (nextOffset != info.fileSize && write != null && write.owner == token
                && activeWrite == write) {
            activeWrite = null;
        }
        history.executeSerial(() -> {
            if (write != null && write.owner == token && write.info.transferId == info.transferId) {
                long durableOffset = write.partFile.exists() ? write.partFile.length() : 0L;
                history.updateProgressOnSerial(info.fileName, durableOffset);
                AppLog.i(TAG, "离线下载已暂停：" + info.fileName + "，本地连续进度 "
                        + durableOffset + " B（协议观察值 " + nextOffset + " B）");
                publishSnapshotOnIo(token);
            }
        });
    }

    public void onFileDownloadCompleted(AppRecorderSession owner, String fileName) {
        SessionToken token = tokenFor(owner);
        if (token == null) {
            return;
        }
        remoteFileNames = without(remoteFileNames, fileName);
        ActiveWrite write = activeWrite;
        if (write != null && write.owner == token && fileName.equals(write.info.fileName)) {
            activeWrite = null;
        } else {
            write = null;
        }
        ActiveWrite finalWrite = write;
        history.executeSerial(() -> {
            // 这个回调只会在 FileDownloadCompleteRequest 得到成功 Response 后触发；
            // 此标记也是首页开放“删除本地副本”入口的唯一依据。
            history.markDeviceDeletionConfirmedOnSerial(fileName);
            if (finalWrite != null && finalWrite.partFile.exists()) {
                // .ogg 已验证成功，原始传输缓存不再需要；删除失败不影响已完成记录。
                if (!finalWrite.partFile.delete()) {
                    AppLog.w(TAG, "已完成文件的原始 .part 清理失败："
                            + finalWrite.partFile);
                }
            }
            publishSnapshotOnIo(token);
            driveNextOnIo(token);
        });
    }

    // ==================== 串行磁盘 IO ====================

    private void prepareDownloadOnIo(ActiveWrite write) {
        try {
            ensureDirectories(write);
            long durableOffset = write.partFile.exists() ? write.partFile.length() : 0L;
            if (durableOffset != write.info.startOffset) {
                throw new IOException("断点文件长度与 download.start offset 不一致: "
                        + durableOffset + " != " + write.info.startOffset);
            }
            if (!write.partFile.exists() && !write.partFile.createNewFile()) {
                throw new IOException("无法创建临时文件");
            }
            history.beginDownloadOnSerial(write.info);
            write.expectedOffset = durableOffset;
            resetRateMeter();
            AppLog.i(TAG, "开始离线下载：" + write.info.fileName + "，"
                    + write.info.fileSize + " B，从 " + durableOffset + " B 继续");
            publishSnapshotOnIo(write.owner);
        } catch (IOException e) {
            failLocalTransferOnIo(write, "准备离线文件失败: " + e.getMessage());
        }
    }

    private void appendChunkOnIo(ActiveWrite write, AppRecorderSession.DownloadInfo info,
                                 long offset, boolean isEnd, byte[] payload) {
        if (write == null || write.info.transferId != info.transferId) {
            AppLog.w(TAG, "忽略已失效下载事务的本地写入：" + info.fileName);
            return;
        }
        if (write.failed) {
            return;
        }
        if (write.expectedOffset != offset) {
            failLocalTransferOnIo(write, "本地写入 offset 不连续: expected="
                    + write.expectedOffset + ", actual=" + offset);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(write.partFile, true)) {
            out.write(payload);
            out.getFD().sync(); // offset 只在字节实际持久化后才写入 SQLite。
            write.expectedOffset += payload.length;
            history.updateProgressOnSerial(info.fileName, write.expectedOffset);
            updateRateMeter(payload.length);
            if (isEnd && write.expectedOffset != info.fileSize) {
                throw new IOException("末块后文件大小不匹配");
            }
            publishSnapshotOnIo(write.owner);
        } catch (IOException e) {
            failLocalTransferOnIo(write, "写入离线文件失败: " + e.getMessage());
        }
    }

    private void verifyAndConvertOnIo(ActiveWrite write, SessionToken owner,
                                      AppRecorderSession.DownloadInfo info) {
        if (write == null || write.owner != owner || write.failed
                || write.info.transferId != info.transferId) {
            failVerificationOnMain(owner, info, "本地下载上下文已丢失");
            return;
        }
        try {
            long size = write.partFile.length();
            long crc = crc32(write.partFile);
            if (size != info.fileSize || crc != info.crc32) {
                throw new IOException("大小或 CRC-32 校验失败: size=" + size + "/"
                        + info.fileSize + ", crc=" + crc + "/" + info.crc32);
            }
            OggOpusMuxer.mux(write.partFile, write.oggFile, info.codecConfig,
                    info.sampleRateHz, info.channelCount);
            history.markTransferredOnSerial(info.fileName, write.oggFile.getAbsolutePath());
            publishSnapshotOnIo(owner);
            AppLog.i(TAG, "离线文件校验并封装成功：" + write.oggFile.getAbsolutePath());
            main.post(() -> {
                if (isCurrent(owner)) {
                    AppRecorderSession current = owner.session;
                    if (current.fileState() == AppRecorderSession.FileState.FILE_VERIFYING
                            && sameTransfer(current.activeDownload(), info)) {
                        current.finishFileVerification(true);
                    } else if (current.fileState() == AppRecorderSession.FileState.FILE_IDLE
                            && current.state() == AppRecorderSession.MainState.READY) {
                        // 进程在最后一块持久化后退出时，本连接没有 download
                        // 事务可收尾；本地重验成功后直接补发自动 complete。若实时
                        // 录音已抢占，则等 STOPPING_RECORDING 完成后的
                        // 下一次列表对账再补发。
                        current.completeFileDownload(info.fileName);
                    }
                }
            });
        } catch (IOException e) {
            write.failed = true;
            if (activeWrite == write) {
                activeWrite = null;
            }
            if (write.partFile.exists() && !write.partFile.delete()) {
                AppLog.w(TAG, "校验失败后的 .part 清理失败：" + write.partFile);
            }
            history.resetTransferOnSerial(info.fileName);
            publishSnapshotOnIo(owner);
            failVerificationOnMain(owner, info, e.getMessage());
        }
    }

    private void failVerificationOnMain(SessionToken owner,
                                        AppRecorderSession.DownloadInfo info, String reason) {
        AppLog.w(TAG, "离线文件校验失败（不会断开链路）：" + info.fileName + "，" + reason);
        main.post(() -> {
            if (isCurrent(owner)) {
                AppRecorderSession current = owner.session;
                if (current.fileState() == AppRecorderSession.FileState.FILE_VERIFYING
                        && sameTransfer(current.activeDownload(), info)) {
                    current.finishFileVerification(false);
                }
            }
        });
    }

    private void failLocalTransferOnIo(ActiveWrite write, String reason) {
        write.failed = true;
        if (activeWrite == write) {
            activeWrite = null;
        }
        AppLog.w(TAG, reason + "，将于下次列表刷新后从安全 offset 重试");
        if (write.partFile.exists() && !write.partFile.delete()) {
            AppLog.w(TAG, "本地失败文件清理失败：" + write.partFile);
        }
        history.resetTransferOnSerial(write.info.fileName);
        publishSnapshotOnIo(write.owner);
        main.post(() -> {
            if (isCurrent(write.owner)) {
                AppRecorderSession current = write.owner.session;
                if (current.fileState() == AppRecorderSession.FileState.FILE_DOWNLOADING
                        && sameTransfer(current.activeDownload(), write.info)) {
                    current.pauseFileDownload();
                }
            }
        });
    }

    private void deleteCompletedLocalHistoryOnIo(String fileName,
                                                   LocalHistoryDeletionListener callback) {
        RecordingHistoryStore.Entry persisted = history.getOnSerial(fileName);
        if (persisted == null) {
            postLocalHistoryDeletionFailed(callback, "本地历史记录已不存在");
            return;
        }
        if (persisted.transferState != RecordingHistoryStore.TransferState.TRANSMITTED
                || !persisted.deviceDeletionConfirmed) {
            postLocalHistoryDeletionFailed(callback, "该录音尚未完成同步，暂不能删除本地副本");
            return;
        }
        if (persisted.transcriptionState.isActive()) {
            postLocalHistoryDeletionFailed(callback, "该录音正在转写，完成后才能删除");
            return;
        }

        // 仅删除受设备命名空间保护的规范路径，绝不把 SQLite 中的字符串直接当作
        // 文件删除目标，避免损坏数据越界影响其他 App 私有文件。
        File ogg = oggFile(HISTORY_NAMESPACE, fileName);
        if (!deleteIfPresent(ogg)) {
            postLocalHistoryDeletionFailed(callback, "无法删除本地音频文件，请稍后重试");
            return;
        }

        // 正常成功路径中 .part 已在 complete 回调中清理；若遗留则尽力移除，但不让
        // 无法播放的临时缓存阻断用户删除正式 Ogg 和历史记录。
        File part = partFile(HISTORY_NAMESPACE, fileName);
        if (part.exists() && !part.delete()) {
            AppLog.w(TAG, "删除本地历史时未能清理遗留 .part：" + part);
        }
        if (history.deleteConfirmedLocalHistoryOnSerial(fileName) != 1) {
            postLocalHistoryDeletionFailed(callback, "本地历史状态已变化，请刷新后重试");
            return;
        }

        AppLog.i(TAG, "已永久删除手机本地历史录音：" + fileName);
        publishSnapshotAfterLocalHistoryDeletionOnIo();
        main.post(() -> {
            if (callback != null) {
                callback.onDeleted();
            }
        });
    }

    private void driveNextOnIo(SessionToken owner) {
        if (!isCurrent(owner)) {
            return;
        }
        AppRecorderSession current = owner.session;
        if (current.state() != AppRecorderSession.MainState.READY
                || current.fileState() != AppRecorderSession.FileState.FILE_IDLE) {
            return;
        }
        RecordingHistoryStore.Entry candidate = null;
        for (RecordingHistoryStore.Entry entry : history.listNewestFirstOnSerial()) {
            if (remoteFileNames.contains(entry.fileName)) {
                candidate = entry;
                break;
            }
        }
        if (candidate == null) {
            if (lastListHadFiles) {
                // 列表有单报文容量上限；本批删除完后重新拉取，直至设备无记录。
                lastListHadFiles = false;
                main.post(() -> {
                    if (isCurrent(owner)) {
                        owner.session.requestFileList();
                    }
                });
            }
            return;
        }
        final RecordingHistoryStore.Entry target = candidate;
        if (target.transferState == RecordingHistoryStore.TransferState.TRANSMITTED) {
            if (!hasUsableOgg(target)) {
                AppLog.w(TAG, "本地 Ogg 缓存不存在或无效，重新下载：" + target.fileName);
                history.resetTransferOnSerial(target.fileName);
                publishSnapshotOnIo(owner);
                driveNextOnIo(owner);
                return;
            }
            main.post(() -> {
                if (isCurrent(owner)) {
                    owner.session.completeFileDownload(target.fileName);
                }
            });
            return;
        }
        File part = partFile(owner, target.fileName);
        long offset = part.exists() ? part.length() : 0L;
        if (target.fileSize <= 0L && offset > 0L) {
            // 崩溃可能恰好发生在 .part 创建后、download.start 元数据入库前；
            // 此时无法证明旧字节属于当前不可变文件，安全做法是从 0 重下。
            if (!part.delete()) {
                AppLog.w(TAG, "无法清理无元数据的 .part，稍后重试：" + part);
                return;
            }
            offset = 0L;
            history.resetTransferOnSerial(target.fileName);
            publishSnapshotOnIo(owner);
        }
        if (target.fileSize > 0L && offset >= target.fileSize) {
            // 进程在所有字节落盘后崩溃：先用已保存的元数据做本地验证，不能发
            // offset == file_size 的非法 download.start。
            AppRecorderSession.DownloadInfo info = new AppRecorderSession.DownloadInfo(
                    target.fileName, 0L, offset, target.fileSize, 0, target.crc32,
                    target.sampleRateHz, target.channelCount, target.codecConfig);
            ActiveWrite write = new ActiveWrite(owner, info, part, oggFile(owner, target.fileName));
            activeWrite = write;
            verifyAndConvertOnIo(write, owner, info);
            return;
        }
        if (offset != target.transferOffset) {
            history.updateProgressOnSerial(target.fileName, offset);
            publishSnapshotOnIo(owner);
        }
        final long resumeOffset = offset;
        main.post(() -> {
            if (isCurrent(owner)) {
                owner.session.startFileDownload(target.fileName, resumeOffset,
                        MAX_CHUNK_PAYLOAD_BYTES);
            }
        });
    }

    /**
     * 一次性把旧版本按 BLE RPA 地址分散的行和缓存归并到单设备命名空间。
     *
     * <p>{@code file_name} 是后端约定的不可变 UUID，因此同名行必然是同一录音。对于
     * 多个旧断点，只迁移实际存在且连续长度与 SQLite 一致的最长 .part；绝不拼接两份
     * 缓存。若已存在有效 Ogg，则它优先于不完整断点。磁盘迁移成功后才原子替换数据库
     * 行；若进程恰好在移动文件后退出，下一次启动也会识别规范目录中的缓存并完成归并，
     * 避免丢失本地数据。
     */
    private void migrateLegacyHistoryOnIo() {
        if (legacyHistoryMigrated) {
            return;
        }
        Map<String, List<RecordingHistoryStore.Entry>> aliasesByFileName =
                new LinkedHashMap<>();
        for (RecordingHistoryStore.Entry entry : history.listAllNewestFirstOnSerial()) {
            List<RecordingHistoryStore.Entry> aliases = aliasesByFileName.get(entry.fileName);
            if (aliases == null) {
                aliases = new ArrayList<>();
                aliasesByFileName.put(entry.fileName, aliases);
            }
            aliases.add(entry);
        }
        boolean complete = true;
        for (List<RecordingHistoryStore.Entry> aliases : aliasesByFileName.values()) {
            if (!migrateLegacyFileAliasesOnIo(aliases)) {
                complete = false;
            }
        }
        if (complete && !history.ensureFileNameUniqueConstraintOnSerial()) {
            AppLog.w(TAG, "暂不能建立 file_name 唯一约束，将在下次启动重试");
            complete = false;
        }
        legacyHistoryMigrated = complete;
    }

    private boolean migrateLegacyFileAliasesOnIo(List<RecordingHistoryStore.Entry> aliases) {
        if (aliases.isEmpty()) {
            return true;
        }
        if (aliases.size() == 1 && HISTORY_NAMESPACE.equals(aliases.get(0).legacyNamespace)) {
            return true;
        }

        RecordingHistoryStore.Entry complete = null;
        File completeCache = null;
        for (RecordingHistoryStore.Entry entry : aliases) {
            if (entry.transferState != RecordingHistoryStore.TransferState.TRANSMITTED) {
                continue;
            }
            File candidate = findUsableOggForMigration(entry);
            if (candidate != null && (complete == null || (!complete.deviceDeletionConfirmed
                    && entry.deviceDeletionConfirmed))) {
                complete = entry;
                completeCache = candidate;
            }
        }
        if (complete != null) {
            File target = oggFile(HISTORY_NAMESPACE, complete.fileName);
            if (!relocateLegacyCache(completeCache, target)) {
                AppLog.w(TAG, "无法迁移旧本地 Ogg，稍后重试：" + complete.fileName);
                return false;
            }
            history.replaceAliasesForFileNameOnSerial(complete,
                    RecordingHistoryStore.TransferState.TRANSMITTED, 0L,
                    target.getAbsolutePath(), complete.deviceDeletionConfirmed);
            return true;
        }

        RecordingHistoryStore.Entry longestPart = null;
        File longestPartCache = null;
        long longestOffset = -1L;
        RecordingHistoryStore.Entry zeroOffsetTransfer = null;
        for (RecordingHistoryStore.Entry entry : aliases) {
            if (entry.transferState != RecordingHistoryStore.TransferState.TRANSMITTING) {
                continue;
            }
            if (entry.transferOffset == 0L
                    && (zeroOffsetTransfer == null
                    || entry.fileSize > zeroOffsetTransfer.fileSize)) {
                // 进程可能正好在 download.start 落库后退出：此时 .part 还没有
                // 数据（甚至尚未创建），但它仍是应在冷启动首页显示的可恢复传输。
                zeroOffsetTransfer = entry;
            }
            File legacyPart = partFile(entry.legacyNamespace, entry.fileName);
            File canonicalPart = partFile(HISTORY_NAMESPACE, entry.fileName);
            File[] candidates = legacyPart.getAbsolutePath().equals(canonicalPart.getAbsolutePath())
                    ? new File[]{legacyPart} : new File[]{legacyPart, canonicalPart};
            for (File part : candidates) {
                long length = part.isFile() ? part.length() : -1L;
                boolean contiguous = length > 0L && length == entry.transferOffset
                        && (entry.fileSize <= 0L || length <= entry.fileSize);
                if (contiguous && length > longestOffset) {
                    longestPart = entry;
                    longestPartCache = part;
                    longestOffset = length;
                }
            }
        }
        if (longestPart != null) {
            File target = partFile(HISTORY_NAMESPACE, longestPart.fileName);
            if (!relocateLegacyCache(longestPartCache, target)) {
                AppLog.w(TAG, "无法迁移旧断点文件，稍后重试：" + longestPart.fileName);
                return false;
            }
            history.replaceAliasesForFileNameOnSerial(longestPart,
                    RecordingHistoryStore.TransferState.TRANSMITTING, target.length(),
                    null, false);
            return true;
        }

        if (zeroOffsetTransfer != null) {
            history.replaceAliasesForFileNameOnSerial(zeroOffsetTransfer,
                    RecordingHistoryStore.TransferState.TRANSMITTING, 0L, null, false);
            return true;
        }

        // 不存在可验证的本地缓存时，保留一条元数据记录并从 0 安全重下。
        RecordingHistoryStore.Entry fallback = selectBestLegacyMetadata(aliases);
        history.replaceAliasesForFileNameOnSerial(fallback,
                RecordingHistoryStore.TransferState.NOT_TRANSMITTED, 0L, null, false);
        return true;
    }

    /**
     * 兼容磁盘先移动、SQLite 尚未来得及提交时的重启：此时旧行仍指向旧目录，
     * 但规范目录中已经存在有效 Ogg。
     */
    private File findUsableOggForMigration(RecordingHistoryStore.Entry entry) {
        if (entry.localPath != null) {
            File original = new File(entry.localPath);
            if (hasUsableOgg(original)) {
                return original;
            }
        }
        File canonical = oggFile(HISTORY_NAMESPACE, entry.fileName);
        return hasUsableOgg(canonical) ? canonical : null;
    }

    private static RecordingHistoryStore.Entry selectBestLegacyMetadata(
            List<RecordingHistoryStore.Entry> aliases) {
        RecordingHistoryStore.Entry best = aliases.get(0);
        for (int i = 1; i < aliases.size(); i++) {
            RecordingHistoryStore.Entry candidate = aliases.get(i);
            int bestPriority = transferPriority(best.transferState);
            int candidatePriority = transferPriority(candidate.transferState);
            if (candidatePriority > bestPriority
                    || (candidatePriority == bestPriority
                    && candidate.transferOffset > best.transferOffset)) {
                best = candidate;
            }
        }
        return best;
    }

    private static int transferPriority(RecordingHistoryStore.TransferState state) {
        switch (state) {
            case TRANSMITTED:
                return 3;
            case TRANSMITTING:
                return 2;
            case NOT_TRANSMITTED:
            default:
                return 1;
        }
    }

    /** 将同一份缓存移动到单设备目录；跨目录 rename 失败时安全地 copy + fsync。 */
    private static boolean relocateLegacyCache(File source, File target) {
        if (!source.isFile()) {
            return false;
        }
        if (source.getAbsolutePath().equals(target.getAbsolutePath())) {
            return true;
        }
        File parent = target.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            return false;
        }
        if (!target.exists() && source.renameTo(target)) {
            return true;
        }

        File temporary = new File(parent, target.getName() + ".migrating");
        if (temporary.exists() && !temporary.delete()) {
            return false;
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            output.getFD().sync();
        } catch (IOException e) {
            if (temporary.exists() && !temporary.delete()) {
                AppLog.w(TAG, "旧缓存迁移临时文件清理失败：" + temporary);
            }
            return false;
        }
        if (target.exists() && !target.delete()) {
            if (!temporary.delete()) {
                AppLog.w(TAG, "旧缓存迁移临时文件清理失败：" + temporary);
            }
            return false;
        }
        if (!temporary.renameTo(target)) {
            if (!temporary.delete()) {
                AppLog.w(TAG, "旧缓存迁移临时文件清理失败：" + temporary);
            }
            return false;
        }
        if (source.exists() && !source.delete()) {
            AppLog.w(TAG, "旧缓存已复制但原文件清理失败：" + source);
        }
        return true;
    }

    private void publishSnapshotOnIo(SessionToken owner) {
        migrateLegacyHistoryOnIo();
        updateRepositoryRuntimeOnSerial();
        history.publishSnapshotOnSerial(true, () -> isCurrent(owner));
    }

    /** 无连接时展示已完整保存的历史录音和有本地断点的传输中录音。 */
    private void publishLocalHistoryOnIo() {
        SessionToken beforeRead = activeToken;
        if (beforeRead != null) {
            publishSnapshotOnIo(beforeRead);
            return;
        }
        migrateLegacyHistoryOnIo();
        updateRepositoryRuntimeOnSerial();
        history.publishSnapshotOnSerial(false, () -> activeToken == null);
    }

    /** 断开设备后历史列表仍可见，因此本地删除不能依赖活跃 BLE session。 */
    private void publishSnapshotAfterLocalHistoryDeletionOnIo() {
        SessionToken token = activeToken;
        updateRepositoryRuntimeOnSerial();
        history.publishSnapshotOnSerial(token != null,
                () -> token == null ? activeToken == null : isCurrent(token));
    }

    private void postLocalHistoryDeletionFailed(LocalHistoryDeletionListener callback,
                                                 String reason) {
        AppLog.e(TAG, "删除本地历史录音失败：" + reason);
        main.post(() -> {
            if (callback != null) {
                callback.onFailed(reason);
            }
        });
    }

    /** 把控制器的瞬时同步状态合并进 Repository 快照，不写入 SQLite。 */
    private void updateRepositoryRuntimeOnSerial() {
        SessionToken token = activeToken;
        boolean connected = token != null && isCurrent(token)
                && token.session.state() != AppRecorderSession.MainState.DISCONNECTED;
        String activeFileName = null;
        if (connected && fileState == AppRecorderSession.FileState.FILE_DOWNLOADING) {
            AppRecorderSession.DownloadInfo info = token.session.activeDownload();
            if (info != null) {
                activeFileName = info.fileName;
            }
        }
        history.setSyncRuntimeOnSerial(connected, activeFileName, rateKBps);
    }

    private void ensureDirectories(ActiveWrite write) throws IOException {
        File rawDir = write.partFile.getParentFile();
        File oggDir = write.oggFile.getParentFile();
        if (rawDir == null || oggDir == null
                || (!rawDir.exists() && !rawDir.mkdirs())
                || (!oggDir.exists() && !oggDir.mkdirs())) {
            throw new IOException("无法创建离线录音目录");
        }
    }

    private File partFile(SessionToken owner, String fileName) {
        return partFile(HISTORY_NAMESPACE, fileName);
    }

    private File oggFile(SessionToken owner, String fileName) {
        return oggFile(HISTORY_NAMESPACE, fileName);
    }

    private File partFile(String namespace, String fileName) {
        return new File(namespaceDirectory(rawRootDir, namespace), storageKey(fileName)
                + ".part");
    }

    private File oggFile(String namespace, String fileName) {
        return new File(namespaceDirectory(oggRootDir, namespace), storageKey(fileName)
                + ".ogg");
    }

    private static File namespaceDirectory(File root, String namespace) {
        return new File(root, storageKey(namespace));
    }

    /** SHA-256 命名避免不同合法文件名在路径清洗后碰撞。 */
    private static String storageKey(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0x0F, 16));
                hex.append(Character.forDigit(b & 0x0F, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Android/Java 平台保证 SHA-256 存在；保留兜底以满足 checked exception。
            return Integer.toHexString(value.hashCode());
        }
    }

    private SessionToken tokenFor(AppRecorderSession owner) {
        SessionToken token = activeToken;
        return token != null && token.session == owner ? token : null;
    }

    private boolean isCurrent(SessionToken token) {
        return token != null && activeToken == token && session == token.session;
    }

    private static boolean sameTransfer(AppRecorderSession.DownloadInfo left,
                                        AppRecorderSession.DownloadInfo right) {
        return left != null && right != null && left.transferId == right.transferId
                && left.fileName.equals(right.fileName);
    }

    private static boolean hasUsableOgg(RecordingHistoryStore.Entry entry) {
        return entry.localPath != null && hasUsableOgg(new File(entry.localPath));
    }

    private static boolean hasUsableOgg(File file) {
        if (!file.isFile() || file.length() < 4L) {
            return false;
        }
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read() == 'O' && input.read() == 'g'
                    && input.read() == 'g' && input.read() == 'S';
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean deleteIfPresent(File file) {
        return !file.exists() || file.delete();
    }

    private static long crc32(File file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[16 * 1024];
        try (FileInputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    crc.update(buffer, 0, read);
                }
            }
        }
        return crc.getValue();
    }

    private void resetRateMeter() {
        rateWindowStartedMs = System.currentTimeMillis();
        rateWindowBytes = 0L;
        rateKBps = 0.0;
    }

    private void updateRateMeter(int bytes) {
        rateWindowBytes += bytes;
        long elapsed = Math.max(1L, System.currentTimeMillis() - rateWindowStartedMs);
        rateKBps = rateWindowBytes * 1000.0 / elapsed / 1024.0;
    }

    private static Set<String> without(Set<String> existing, String name) {
        Set<String> copy = new HashSet<>(existing);
        copy.remove(name);
        return Collections.unmodifiableSet(copy);
    }
}
