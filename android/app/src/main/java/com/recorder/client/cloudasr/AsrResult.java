package com.recorder.client.cloudasr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次云端全量 ASR 的最终产物：拼接好的纯文本 + 说话人结构化分段。
 *
 * <p>纯文本用于兼容展示与检索；分段列表可序列化为 JSON 存入 SQLite，
 * 供详情页按「讲话人 + 起始时间」视图还原。
 */
public final class AsrResult {

    private static final Gson GSON = new Gson();

    public final String plainText;
    public final List<AsrSegment> segments;

    public AsrResult(String plainText, List<AsrSegment> segments) {
        this.plainText = plainText;
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
    }

    /** 序列化分段列表；空列表返回 null（调用方据此存 NULL 列）。 */
    public String segmentsToJson() {
        if (segments.isEmpty()) {
            return null;
        }
        JsonArray array = new JsonArray();
        for (AsrSegment segment : segments) {
            JsonObject item = new JsonObject();
            item.addProperty("speaker", segment.speakerId);
            item.addProperty("start_ms", segment.startMs);
            item.addProperty("end_ms", segment.endMs);
            item.addProperty("text", segment.text);
            array.add(item);
        }
        return GSON.toJson(array);
    }

    /** 反序列化 {@link #segmentsToJson} 的输出；任何字段非法都返回空列表。 */
    public static List<AsrSegment> segmentsFromJson(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) {
                return Collections.emptyList();
            }
            List<AsrSegment> segments = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    return Collections.emptyList();
                }
                JsonObject item = element.getAsJsonObject();
                JsonElement text = item.get("text");
                if (text == null || !text.isJsonPrimitive()) {
                    return Collections.emptyList();
                }
                segments.add(new AsrSegment(
                        item.has("speaker") ? item.get("speaker").getAsInt() : 0,
                        item.has("start_ms") ? item.get("start_ms").getAsLong() : 0L,
                        item.has("end_ms") ? item.get("end_ms").getAsLong() : 0L,
                        text.getAsString()));
            }
            return segments;
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            return Collections.emptyList();
        }
    }
}
