package com.recorder.client.cloudasr;

import com.recorder.client.trtc.TrtcUserSig;
import com.recorder.client.trtc.TrtcUserSigConfig;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 腾讯云录音文件识别（全量 ASR）客户端。
 *
 * <p>封装 {@code CreateRecTask}（本地音频 base64 上传）与
 * {@code DescribeTaskStatus}（轮询取结果）两个接口，协议报文由 Google 官方
 * Gson 库承载（与业务协议的 protobuf 选型一致，不手写 JSON 解析）。
 * 除 Gson 外仅依赖 Java 8 标准库，因此同一份代码既能在本地纯 Java 环境
 * 联调验证，也能直接在 Android 8.0（API 26）以上的 client app 中复用。
 *
 * <p>请求固定开启说话人分离（{@code SpeakerDiarization=1}）并携带
 * 智能断句的详细识别结果（{@code ResTextFormat=3}），因此返回结果包含
 * 每个语句分段的说话人 Id 与起止毫秒时间（见 {@link AsrSegment}）。
 *
 * <p>单个分片超过服务端 5MB（base64 编码后）上限的音频，会先经
 * {@link OggChunkSplitter} 按 Ogg page 边界拆分，逐片提交识别，最终结果
 * 按原始顺序拼接：后一片的时间轴按前片的实际音频时长平移；由于云端
 * 说话人编号每片独立，后一片的说话人 Id 会按前片最大编号偏移，绝不把
 * 不同片中的同编号说话人误认为同一人（跨片说话人归一暂不支持）。
 *
 * <p>所有方法都会执行网络与磁盘 IO，调用方必须在后台线程使用。
 */
public final class CloudAsrClient {

    /** 业务级失败（服务端返回错误、任务失败、结果无法解析等）。 */
    public static final class AsrException extends Exception {
        private final boolean retryable;

        public AsrException(String message) {
            this(message, false);
        }

        public AsrException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    /** 轻量日志端口；本地联调可接 stdout，Android app 调用端接入 AppLog。 */
    public interface Logger {
        void log(String message);
    }

    /** 单片识别结果：分段 + 该片音频实际时长（供后片平移时间轴）。 */
    public static final class ChunkResult {
        public final AsrResult result;
        public final int maxSpeakerId;
        public final long audioDurationMs;

        public ChunkResult(AsrResult result, int maxSpeakerId, long audioDurationMs) {
            this.result = result;
            this.maxSpeakerId = maxSpeakerId;
            this.audioDurationMs = audioDurationMs;
        }
    }

    /** DescribeTaskStatus 的单次查询结果；等待态不包含 chunkResult。 */
    public static final class TaskPoll {
        public final boolean completed;
        public final String statusText;
        public final ChunkResult chunkResult;

        private TaskPoll(boolean completed, String statusText, ChunkResult chunkResult) {
            this.completed = completed;
            this.statusText = statusText;
            this.chunkResult = chunkResult;
        }
    }

    private static final String CREATE_TASK_URL = "https://asr.cloud-rtc.com/v1/CreateRecTask";
    private static final String QUERY_TASK_URL = "https://asr.cloud-rtc.com/v1/DescribeTaskStatus";
    /** 服务端上限 5MB 指 base64 编码后的大小；raw 按 3/4 折算后再留安全余量。 */
    private static final long MAX_CHUNK_RAW_BYTES = 3_500_000L;
    /** UserSig 有效期，沿用 SDK 文档示例的 7 天。 */
    private static final long USER_SIG_EXPIRE_SECONDS = 7L * 24 * 60 * 60;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final long POLL_TIMEOUT_MS = 10L * 60 * 1000;

    private static final int STATUS_WAITING = 0;
    private static final int STATUS_DOING = 1;
    private static final int STATUS_SUCCESS = 2;
    private static final int STATUS_FAILED = 3;

    private static final Gson GSON = new Gson();

    private final Logger logger;

    public CloudAsrClient(Logger logger) {
        this.logger = logger;
    }

    /**
     * 对本地 Ogg Opus 文件执行全量识别（开启说话人分离）。
     *
     * @return 拼接纯文本 + 带说话人与起止时间的有序分段
     * @throws IOException  文件读取或网络传输失败
     * @throws AsrException 服务端拒绝请求或识别任务失败
     */
    public AsrResult transcribe(File audioFile) throws IOException, AsrException {
        List<byte[]> chunks = prepareChunks(audioFile);
        log(String.format(Locale.US, "云端 ASR：%s，%d B，拆分为 %d 片",
                audioFile.getName(), audioFile.length(), chunks.size()));
        List<ChunkResult> outcomes = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            log(String.format(Locale.US, "提交第 %d/%d 片（%d B）",
                    i + 1, chunks.size(), chunks.get(i).length));
            String recTaskId = createTask(chunks.get(i));
            outcomes.add(pollTaskResult(recTaskId, i + 1, chunks.size()));
        }
        return mergeChunks(outcomes);
    }

