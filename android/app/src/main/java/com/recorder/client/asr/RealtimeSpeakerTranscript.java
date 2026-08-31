package com.recorder.client.asr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VoiceAI 实时说话人分段状态容器。
 *
 * <p>同一 {@code segmentId} 的中间结果会就地更新，首次出现顺序即展示顺序；云端
 * 原始 speakerId 可能从 -1 收敛为 0/1/2...，本类把已确认的原始编号按本录音中的
 * 首次出现顺序映射为用户可读的 1/2/3...。所有公共方法均可跨线程调用。
 */
public final class RealtimeSpeakerTranscript {

    private static final class MutableSegment {
        final String segmentId;
        final long approximateStartMs;
        String text;
        boolean completed;
        int rawSpeakerId;

        MutableSegment(String segmentId, String text, boolean completed, int rawSpeakerId,
                       long approximateStartMs) {
            this.segmentId = segmentId;
            this.text = text;
            this.completed = completed;
            this.rawSpeakerId = rawSpeakerId;
            this.approximateStartMs = approximateStartMs;
        }
    }

    private final LinkedHashMap<String, MutableSegment> segments = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Integer> speakerNumbers = new LinkedHashMap<>();
    private int nextSpeakerNumber = 1;

    /** 新增或更新一条 VoiceAI 消息。 */
    public synchronized void onMessage(String segmentId, String text, boolean completed,
                                       int rawSpeakerId, long approximateStartMs) {
        String id = segmentId == null ? "" : segmentId;
        String content = text == null ? "" : text;
        MutableSegment segment = segments.get(id);
        if (segment == null) {
            segment = new MutableSegment(id, content, completed, rawSpeakerId,
                    Math.max(0L, approximateStartMs));
            segments.put(id, segment);
        } else {
            // 已落定分段忽略迟到的中间态，避免文本、完成态或说话人发生回退；
            // 重复稳态消息仍允许覆盖，兼容服务端对同 segmentId 的最终修订。
            if (!segment.completed || completed) {
                segment.text = content;
                if (rawSpeakerId >= 0 || segment.rawSpeakerId < 0) {
                    segment.rawSpeakerId = rawSpeakerId;
                }
            }
            segment.completed = segment.completed || completed;
        }
        if (rawSpeakerId >= 0 && !speakerNumbers.containsKey(rawSpeakerId)) {
            speakerNumbers.put(rawSpeakerId, nextSpeakerNumber++);
        }
    }

    /** 不可变快照，供主线程安全渲染。 */
    public synchronized List<RealtimeSpeakerSegment> snapshot() {
        if (segments.isEmpty()) {
            return Collections.emptyList();
        }
        List<RealtimeSpeakerSegment> result = new ArrayList<>(segments.size());
        for (Map.Entry<String, MutableSegment> entry : segments.entrySet()) {
            MutableSegment segment = entry.getValue();
            Integer display = speakerNumbers.get(segment.rawSpeakerId);
            result.add(new RealtimeSpeakerSegment(segment.segmentId,
                    display == null ? 0 : display,
                    segment.text, segment.completed, segment.approximateStartMs));
        }
        return Collections.unmodifiableList(result);
    }

    /** 清空当前实时说话人分段。 */
    public synchronized void clear() {
        segments.clear();
        speakerNumbers.clear();
        nextSpeakerNumber = 1;
    }

    /** 开始新录音会话。 */
    public synchronized void reset() {
        clear();
    }
}
