package com.recorder.client.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.recorder.business.AppRecorderSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App 侧历史录音副本。
 *
 * <p>SQLite 是 Android 8 内置组件，适合以不可变的 {@code file_name} UUID 表示一条
 * 录音记录并原子地保存下载 offset、编解码信息和最终本地路径。BLE 广播地址可能轮换，
 * 只能作为旧缓存目录的迁移线索，绝不能决定记录身份。
 * 所有调用均应来自 {@link RecordingHistoryRepository} 的单一 IO 线程。
 */
public final class RecordingHistoryStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "recording_history.db";
    private static final int DB_VERSION = 7;
    private static final String TABLE = "recording_history";
    private static final String ASR_JOB_TABLE = "recording_asr_job";
    private static final String ASR_CHUNK_TABLE = "recording_asr_chunk_task";
    private static final String FILE_NAME_UNIQUE_INDEX = "recording_history_file_name_uq";
    /** 固定本地存储命名空间，不是设备身份；旧地址仅保留给迁移读取。 */
    static final String HISTORY_NAMESPACE = "local-history";
    private static final int DEVICE_DELETION_UNCONFIRMED = 0;
    private static final int DEVICE_DELETION_CONFIRMED = 1;
    /** v2 迁移记录：等待下一轮完整 file.list 对账，尚不能展示本地删除。 */
    private static final int DEVICE_DELETION_LEGACY_PENDING_RECONCILIATION = 2;

    public enum TransferState {
        NOT_TRANSMITTED(0),
        TRANSMITTING(1),
        TRANSMITTED(2);

        final int value;

        TransferState(int value) {
            this.value = value;
        }

        static TransferState fromValue(int value) {
            for (TransferState state : values()) {
                if (state.value == value) {
                    return state;
                }
            }
            return NOT_TRANSMITTED;
        }
    }

    /** 本地存储的 ASR 文本来源状态。 */
    public enum AsrTextState {
        /** 本地没有存储任何 asr 文本。 */
        NONE(0),
        /** 实时录音阶段生成的 asr 文本，可能不完整或错误较多（预留）。 */
        ASR_BY_REALTIME_RECORDING(1),
        /** 调用云服务对完整录音文件进行全量 asr 生成的文本。 */
        ASR_FULLY_BY_CLOUD(2);

        final int value;

        AsrTextState(int value) {
            this.value = value;
        }

        static AsrTextState fromValue(int value) {
            for (AsrTextState state : values()) {
                if (state.value == value) {
                    return state;
                }
            }
            return NONE;
        }
    }

    /** 云端全量转写任务状态；与音频同步状态、文本来源状态相互独立。 */
    public enum TranscriptionState {
        NOT_REQUESTED(0),
        QUEUED(1),
        RUNNING(2),
        RETRY_WAITING(3),
        SUCCEEDED(4),
        FAILED(5),
        CANCELED(6);

        final int value;

        TranscriptionState(int value) {
            this.value = value;
        }

        static TranscriptionState fromValue(int value) {
            for (TranscriptionState state : values()) {
                if (state.value == value) {
                    return state;
                }
            }
            return NOT_REQUESTED;
        }

        public boolean isActive() {
            return this == QUEUED || this == RUNNING || this == RETRY_WAITING;
        }
    }

    /** 当前 file_name 对应的最新转写 generation。 */
    public static final class AsrJob {
        public final String fileName;
        public final long generation;
        public final TranscriptionState state;
        public final String workId;
        public final String audioPath;
        public final long sourceSize;
        public final long sourceModifiedMs;
        public final int retryCount;
        public final String errorMessage;

        AsrJob(String fileName, long generation, TranscriptionState state, String workId,
               String audioPath, long sourceSize, long sourceModifiedMs, int retryCount,
               String errorMessage) {
            this.fileName = fileName;
            this.generation = generation;
            this.state = state;
            this.workId = workId;
            this.audioPath = audioPath;
            this.sourceSize = sourceSize;
            this.sourceModifiedMs = sourceModifiedMs;
            this.retryCount = retryCount;
            this.errorMessage = errorMessage;
        }
    }

    /** Worker 创建任务前/后持久化的单片 checkpoint。 */
    public static final class AsrChunkTask {
        public static final int SUBMITTED = 1;
        public static final int COMPLETED = 2;

        public final int chunkIndex;
        public final String recTaskId;
        public final int state;
        public final String resultText;
        public final String resultDetailJson;
        public final long audioDurationMs;
        public final int maxSpeakerId;

        AsrChunkTask(int chunkIndex, String recTaskId, int state, String resultText,
                     String resultDetailJson, long audioDurationMs, int maxSpeakerId) {
            this.chunkIndex = chunkIndex;
            this.recTaskId = recTaskId;
            this.state = state;
            this.resultText = resultText;
            this.resultDetailJson = resultDetailJson;
            this.audioDurationMs = audioDurationMs;
            this.maxSpeakerId = maxSpeakerId;
        }
    }

    /** 原子 enqueue 的结果；accepted=false 表示已有活动任务或成功结果。 */
    public static final class EnqueueResult {
        public final boolean accepted;
        public final long generation;
        public final String workId;
        public final String rejectionReason;

        EnqueueResult(boolean accepted, long generation, String workId,
                      String rejectionReason) {
            this.accepted = accepted;
            this.generation = generation;
            this.workId = workId;
            this.rejectionReason = rejectionReason;
        }
    }

    /** 首页与同步控制器共用的不可变快照。 */
    public static final class Entry {
        /** 仅用于兼容旧版按 BLE 地址分目录的缓存；不参与当前业务身份。 */
        public final String legacyNamespace;
        public final String fileName;
        /** 用户可编辑的展示名；{@code fileName} 仍是唯一、不可变的业务身份。 */
        public final String recordingName;
        public final long fileSize;
        public final long createdTimeSec;
        public final long durationMs;
        public final long crc32;
        public final long sampleRateHz;
        public final long channelCount;
        public final byte[] codecConfig;
        public final TransferState transferState;
        public final long transferOffset;
        public final String localPath;
        /** 仅在设备端源文件删除已确认后为 true。 */
        public final boolean deviceDeletionConfirmed;
        /** 本地存储的 asr 文本状态。 */
        public final AsrTextState asrTextState;
        /** 本地存储的 asr 文本内容；无文本时为 null。 */
        public final String asrText;
        /** 云端全量 ASR 的说话人分段 JSON（见 cloudasr.AsrResult）；无分段时为 null。 */
        public final String asrDetailJson;
        /** 最新云端全量转写任务状态。 */
        public final TranscriptionState transcriptionState;
        public final long transcriptionGeneration;
        public final String transcriptionError;

        Entry(String legacyNamespace, String fileName, String recordingName, long fileSize,
              long createdTimeSec,
              long durationMs,
              long crc32, long sampleRateHz, long channelCount, byte[] codecConfig,
              TransferState transferState, long transferOffset, String localPath,
              boolean deviceDeletionConfirmed, AsrTextState asrTextState, String asrText,
              String asrDetailJson, TranscriptionState transcriptionState,
              long transcriptionGeneration, String transcriptionError) {
            this.legacyNamespace = legacyNamespace;
            this.fileName = fileName;
            this.recordingName = recordingName;
            this.fileSize = fileSize;
            this.createdTimeSec = createdTimeSec;
            this.durationMs = durationMs;
            this.crc32 = crc32;
            this.sampleRateHz = sampleRateHz;
            this.channelCount = channelCount;
            this.codecConfig = codecConfig == null ? null : codecConfig.clone();
            this.transferState = transferState;
            this.transferOffset = transferOffset;
            this.localPath = localPath;
            this.deviceDeletionConfirmed = deviceDeletionConfirmed;
            this.asrTextState = asrTextState;
            this.asrText = asrText;
            this.asrDetailJson = asrDetailJson;
            this.transcriptionState = transcriptionState;
            this.transcriptionGeneration = transcriptionGeneration;
            this.transcriptionError = transcriptionError;
        }

        public Entry withTransfer(TransferState state, long offset, String path) {
            return new Entry(legacyNamespace, fileName, recordingName, fileSize, createdTimeSec,
                    durationMs, crc32,
                    sampleRateHz, channelCount, codecConfig, state, offset, path,
                    deviceDeletionConfirmed, asrTextState, asrText, asrDetailJson,
                    transcriptionState, transcriptionGeneration, transcriptionError);
        }

        private Entry withTranscription(TranscriptionState state, long generation,
                                        String error) {
            return new Entry(legacyNamespace, fileName, recordingName, fileSize, createdTimeSec,
                    durationMs, crc32,
                    sampleRateHz, channelCount, codecConfig, transferState, transferOffset,
                    localPath, deviceDeletionConfirmed, asrTextState, asrText, asrDetailJson,
                    state, generation, error);
        }
    }

    public RecordingHistoryStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "device_key TEXT NOT NULL,"
                + "file_name TEXT NOT NULL,"
                + "recording_name TEXT NOT NULL,"
                + "file_size INTEGER NOT NULL DEFAULT 0,"
                + "created_time INTEGER NOT NULL DEFAULT 0,"
                + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                + "crc INTEGER NOT NULL DEFAULT 0,"
                + "sample_rate_hz INTEGER NOT NULL DEFAULT 0,"
                + "channel_count INTEGER NOT NULL DEFAULT 0,"
                + "codec_config BLOB,"
                + "transfer_state INTEGER NOT NULL DEFAULT 0,"
                + "transfer_offset INTEGER NOT NULL DEFAULT 0,"
                + "local_path TEXT,"
                + "device_deletion_confirmed INTEGER NOT NULL DEFAULT 0,"
                + "asr_text_state INTEGER NOT NULL DEFAULT 0,"
                + "asr_text TEXT,"
                + "asr_detail_json TEXT,"
                + "PRIMARY KEY(device_key, file_name)"
                + ")");
        db.execSQL("CREATE INDEX recording_history_created_idx ON " + TABLE
                + "(device_key, created_time DESC, file_name DESC)");
        db.execSQL("CREATE UNIQUE INDEX " + FILE_NAME_UNIQUE_INDEX + " ON " + TABLE
                + "(file_name)");
        createAsrTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            // v2 只持久化了本地 Ogg 成功，无法证明已经收到 complete Response。
            // 先标为待对账；等本连接完整 file.list 返回空列表、确认该旧文件不在
            // 设备待同步集合后再开放本地删除，绝不能在升级瞬间直接视作已确认。
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN device_deletion_confirmed INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE " + TABLE + " SET device_deletion_confirmed="
                    + DEVICE_DELETION_LEGACY_PENDING_RECONCILIATION
                    + " WHERE transfer_state=" + TransferState.TRANSMITTED.value);
        }
        if (oldVersion < 4) {
            // v4 引入云端全量 ASR 结果持久化：存量记录一律视为无 asr 文本。
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN asr_text_state INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN asr_text TEXT");
        }
        if (oldVersion < 5) {
            // v5 引入说话人结构化分段持久化；存量记录无分段，按纯文本展示。
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN asr_detail_json TEXT");
        }
        if (oldVersion < 6) {
            createAsrTables(db);
            // v5 已经存在的云端完整文本就是成功任务；迁移后详情页不会退回空态。
            db.execSQL("INSERT OR IGNORE INTO " + ASR_JOB_TABLE + " ("
                    + "file_name,generation,state,work_id,audio_path,source_size,"
                    + "source_modified_ms,retry_count,error_message,created_time_ms,"
                    + "updated_time_ms) SELECT file_name,1,"
                    + TranscriptionState.SUCCEEDED.value
                    + ",NULL,COALESCE(local_path,''),file_size,0,0,NULL,0,0 FROM " + TABLE
                    + " WHERE asr_text_state=" + AsrTextState.ASR_FULLY_BY_CLOUD.value
                    + " AND asr_text IS NOT NULL");
        }
    }

    private static void createAsrTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + ASR_JOB_TABLE + " ("
                + "file_name TEXT NOT NULL PRIMARY KEY,"
                + "generation INTEGER NOT NULL,"
                + "state INTEGER NOT NULL,"
                + "work_id TEXT,"
                + "audio_path TEXT NOT NULL,"
                + "source_size INTEGER NOT NULL,"
                + "source_modified_ms INTEGER NOT NULL,"
                + "retry_count INTEGER NOT NULL DEFAULT 0,"
                + "error_message TEXT,"
                + "created_time_ms INTEGER NOT NULL,"
                + "updated_time_ms INTEGER NOT NULL"
                + ")");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + ASR_CHUNK_TABLE + " ("
                + "file_name TEXT NOT NULL,"
                + "generation INTEGER NOT NULL,"
                + "chunk_index INTEGER NOT NULL,"
                + "rec_task_id TEXT NOT NULL,"
                + "state INTEGER NOT NULL,"
                + "submitted_time_ms INTEGER NOT NULL,"
                + "result_text TEXT,"
                + "result_detail_json TEXT,"
                + "audio_duration_ms INTEGER NOT NULL DEFAULT 0,"
                + "max_speaker_id INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY(file_name,generation,chunk_index)"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS recording_asr_chunk_job_idx ON "
                + ASR_CHUNK_TABLE + "(file_name,generation,chunk_index)");
    }

    /** 合并当前唯一设备的最新列表；已确认删除的完成记录仍保留在手机历史中。 */
    public void mergeRemote(List<AppRecorderSession.RemoteFile> remoteFiles) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (AppRecorderSession.RemoteFile remote : remoteFiles) {
                ContentValues update = new ContentValues();
                update.put("created_time", remote.createdTimeSec);
                update.put("duration_ms", remote.durationMs);
                // 文件仍在设备列表中，说明此前的自动 complete 尚未获得确认，不能
                // 把它作为“仅剩本地副本”的历史项供用户删除。
                update.put("device_deletion_confirmed", DEVICE_DELETION_UNCONFIRMED);
                int changed = db.update(TABLE, update, "device_key=? AND file_name=?",
                        new String[]{HISTORY_NAMESPACE, remote.fileName});
                if (changed == 0) {
                    ContentValues insert = new ContentValues(update);
                    insert.put("device_key", HISTORY_NAMESPACE);
                    insert.put("file_name", remote.fileName);
                    insert.put("recording_name",
                            RecordingNameFormatter.defaultName(remote.createdTimeSec));
                    insert.put("transfer_state", TransferState.NOT_TRANSMITTED.value);
                    insert.put("transfer_offset", 0L);
                    db.insertOrThrow(TABLE, null, insert);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 设备已确认下载格式，持久化它并将记录置为可中断的传输中。 */
    public void beginDownload(AppRecorderSession.DownloadInfo info) {
        ContentValues values = new ContentValues();
        values.put("file_size", info.fileSize);
        values.put("crc", info.crc32);
        values.put("sample_rate_hz", info.sampleRateHz);
        values.put("channel_count", info.channelCount);
        values.put("codec_config", info.codecConfig);
        values.put("transfer_state", TransferState.TRANSMITTING.value);
        values.put("transfer_offset", info.startOffset);
        values.put("device_deletion_confirmed", DEVICE_DELETION_UNCONFIRMED);
        int changed = getWritableDatabase().update(TABLE, values,
                "device_key=? AND file_name=?", new String[]{HISTORY_NAMESPACE, info.fileName});
        if (changed == 0) {
            values.put("device_key", HISTORY_NAMESPACE);
            values.put("file_name", info.fileName);
            values.put("recording_name", RecordingNameFormatter.defaultName(0L));
            values.put("created_time", 0L);
            values.put("duration_ms", 0L);
            getWritableDatabase().insertOrThrow(TABLE, null, values);
        }
    }

    public void updateProgress(String fileName, long offset) {
        ContentValues values = new ContentValues();
        values.put("transfer_state", TransferState.TRANSMITTING.value);
        values.put("transfer_offset", offset);
        getWritableDatabase().update(TABLE, values, "device_key=? AND file_name=?",
                new String[]{HISTORY_NAMESPACE, fileName});
    }

    /** 大小、CRC 或容器转换失败后从 0 重新开始。 */
    public void resetTransfer(String fileName) {
        ContentValues values = new ContentValues();
        values.put("transfer_state", TransferState.NOT_TRANSMITTED.value);
        values.put("transfer_offset", 0L);
        values.putNull("local_path");
        values.put("device_deletion_confirmed", DEVICE_DELETION_UNCONFIRMED);
        getWritableDatabase().update(TABLE, values, "device_key=? AND file_name=?",
                new String[]{HISTORY_NAMESPACE, fileName});
    }

    /** 本地完整 .ogg 已落盘，仍等待设备侧自动删除确认。 */
    public void markTransferred(String fileName, String oggPath) {
        ContentValues values = new ContentValues();
        values.put("transfer_state", TransferState.TRANSMITTED.value);
        values.put("transfer_offset", 0L);
        values.put("local_path", oggPath);
        values.put("device_deletion_confirmed", DEVICE_DELETION_UNCONFIRMED);
        getWritableDatabase().update(TABLE, values, "device_key=? AND file_name=?",
                new String[]{HISTORY_NAMESPACE, fileName});
    }

    /** 设备已成功响应自动 complete；此后才允许用户清理手机本地副本。 */
    public void markDeviceDeletionConfirmed(String fileName) {
        ContentValues values = new ContentValues();
        values.put("device_deletion_confirmed", DEVICE_DELETION_CONFIRMED);
        getWritableDatabase().update(TABLE, values,
                "device_key=? AND file_name=? AND transfer_state=?",
                new String[]{HISTORY_NAMESPACE, fileName,
                        String.valueOf(TransferState.TRANSMITTED.value)});
    }

    /** 更新本地展示名称；不会改动 file_name、音频文件路径或任何同步状态。 */
    public boolean renameRecording(String fileName, String recordingName) {
        String normalized = RecordingNameFormatter.normalizeUserName(recordingName);
        if (normalized == null) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("recording_name", normalized);
        return getWritableDatabase().update(TABLE, values, "device_key=? AND file_name=?",
                new String[]{HISTORY_NAMESPACE, fileName}) == 1;
    }

    /**
     * 永久删除已经完成设备侧自动删除确认的本地历史行。
     *
     * <p>音频文件由调用者先删除；这里的条件保护避免旧 UI 快照误删正在同步、
     * 尚未完成设备侧自动删除或仍有活动转写任务的记录。
     */
    public int deleteConfirmedLocalHistory(String fileName) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            AsrJob job = getAsrJob(db, fileName);
            if (job != null && job.state.isActive()) {
                db.setTransactionSuccessful();
                return 0;
            }
            int deleted = db.delete(TABLE,
                    "device_key=? AND file_name=? AND transfer_state=?"
                            + " AND device_deletion_confirmed=" + DEVICE_DELETION_CONFIRMED,
                    new String[]{HISTORY_NAMESPACE, fileName,
                            String.valueOf(TransferState.TRANSMITTED.value)});
            if (deleted > 0) {
                db.delete(ASR_CHUNK_TABLE, "file_name=?", new String[]{fileName});
                db.delete(ASR_JOB_TABLE, "file_name=?", new String[]{fileName});
            }
            db.setTransactionSuccessful();
            return deleted;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * v2 迁移的已下载记录没有保存 complete Response。完整同步最终返回空列表时，
     * 可据此确认这些未在任何批次中出现的旧记录已不在设备端。
     */
    public void confirmLegacyDeviceDeletionsAfterEmptyRemoteList() {
        ContentValues values = new ContentValues();
        values.put("device_deletion_confirmed", DEVICE_DELETION_CONFIRMED);
        getWritableDatabase().update(TABLE, values,
                "device_key=? AND transfer_state=? AND device_deletion_confirmed=?",
                new String[]{HISTORY_NAMESPACE, String.valueOf(TransferState.TRANSMITTED.value),
                        String.valueOf(DEVICE_DELETION_LEGACY_PENDING_RECONCILIATION)});
    }

    public Entry get(String fileName) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, null,
                "device_key=? AND file_name=?", new String[]{HISTORY_NAMESPACE, fileName},
                null, null, null)) {
            return cursor.moveToFirst() ? attachAsrJob(read(cursor), getAsrJob(fileName)) : null;
        }
    }

    // ==================== 云端全量转写任务 ====================

    /** 先落库 QUEUED，再由 Controller 提交 WorkManager；同一录音活动任务天然防重。 */
    public EnqueueResult enqueueAsrJob(String fileName, String audioPath, long sourceSize,
                                       long sourceModifiedMs, String workId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Entry entry;
            try (Cursor cursor = db.query(TABLE, null, "device_key=? AND file_name=?",
                    new String[]{HISTORY_NAMESPACE, fileName}, null, null, null)) {
                entry = cursor.moveToFirst() ? read(cursor) : null;
            }
            if (entry == null || entry.transferState != TransferState.TRANSMITTED) {
                return new EnqueueResult(false, 0L, null, "录音尚未完整同步到本地");
            }
            AsrJob existing = getAsrJob(db, fileName);
            if (existing != null && (existing.state.isActive()
                    || existing.state == TranscriptionState.SUCCEEDED)) {
                db.setTransactionSuccessful();
                return new EnqueueResult(false, existing.generation, existing.workId, null);
            }
            long generation = existing == null ? 1L : existing.generation + 1L;
            long now = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("file_name", fileName);
            values.put("generation", generation);
            values.put("state", TranscriptionState.QUEUED.value);
            values.put("work_id", workId);
            values.put("audio_path", audioPath);
            values.put("source_size", sourceSize);
            values.put("source_modified_ms", sourceModifiedMs);
            values.put("retry_count", 0);
            values.putNull("error_message");
            values.put("created_time_ms", now);
            values.put("updated_time_ms", now);
            db.insertWithOnConflict(ASR_JOB_TABLE, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            db.delete(ASR_CHUNK_TABLE, "file_name=?", new String[]{fileName});
            db.setTransactionSuccessful();
            return new EnqueueResult(true, generation, workId, null);
        } finally {
            db.endTransaction();
        }
    }

    public AsrJob getAsrJob(String fileName) {
        return getAsrJob(getReadableDatabase(), fileName);
    }

    public AsrJob getAsrJob(String fileName, long generation) {
        AsrJob job = getAsrJob(fileName);
        return job != null && job.generation == generation ? job : null;
    }

    public List<AsrJob> listRecoverableAsrJobs() {
        List<AsrJob> jobs = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(ASR_JOB_TABLE, null,
                "state IN (?,?,?)", new String[]{
                        String.valueOf(TranscriptionState.QUEUED.value),
                        String.valueOf(TranscriptionState.RUNNING.value),
                        String.valueOf(TranscriptionState.RETRY_WAITING.value)},
                null, null, "updated_time_ms ASC")) {
            while (cursor.moveToNext()) {
                jobs.add(readAsrJob(cursor));
            }
        }
        return jobs;
    }

    /** Worker 对 generation 做 compare-and-set；旧任务无法覆盖新任务。 */
    public boolean claimAsrJob(String fileName, long generation) {
        ContentValues values = new ContentValues();
        values.put("state", TranscriptionState.RUNNING.value);
        values.putNull("error_message");
        values.put("updated_time_ms", System.currentTimeMillis());
        return getWritableDatabase().update(ASR_JOB_TABLE, values,
                "file_name=? AND generation=? AND state IN (?,?,?)", new String[]{fileName,
                        String.valueOf(generation),
                        String.valueOf(TranscriptionState.QUEUED.value),
                        String.valueOf(TranscriptionState.RUNNING.value),
                        String.valueOf(TranscriptionState.RETRY_WAITING.value)}) == 1;
    }

    public boolean markAsrRetryWaiting(String fileName, long generation, int retryCount,
                                       String errorMessage) {
        return updateActiveAsrState(fileName, generation, TranscriptionState.RETRY_WAITING,
                retryCount, errorMessage);
    }

    public boolean markAsrFailed(String fileName, long generation, int retryCount,
                                 String errorMessage) {
        return updateActiveAsrState(fileName, generation, TranscriptionState.FAILED,
                retryCount, errorMessage);
    }

    public AsrJob cancelAsrJob(String fileName) {
        SQLiteDatabase db = getWritableDatabase();
        AsrJob job = getAsrJob(db, fileName);
        if (job == null || !job.state.isActive()) {
            return null;
        }
        ContentValues values = new ContentValues();
        values.put("state", TranscriptionState.CANCELED.value);
        values.put("updated_time_ms", System.currentTimeMillis());
        int changed = db.update(ASR_JOB_TABLE, values,
                "file_name=? AND generation=? AND state IN (?,?,?)", new String[]{fileName,
                        String.valueOf(job.generation),
                        String.valueOf(TranscriptionState.QUEUED.value),
                        String.valueOf(TranscriptionState.RUNNING.value),
                        String.valueOf(TranscriptionState.RETRY_WAITING.value)});
        return changed == 1 ? job : null;
    }

    private boolean updateActiveAsrState(String fileName, long generation,
                                         TranscriptionState state, int retryCount,
                                         String errorMessage) {
        ContentValues values = new ContentValues();
        values.put("state", state.value);
        values.put("retry_count", retryCount);
        if (errorMessage == null) {
            values.putNull("error_message");
        } else {
            values.put("error_message", errorMessage);
        }
        values.put("updated_time_ms", System.currentTimeMillis());
        return getWritableDatabase().update(ASR_JOB_TABLE, values,
                "file_name=? AND generation=? AND state IN (?,?,?)", new String[]{fileName,
                        String.valueOf(generation),
                        String.valueOf(TranscriptionState.QUEUED.value),
                        String.valueOf(TranscriptionState.RUNNING.value),
                        String.valueOf(TranscriptionState.RETRY_WAITING.value)}) == 1;
    }

    /** 获得 RecTaskId 后立即 checkpoint；若 generation 已失效则拒绝写入。 */
    public boolean saveAsrChunkSubmitted(String fileName, long generation, int chunkIndex,
                                         String recTaskId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!isCurrentActiveGeneration(db, fileName, generation)) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put("file_name", fileName);
            values.put("generation", generation);
            values.put("chunk_index", chunkIndex);
            values.put("rec_task_id", recTaskId);
            values.put("state", AsrChunkTask.SUBMITTED);
            values.put("submitted_time_ms", System.currentTimeMillis());
            db.insertWithOnConflict(ASR_CHUNK_TABLE, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public boolean saveAsrChunkCompleted(String fileName, long generation, int chunkIndex,
                                         String resultText, String resultDetailJson,
                                         long audioDurationMs, int maxSpeakerId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!isCurrentActiveGeneration(db, fileName, generation)) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put("state", AsrChunkTask.COMPLETED);
            values.put("result_text", resultText);
            if (resultDetailJson == null) {
                values.putNull("result_detail_json");
            } else {
                values.put("result_detail_json", resultDetailJson);
            }
            values.put("audio_duration_ms", audioDurationMs);
            values.put("max_speaker_id", maxSpeakerId);
            int changed = db.update(ASR_CHUNK_TABLE, values,
                    "file_name=? AND generation=? AND chunk_index=?", new String[]{fileName,
                            String.valueOf(generation), String.valueOf(chunkIndex)});
            if (changed != 1) {
                return false;
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public List<AsrChunkTask> listAsrChunks(String fileName, long generation) {
        List<AsrChunkTask> chunks = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(ASR_CHUNK_TABLE, null,
                "file_name=? AND generation=?", new String[]{fileName,
                        String.valueOf(generation)}, null, null, "chunk_index ASC")) {
            while (cursor.moveToNext()) {
                chunks.add(new AsrChunkTask(
                        cursor.getInt(cursor.getColumnIndexOrThrow("chunk_index")),
                        cursor.getString(cursor.getColumnIndexOrThrow("rec_task_id")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("state")),
                        cursor.isNull(cursor.getColumnIndexOrThrow("result_text")) ? null
                                : cursor.getString(cursor.getColumnIndexOrThrow("result_text")),
                        cursor.isNull(cursor.getColumnIndexOrThrow("result_detail_json")) ? null
                                : cursor.getString(cursor.getColumnIndexOrThrow(
                                "result_detail_json")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("audio_duration_ms")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("max_speaker_id"))));
            }
        }
        return chunks;
    }

    /** 最终文本、分段与 SUCCEEDED 在同一事务提交。 */
    public boolean completeAsrJob(String fileName, long generation, String asrText,
                                  String asrDetailJson) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!isCurrentActiveGeneration(db, fileName, generation)) {
                return false;
            }
            ContentValues historyValues = new ContentValues();
            historyValues.put("asr_text_state", AsrTextState.ASR_FULLY_BY_CLOUD.value);
            historyValues.put("asr_text", asrText);
            if (asrDetailJson == null) {
                historyValues.putNull("asr_detail_json");
            } else {
                historyValues.put("asr_detail_json", asrDetailJson);
            }
            int historyChanged = db.update(TABLE, historyValues,
                    "device_key=? AND file_name=?", new String[]{HISTORY_NAMESPACE, fileName});
            if (historyChanged != 1) {
                return false;
            }
            ContentValues jobValues = new ContentValues();
            jobValues.put("state", TranscriptionState.SUCCEEDED.value);
            jobValues.putNull("error_message");
            jobValues.put("updated_time_ms", System.currentTimeMillis());
            int jobChanged = db.update(ASR_JOB_TABLE, jobValues,
                    "file_name=? AND generation=?", new String[]{fileName,
                            String.valueOf(generation)});
            if (jobChanged != 1) {
                return false;
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** 读取全部本地行，供首次启动时将旧 BLE 地址命名空间迁入单设备空间。 */
    public List<Entry> listAllNewestFirst() {
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, null, null,
                null, null, "created_time DESC, file_name DESC, device_key DESC")) {
            while (cursor.moveToNext()) {
                entries.add(read(cursor));
            }
        }
        return attachAsrJobs(entries);
    }

    /**
     * 将同一 file_name 的旧地址别名归并为本地唯一命名空间中的一条记录。
     *
     * <p>调用者已经在磁盘上把获选的缓存文件迁到 {@code canonicalLocalPath}；这里仅
     * 原子地提交 SQLite，避免任何时刻出现两个可见的断点记录。
     */
    public void replaceAliasesForFileName(Entry source, TransferState transferState,
                                          long transferOffset,
                                          String canonicalLocalPath,
                                          boolean deviceDeletionConfirmed) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int sourceDeletionState = sourceDeletionState(db, source);
            db.delete(TABLE, "file_name=?", new String[]{source.fileName});
            ContentValues values = valuesFor(source);
            values.put("device_key", HISTORY_NAMESPACE);
            values.put("transfer_state", transferState.value);
            values.put("transfer_offset", transferOffset);
            if (canonicalLocalPath == null) {
                values.putNull("local_path");
            } else {
                values.put("local_path", canonicalLocalPath);
            }
            if (transferState == TransferState.TRANSMITTED
                    && !deviceDeletionConfirmed
                    && sourceDeletionState == DEVICE_DELETION_LEGACY_PENDING_RECONCILIATION) {
                // 旧版已落盘记录尚待一次完整空列表对账；归并不能把这层保护
                // 降级为普通未确认，否则之后永远不会开放本地删除。
                values.put("device_deletion_confirmed",
                        DEVICE_DELETION_LEGACY_PENDING_RECONCILIATION);
            } else {
                values.put("device_deletion_confirmed", deviceDeletionConfirmed
                        ? DEVICE_DELETION_CONFIRMED : DEVICE_DELETION_UNCONFIRMED);
            }
            db.insertOrThrow(TABLE, null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<Entry> listNewestFirst() {
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, "device_key=?",
                new String[]{HISTORY_NAMESPACE}, null, null,
                "created_time DESC, file_name DESC")) {
            while (cursor.moveToNext()) {
                entries.add(read(cursor));
            }
        }
        return attachAsrJobs(entries);
    }

    /**
     * 首页历史：始终包含所有已经完整保存到手机的录音，以及已持久化断点的传输中
     * 录音；若当前已连接设备，则额外包含尚待同步的记录。这样冷启动无需蓝牙
     * 连接即可展示可播放历史和可恢复进度，同时仍保留当前设备的待同步状态。
     */
    public List<Entry> listForHistoryScreen(boolean includeQueuedRecords) {
        String selection;
        String[] args;
        if (!includeQueuedRecords) {
            selection = "transfer_state IN (?,?)";
            args = new String[]{String.valueOf(TransferState.TRANSMITTED.value),
                    String.valueOf(TransferState.TRANSMITTING.value)};
        } else {
            selection = "transfer_state IN (?,?) OR device_key=?";
            args = new String[]{String.valueOf(TransferState.TRANSMITTED.value),
                    String.valueOf(TransferState.TRANSMITTING.value),
                    HISTORY_NAMESPACE};
        }
        Map<String, Entry> entriesByFileName = new LinkedHashMap<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, selection, args,
                null, null, "created_time DESC, file_name DESC, device_key DESC")) {
            while (cursor.moveToNext()) {
                Entry candidate = read(cursor);
                Entry existing = entriesByFileName.get(candidate.fileName);
                if (existing == null || shouldPrefer(candidate, existing)) {
                    entriesByFileName.put(candidate.fileName, candidate);
                }
            }
        }
        return attachAsrJobs(new ArrayList<>(entriesByFileName.values()));
    }

    /**
     * 当旧地址缓存迁移暂时失败时，页面仍必须遵守 file_name 一条记录的约束。
     * 正常路径会在读取前完成归并；这里是防御性兜底，不依赖 BLE 地址排序。
     */
    private static boolean shouldPrefer(Entry candidate, Entry existing) {
        int candidatePriority = transferPriority(candidate.transferState);
        int existingPriority = transferPriority(existing.transferState);
        if (candidatePriority != existingPriority) {
            return candidatePriority > existingPriority;
        }
        if (candidate.transferOffset != existing.transferOffset) {
            return candidate.transferOffset > existing.transferOffset;
        }
        if (candidate.deviceDeletionConfirmed != existing.deviceDeletionConfirmed) {
            return candidate.deviceDeletionConfirmed;
        }
        return HISTORY_NAMESPACE.equals(candidate.legacyNamespace)
                && !HISTORY_NAMESPACE.equals(existing.legacyNamespace);
    }

    private static int transferPriority(TransferState state) {
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

    private static int sourceDeletionState(SQLiteDatabase db, Entry source) {
        try (Cursor cursor = db.query(TABLE, new String[]{"device_deletion_confirmed"},
                "device_key=? AND file_name=?",
                new String[]{source.legacyNamespace, source.fileName},
                null, null, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : DEVICE_DELETION_UNCONFIRMED;
        }
    }

    /** 在旧别名全部归并后建立 file_name 唯一约束，防止未来重新写出重复记录。 */
    public boolean ensureFileNameUniqueConstraint() {
        try {
            getWritableDatabase().execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + FILE_NAME_UNIQUE_INDEX + " ON " + TABLE + "(file_name)");
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private List<Entry> attachAsrJobs(List<Entry> entries) {
        Map<String, AsrJob> jobs = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().query(ASR_JOB_TABLE, null, null, null,
                null, null, null)) {
            while (cursor.moveToNext()) {
                AsrJob job = readAsrJob(cursor);
                jobs.put(job.fileName, job);
            }
        }
        List<Entry> enriched = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            enriched.add(attachAsrJob(entry, jobs.get(entry.fileName)));
        }
        return enriched;
    }

    private static Entry attachAsrJob(Entry entry, AsrJob job) {
        if (job != null) {
            return entry.withTranscription(job.state, job.generation, job.errorMessage);
        }
        // 防御旧库或人工导入数据：只要完整云端文本存在，UI 就不能显示为未请求。
        if (entry.asrTextState == AsrTextState.ASR_FULLY_BY_CLOUD
                && entry.asrText != null && !entry.asrText.isEmpty()) {
            return entry.withTranscription(TranscriptionState.SUCCEEDED, 0L, null);
        }
        return entry;
    }

    private static AsrJob getAsrJob(SQLiteDatabase db, String fileName) {
        try (Cursor cursor = db.query(ASR_JOB_TABLE, null, "file_name=?",
                new String[]{fileName}, null, null, null)) {
            return cursor.moveToFirst() ? readAsrJob(cursor) : null;
        }
    }

    private static AsrJob readAsrJob(Cursor cursor) {
        return new AsrJob(
                cursor.getString(cursor.getColumnIndexOrThrow("file_name")),
                cursor.getLong(cursor.getColumnIndexOrThrow("generation")),
                TranscriptionState.fromValue(cursor.getInt(
                        cursor.getColumnIndexOrThrow("state"))),
                cursor.isNull(cursor.getColumnIndexOrThrow("work_id")) ? null
                        : cursor.getString(cursor.getColumnIndexOrThrow("work_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                cursor.getLong(cursor.getColumnIndexOrThrow("source_size")),
                cursor.getLong(cursor.getColumnIndexOrThrow("source_modified_ms")),
                cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
                cursor.isNull(cursor.getColumnIndexOrThrow("error_message")) ? null
                        : cursor.getString(cursor.getColumnIndexOrThrow("error_message")));
    }

    private static boolean isCurrentActiveGeneration(SQLiteDatabase db, String fileName,
                                                     long generation) {
        AsrJob job = getAsrJob(db, fileName);
        return job != null && job.generation == generation && job.state.isActive();
    }

    private static Entry read(Cursor cursor) {
        byte[] config = cursor.isNull(cursor.getColumnIndexOrThrow("codec_config"))
                ? null : cursor.getBlob(cursor.getColumnIndexOrThrow("codec_config"));
        return new Entry(
                cursor.getString(cursor.getColumnIndexOrThrow("device_key")),
                cursor.getString(cursor.getColumnIndexOrThrow("file_name")),
                cursor.getString(cursor.getColumnIndexOrThrow("recording_name")),
                cursor.getLong(cursor.getColumnIndexOrThrow("file_size")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_time")),
                cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
                cursor.getLong(cursor.getColumnIndexOrThrow("crc")),
                cursor.getLong(cursor.getColumnIndexOrThrow("sample_rate_hz")),
                cursor.getLong(cursor.getColumnIndexOrThrow("channel_count")),
                config,
                TransferState.fromValue(cursor.getInt(
                        cursor.getColumnIndexOrThrow("transfer_state"))),
                cursor.getLong(cursor.getColumnIndexOrThrow("transfer_offset")),
                cursor.isNull(cursor.getColumnIndexOrThrow("local_path")) ? null
                        : cursor.getString(cursor.getColumnIndexOrThrow("local_path")),
                cursor.getInt(cursor.getColumnIndexOrThrow("device_deletion_confirmed"))
                        == DEVICE_DELETION_CONFIRMED,
                AsrTextState.fromValue(cursor.getInt(
                        cursor.getColumnIndexOrThrow("asr_text_state"))),
                cursor.isNull(cursor.getColumnIndexOrThrow("asr_text")) ? null
                        : cursor.getString(cursor.getColumnIndexOrThrow("asr_text")),
                cursor.isNull(cursor.getColumnIndexOrThrow("asr_detail_json")) ? null
                        : cursor.getString(cursor.getColumnIndexOrThrow("asr_detail_json")),
                TranscriptionState.NOT_REQUESTED, 0L, null);
    }

    private static ContentValues valuesFor(Entry entry) {
        ContentValues values = new ContentValues();
        values.put("file_name", entry.fileName);
        values.put("recording_name", entry.recordingName);
        values.put("file_size", entry.fileSize);
        values.put("created_time", entry.createdTimeSec);
        values.put("duration_ms", entry.durationMs);
        values.put("crc", entry.crc32);
        values.put("sample_rate_hz", entry.sampleRateHz);
        values.put("channel_count", entry.channelCount);
        values.put("codec_config", entry.codecConfig);
        // 旧别名归并走 delete + insert，必须原样保留已落库的云端 ASR 结果。
        values.put("asr_text_state", entry.asrTextState.value);
        values.put("asr_text", entry.asrText);
        values.put("asr_detail_json", entry.asrDetailJson);
        return values;
    }

}