    public List<byte[]> prepareChunks(File audioFile) throws IOException {
        return OggChunkSplitter.split(readFully(audioFile), MAX_CHUNK_RAW_BYTES);
    }

    // ==================== CreateRecTask ====================

    public String createTask(byte[] oggBytes) throws IOException, AsrException {
        String requestId = UUID.randomUUID().toString();
        JsonObject body = new JsonObject();
        body.addProperty("EngineModelType", "bigmodel");
        body.addProperty("ChannelNum", 1);
        // 3 = 词粒度详情 + 标点 + 智能断句；ResultDetail 才携带说话人与时间戳。
        body.addProperty("ResTextFormat", 3);
        body.addProperty("SourceType", 1);
        // 开启说话人分离（单声道可用）。
        body.addProperty("SpeakerDiarization", 1);
        body.addProperty("Data", Base64.getEncoder().encodeToString(oggBytes));
        body.addProperty("DataLen", oggBytes.length);
        JsonObject response = post(CREATE_TASK_URL, requestId, GSON.toJson(body));
        JsonObject data = objectOrNull(response, "Data");
        String recTaskId = data == null ? null : stringOrNull(data, "RecTaskId");
        if (recTaskId == null || recTaskId.isEmpty()) {
            throw new AsrException("CreateRecTask 响应缺少 RecTaskId: " + response);
        }
        return recTaskId;
    }

    // ==================== DescribeTaskStatus ====================

    /** 单次查询云端任务；Worker 据此自行轮询并在每片完成时 checkpoint。 */
    public TaskPoll queryTask(String recTaskId) throws IOException, AsrException {
        String requestId = UUID.randomUUID().toString();
        JsonObject body = new JsonObject();
        body.addProperty("RecTaskId", recTaskId);
        JsonObject response = post(QUERY_TASK_URL, requestId, GSON.toJson(body));
        JsonObject data = objectOrNull(response, "Data");
        if (data == null) {
            throw new AsrException("DescribeTaskStatus 响应缺少 Data: " + response, true);
        }
        long status = data.has("Status") ? data.get("Status").getAsLong() : -1L;
        if (status == STATUS_SUCCESS) {
            return new TaskPoll(true, stringOrNull(data, "StatusStr"),
                    extractChunkResult(data));
        }
        if (status == STATUS_FAILED) {
            String error = stringOrNull(data, "ErrorMsg");
            throw new AsrException("云端识别任务失败: "
                    + (error == null || error.isEmpty() ? "未知原因" : error));
        }
        if (status != STATUS_WAITING && status != STATUS_DOING) {
            throw new AsrException("云端识别任务返回未知状态: " + status, true);
        }
        return new TaskPoll(false, stringOrNull(data, "StatusStr"), null);
    }

