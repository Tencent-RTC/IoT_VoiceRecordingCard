package com.recorder.client.cloudasr;

/**
 * 全量 ASR 的一个语句分段：说话人标识 + 相对整段音频的起止时间 + 识别文本。
 *
 * <p>{@code speakerId} 沿用服务端返回值（1 起）；多片合并时已按片偏移，
 * 不同片中编号相同的说话人不会被误认为同一人。
 */
public final class AsrSegment {

    public final int speakerId;
    public final long startMs;
    public final long endMs;
    public final String text;

    public AsrSegment(int speakerId, long startMs, long endMs, String text) {
        this.speakerId = speakerId;
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
    }
}
