package com.recorder.client.transcription;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.recorder.client.AppLog;
import com.recorder.client.offline.RecordingHistoryRepository;
import com.recorder.client.offline.RecordingHistoryStore;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 应用级云端全量转写编排器。
 *
 * <p>UI 只向本类提交 enqueue/retry/cancel 意图。任务先以 QUEUED 写入业务数据库，再以
 * file_name 唯一任务名交给 WorkManager；实际上传和轮询全部由
 * {@link CloudTranscriptionWorker} 执行，不依赖任何 Activity 生命周期。
 */
public final class CloudTranscriptionController {

    private static final String TAG = "CloudTranscription";
    private static final String UNIQUE_WORK_PREFIX = "asr:";

    public interface CommandCallback {
        void onAccepted();

        void onRejected(String reason);
    }

    private final RecordingHistoryRepository history;
    private final WorkManager workManager;

    public CloudTranscriptionController(Context context, RecordingHistoryRepository history) {
        Context appContext = context.getApplicationContext();
        this.history = history;
        this.workManager = WorkManager.getInstance(appContext);
        recoverPersistedJobs();
    }

    public void enqueue(String fileName, File audioFile, CommandCallback callback) {
        if (fileName == null || fileName.isEmpty()) {
            reject(callback, "录音标识为空");
            return;
        }
        if (audioFile == null || !audioFile.isFile()) {
            reject(callback, "本地录音文件不存在");
            return;
        }
        UUID workId = UUID.randomUUID();
        history.enqueueCloudTranscription(fileName, audioFile.getAbsolutePath(),
                audioFile.length(), audioFile.lastModified(), workId.toString(), result -> {
                    if (result.rejectionReason != null) {
                        reject(callback, result.rejectionReason);
                        return;
                    }
                    if (result.accepted) {
                        enqueueWork(fileName, result.generation, workId,
                                ExistingWorkPolicy.KEEP);
                    }
                    // 已有活动任务或成功结果也属于幂等成功，不创建第二个云端任务。
                    if (callback != null) {
                        callback.onAccepted();
                    }
                });
    }

    public void retry(String fileName, File audioFile, CommandCallback callback) {
        enqueue(fileName, audioFile, callback);
    }

    public void cancel(String fileName) {
        if (fileName == null) {
            return;
        }
        history.cancelCloudTranscription(fileName, canceled -> {
            if (canceled != null) {
                workManager.cancelUniqueWork(uniqueWorkName(fileName));
            }
        });
    }

    /** DB 已落 QUEUED 但进程在 enqueueWork 前退出时，冷启动由这里补调度。 */
    private void recoverPersistedJobs() {
        history.loadRecoverableAsrJobs(jobs -> {
            for (RecordingHistoryStore.AsrJob job : jobs) {
                // 不复用旧 WorkSpec UUID：已完成但未及时回写业务 DB 的 WorkSpec 仍可能
                // 保留在 WorkManager 数据库。追加一个恢复节点时，前序若仍在执行则新节点
                // 最终按 generation 幂等退出；前序已终止则新节点接手 checkpoint。
                enqueueWork(job.fileName, job.generation, UUID.randomUUID(),
                        ExistingWorkPolicy.APPEND_OR_REPLACE);
            }
        });
    }

    private void enqueueWork(String fileName, long generation, UUID workId,
                             ExistingWorkPolicy policy) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        Data input = new Data.Builder()
                .putString(CloudTranscriptionWorker.KEY_FILE_NAME, fileName)
                .putLong(CloudTranscriptionWorker.KEY_GENERATION, generation)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                CloudTranscriptionWorker.class)
                .setId(workId)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .addTag(uniqueWorkName(fileName))
                .build();
        workManager.enqueueUniqueWork(uniqueWorkName(fileName), policy, request);
        AppLog.i(TAG, "已调度云端转写：" + fileName + " generation=" + generation);
    }

    private static String uniqueWorkName(String fileName) {
        return UNIQUE_WORK_PREFIX + fileName;
    }

    private static void reject(CommandCallback callback, String reason) {
        if (callback != null) {
            callback.onRejected(reason);
        }
    }
}
