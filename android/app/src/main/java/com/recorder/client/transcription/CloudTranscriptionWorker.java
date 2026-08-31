package com.recorder.client.transcription;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.recorder.client.AppLog;
import com.recorder.client.MainActivity;
import com.recorder.client.RecordingManager;
import com.recorder.client.cloudasr.AsrResult;
import com.recorder.client.cloudasr.CloudAsrClient;
import com.recorder.client.offline.RecordingHistoryRepository;
import com.recorder.client.offline.RecordingHistoryStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 可持久化云端全量转写执行器：拆片、提交、轮询、checkpoint 与最终事务提交。
 */
public final class CloudTranscriptionWorker extends Worker {

    public static final String KEY_FILE_NAME = "file_name";
    public static final String KEY_GENERATION = "generation";

    private static final String TAG = "CloudAsrWorker";
    private static final String CHANNEL_ID = "cloud_transcription";
    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final long CHUNK_POLL_TIMEOUT_MS = 10L * 60L * 1_000L;
    private static final int MAX_RUN_ATTEMPTS = 5;

    public CloudTranscriptionWorker(@NonNull Context context,
                                    @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String fileName = getInputData().getString(KEY_FILE_NAME);
        long generation = getInputData().getLong(KEY_GENERATION, 0L);
        if (fileName == null || generation <= 0L) {
            return Result.failure();
        }
        setForegroundAsync(foregroundInfo(fileName, 0, 1));

        RecordingManager manager = RecordingManager.get();
        manager.initBackground(getApplicationContext());
        RecordingHistoryRepository history = manager.historyRepository();
        if (history == null || !history.claimAsrJobBlocking(fileName, generation)) {
            // generation 已被 retry/cancel 替换，旧 Worker 正常退出且不得写结果。
            return Result.success();
        }

        try {
            RecordingHistoryStore.AsrJob job = history.getAsrJobBlocking(fileName, generation);
            if (job == null) {
                return Result.success();
            }
            File audioFile = new File(job.audioPath);
            validateSource(audioFile, job);

            CloudAsrClient client = new CloudAsrClient(message -> AppLog.i(TAG, message));
            List<byte[]> chunks = client.prepareChunks(audioFile);
            Map<Integer, RecordingHistoryStore.AsrChunkTask> checkpoints = new HashMap<>();
            for (RecordingHistoryStore.AsrChunkTask checkpoint
                    : history.listAsrChunksBlocking(fileName, generation)) {
                checkpoints.put(checkpoint.chunkIndex, checkpoint);
            }

            List<CloudAsrClient.ChunkResult> outcomes = new ArrayList<>(chunks.size());
            for (int index = 0; index < chunks.size(); index++) {
                if (isStopped()) {
                    return Result.retry();
                }
                setForegroundAsync(foregroundInfo(fileName, index + 1, chunks.size()));
                RecordingHistoryStore.AsrChunkTask checkpoint = checkpoints.get(index);
                CloudAsrClient.ChunkResult outcome;
                if (checkpoint != null
                        && checkpoint.state == RecordingHistoryStore.AsrChunkTask.COMPLETED) {
                    outcome = CloudAsrClient.restoreChunkResult(checkpoint.resultText,
                            checkpoint.resultDetailJson, checkpoint.audioDurationMs,
                            checkpoint.maxSpeakerId);
                } else {
                    String recTaskId = checkpoint == null ? null : checkpoint.recTaskId;
                    if (recTaskId == null || recTaskId.isEmpty()) {
                        recTaskId = client.createTask(chunks.get(index));
                        if (!history.saveAsrChunkSubmittedBlocking(fileName, generation, index,
                                recTaskId)) {
                            return Result.success();
                        }
                    }
                    outcome = pollChunk(client, recTaskId);
                    if (!history.saveAsrChunkCompletedBlocking(fileName, generation, index,
                            outcome.result.plainText, outcome.result.segmentsToJson(),
                            outcome.audioDurationMs, outcome.maxSpeakerId)) {
                        return Result.success();
                    }
                }
                outcomes.add(outcome);
            }

            AsrResult result = CloudAsrClient.mergeChunks(outcomes);
            if (result.plainText.isEmpty()) {
                throw new CloudAsrClient.AsrException("云端未返回任何识别文本");
            }
            if (!history.completeAsrJobBlocking(fileName, generation, result.plainText,
                    result.segmentsToJson())) {
                return Result.success();
            }
            AppLog.i(TAG, "云端转写完成并落库：" + fileName + " generation=" + generation);
            return Result.success();
        } catch (CloudAsrClient.AsrException e) {
            return e.isRetryable() ? retryOrFail(history, fileName, generation, e)
                    : fail(history, fileName, generation, e);
        } catch (IOException | IllegalStateException e) {
            return retryOrFail(history, fileName, generation, e);
        } catch (RuntimeException e) {
            return fail(history, fileName, generation, e);
        }
    }

    private CloudAsrClient.ChunkResult pollChunk(CloudAsrClient client, String recTaskId)
            throws IOException, CloudAsrClient.AsrException {
        long deadline = System.currentTimeMillis() + CHUNK_POLL_TIMEOUT_MS;
        while (true) {
            if (isStopped()) {
                throw new CloudAsrClient.AsrException("转写任务已暂停", true);
            }
            CloudAsrClient.TaskPoll poll = client.queryTask(recTaskId);
            if (poll.completed) {
                return poll.chunkResult;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new CloudAsrClient.AsrException("等待云端识别结果超时", true);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudAsrClient.AsrException("等待云端识别结果被中断", true);
            }
        }
    }

    private static void validateSource(File audioFile, RecordingHistoryStore.AsrJob job)
            throws CloudAsrClient.AsrException {
        if (!audioFile.isFile()) {
            throw new CloudAsrClient.AsrException("本地录音文件不存在");
        }
        if (audioFile.length() != job.sourceSize
                || audioFile.lastModified() != job.sourceModifiedMs) {
            throw new CloudAsrClient.AsrException("本地录音文件在转写请求后发生变化");
        }
    }

    private Result retryOrFail(RecordingHistoryRepository history, String fileName,
                               long generation, Exception error) {
        int attempts = getRunAttemptCount() + 1;
        if (attempts >= MAX_RUN_ATTEMPTS) {
            return fail(history, fileName, generation, error);
        }
        String message = errorMessage(error);
        history.markAsrRetryWaitingBlocking(fileName, generation, attempts, message);
        AppLog.w(TAG, "云端转写等待重试(" + attempts + "): " + message);
        return Result.retry();
    }

    private Result fail(RecordingHistoryRepository history, String fileName, long generation,
                        Exception error) {
        String message = errorMessage(error);
        history.markAsrFailedBlocking(fileName, generation, getRunAttemptCount() + 1, message);
        AppLog.e(TAG, "云端转写失败：" + message, error);
        return Result.failure();
    }

    private ForegroundInfo foregroundInfo(String fileName, int chunk, int totalChunks) {
        Context context = getApplicationContext();
        NotificationManager notifications = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications != null) {
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "录音转写", NotificationManager.IMPORTANCE_LOW));
        }
        Intent openApp = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context,
                notificationId(fileName), openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String progress = chunk <= 0 ? "正在准备录音"
                : "正在转写第 " + chunk + "/" + totalChunks + " 个分片";
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("录音转写中")
                .setContentText(progress)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(notificationId(fileName), notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(notificationId(fileName), notification);
    }

    private static int notificationId(String fileName) {
        return 0x415352 ^ (fileName.hashCode() & 0x00FFFFFF);
    }

    private static String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