    /** 兼容原有阻塞式本地联调入口。 */
    private ChunkResult pollTaskResult(String recTaskId, int chunkIndex, int chunkCount)
            throws IOException, AsrException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (true) {
            TaskPoll poll = queryTask(recTaskId);
            if (poll.completed) {
                return poll.chunkResult;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AsrException("等待云端识别结果超时", true);
            }
            log(String.format(Locale.US, "第 %d/%d 片识别中（%s），%.1fs 后重试",
                    chunkIndex, chunkCount,
                    poll.statusText, POLL_INTERVAL_MS / 1000.0));
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AsrException("等待识别结果被中断");
            }
        }
    }

    // ==================== 结果提取 ====================

    /**
     * 从任务成功的 Data 对象提取分段：优先 ResultDetail 的 FinalSentence +
     * StartMs/EndMs + SpeakerId；若服务端未返回详情，则退化为单个
     * 「未知说话人（0）、从 0 开始」的分段（剥离 Result 行首时间戳前缀）。
     */
    private static ChunkResult extractChunkResult(JsonObject data) throws AsrException {
        long audioDurationMs = data.has("AudioDuration")
                ? (long) (data.get("AudioDuration").getAsDouble() * 1000.0) : 0L;
        List<AsrSegment> segments = new ArrayList<>();
        int maxSpeakerId = 0;
        JsonElement detailElement = data.get("ResultDetail");
        if (detailElement != null && detailElement.isJsonArray()) {
            JsonArray details = detailElement.getAsJsonArray();
            for (JsonElement item : details) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject sentence = item.getAsJsonObject();
                String text = stringOrNull(sentence, "FinalSentence");
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                int speakerId = sentence.has("SpeakerId")
                        ? sentence.get("SpeakerId").getAsInt() : 0;
                long startMs = sentence.has("StartMs")
                        ? sentence.get("StartMs").getAsLong() : 0L;
                long endMs = sentence.has("EndMs")
                        ? sentence.get("EndMs").getAsLong() : startMs;
                segments.add(new AsrSegment(speakerId, startMs, endMs, text.trim()));
                maxSpeakerId = Math.max(maxSpeakerId, speakerId);
            }
        }
        if (segments.isEmpty()) {
            String result = stringOrNull(data, "Result");
            if (result == null) {
                throw new AsrException("识别结果缺少 Result 字段");
            }
            StringBuilder text = new StringBuilder();
            for (String line : result.split("\n")) {
                text.append(stripLinePrefix(line));
            }
            segments.add(new AsrSegment(0, 0L, audioDurationMs, text.toString()));
        }
        StringBuilder text = new StringBuilder();
        for (AsrSegment segment : segments) {
            appendSegment(text, segment.text);
        }
        return new ChunkResult(new AsrResult(text.toString(), segments), maxSpeakerId,
                audioDurationMs);
    }

    /** 从 SQLite checkpoint 还原已经完成的分片。 */
    public static ChunkResult restoreChunkResult(String text, String detailJson,
                                                 long audioDurationMs, int maxSpeakerId)
            throws AsrException {
        List<AsrSegment> segments = AsrResult.segmentsFromJson(detailJson);
        if (text == null || segments.isEmpty()) {
            throw new AsrException("本地分片 checkpoint 不完整", true);
        }
        return new ChunkResult(new AsrResult(text, segments), maxSpeakerId, audioDurationMs);
    }

    /** 按原始分片顺序合并时间轴和说话人编号。 */
    public static AsrResult mergeChunks(List<ChunkResult> chunks) {
        List<AsrSegment> merged = new ArrayList<>();
        StringBuilder plainText = new StringBuilder();
        long timeOffsetMs = 0L;
        int speakerOffset = 0;
        for (ChunkResult chunk : chunks) {
            for (AsrSegment segment : chunk.result.segments) {
                merged.add(new AsrSegment(segment.speakerId + speakerOffset,
                        segment.startMs + timeOffsetMs, segment.endMs + timeOffsetMs,
                        segment.text));
            }
            appendSegment(plainText, chunk.result.plainText);
            speakerOffset += chunk.maxSpeakerId;
            timeOffsetMs += chunk.audioDurationMs;
        }
        return new AsrResult(plainText.toString(), merged);
    }

    /** 剥离 "[0:0.200,0:1.380,1]  您好。" 这类行首的时间戳前缀。 */
    private static String stripLinePrefix(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("[")) {
            return trimmed;
        }
        int end = trimmed.indexOf(']');
        return end < 0 ? trimmed : trimmed.substring(end + 1).trim();
    }

    private static void appendSegment(StringBuilder target, String segment) {
        if (segment == null) {
            return;
        }
        String cleaned = segment.trim();
        if (cleaned.isEmpty()) {
            return;
        }
        if (target.length() > 0) {
            target.append('\n');
        }
        target.append(cleaned);
    }

    // ==================== HTTP 基础设施 ====================

    private JsonObject post(String baseUrl, String requestId, String jsonBody)
            throws IOException, AsrException {
        int sdkAppId = TrtcUserSigConfig.getSdkAppId();
        String secretKey = TrtcUserSigConfig.getSecretKey();

        String userSig = TrtcUserSig.genTestUserSig(
                sdkAppId, 
                requestId,
                USER_SIG_EXPIRE_SECONDS, 
                secretKey);
        if (userSig.isEmpty()) {
            throw new AsrException("UserSig 生成失败，请检查密钥配置");
        }
        String url = baseUrl + "?AppId=" + sdkAppId
                + "&Secretid=0"
                + "&RequestId=" + requestId
                + "&Timestamp=" + (System.currentTimeMillis() / 1000L);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-TRTC-SdkAppId", String.valueOf(sdkAppId));
            conn.setRequestProperty("X-TRTC-UserSig", userSig);
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            String responseText = readResponse(code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                boolean retryable = code == 408 || code == 429 || code >= 500;
                throw new AsrException("云端接口 HTTP " + code + ": " + responseText,
                        retryable);
            }
            JsonObject root;
            try {
                // 树模型解析不受信响应，不做任意类反序列化。
                root = JsonParser.parseString(responseText).getAsJsonObject();
            } catch (JsonParseException | IllegalStateException e) {
                throw new AsrException("云端接口返回非 JSON: " + abbreviate(responseText));
            }
            JsonObject response = objectOrNull(root, "Response");
            if (response == null) {
                throw new AsrException("云端接口响应缺少 Response: " + abbreviate(responseText));
            }
            JsonObject error = objectOrNull(response, "Error");
            if (error != null) {
                throw new AsrException("云端接口错误 " + stringOrNull(error, "Code")
                        + ": " + stringOrNull(error, "Message"));
            }
            return response;
        } finally {
            conn.disconnect();
        }
    }

    private static JsonObject objectOrNull(JsonObject parent, String member) {
        JsonElement element = parent.get(member);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String stringOrNull(JsonObject parent, String member) {
        JsonElement element = parent.get(member);
        return element != null && element.isJsonPrimitive() && !element.isJsonNull()
                ? element.getAsString() : null;
    }

    private static byte[] readFully(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
        return out.toByteArray();
    }

    private static String readResponse(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int read;
        try (InputStream input = in) {
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }
}
