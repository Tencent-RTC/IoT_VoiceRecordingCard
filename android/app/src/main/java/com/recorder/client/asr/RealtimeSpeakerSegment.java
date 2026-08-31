package com.recorder.client.asr;

/**
 * VoiceAI 实时说话人分离的一条可展示分段。
 *
 * <p>{@code speakerNumber} 是当前录音会话内按说话人首次出现顺序映射出的展示编号，
 * 从 1 开始；0 表示云端暂时还没有确认说话人。{@code approximateStartMs} 取客户端
 * 第一次收到该 segmentId 时的录音相对时间，仅用于实时页近似展示，并非服务端精确时间戳。
 */
public final class RealtimeSpeakerSegment {

    public final String segmentId;
    public final int speakerNumber;
    public final String text;
    public final boolean completed;
    public final long approximateStartMs;

    public RealtimeSpeakerSegment(String segmentId, int speakerNumber, String text,
                                  boolean completed, long approximateStartMs) {
        this.segmentId = segmentId == null ? "" : segmentId;
        this.speakerNumber = Math.max(0, speakerNumber);
        this.text = text == null ? "" : text;
        this.completed = completed;
        this.approximateStartMs = Math.max(0L, approximateStartMs);
    }
}
