package com.recorder.transport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 观测指标（协议文档 §13.2）。
 *
 * <p>本实验版的首要产出不是「能跑」，而是这组数据：
 * <b>在信用流控保护下，BLE GATT 的真实丢帧率是多少？</b>
 * 重传机制是必要的工程保险，还是一段永不执行的死代码？
 *
 * <p>判定标准（§13.3）：p = gapFramesLost / totalDataFramesRx
 * <ul>
 *   <li>p = 0        → 流控完全有效，重传长期不必要</li>
 *   <li>0 < p < 1e-5 → Demo 可接受，上线前加回重传</li>
 *   <li>p >= 1e-5    → 先调流控，勿急于加重传</li>
 * </ul>
 *
 * <p>归因关键是 {@link #maxGapBurstLength}：
 * 1~2 表示随机丢包（重传有效）；>=5 表示接收缓冲溢出、流控失效，
 * <b>此时加重传是错误的修复方向</b>（重传的帧会再次溢出）。
 */
public final class TransportMetrics {

    // ---- 实验主指标 ----
    public volatile long totalDataFramesRx;
    public volatile long gapEvents;
    public volatile long gapFramesLost;
    public volatile int maxGapBurstLength;
    public volatile long duplicateFrames;

    // ---- 辅助判定 ----
    public volatile long ackStallEvents;
    public volatile long ackStallRecoveries;
    public volatile long maxAckStallMs;
    /** ACK 补发成功提交次数（保留旧指标名以便日志对比）。 */
    public volatile long periodicAckSent;
    /** 快速 3 次额度耗尽后的低频 ACK 保活提交次数。 */
    public volatile long ackKeepAliveSent;
    public volatile long windowBlockedMs;
    public volatile long linkUpTimeMs;
    public volatile long totalBytesRx;
    public volatile long totalBytesTx;

    // ---- 归因 ----
    public final Map<TransportFatalError, Long> teardownReasons = new LinkedHashMap<>();

    public synchronized void recordGap(int expectedSeq, int actualSeq) {
        gapEvents++;
        int lost = (actualSeq - expectedSeq) & 0xFFFF;
        gapFramesLost += lost;
        if (lost > maxGapBurstLength) {
            maxGapBurstLength = lost;
        }
    }

    public synchronized void recordTeardown(TransportFatalError reason) {
        Long v = teardownReasons.get(reason);
        teardownReasons.put(reason, v == null ? 1L : v + 1L);
    }

    /** 每帧丢失率。分母为 0 时返回 0。 */
    public double frameLossRate() {
        long total = totalDataFramesRx + gapFramesLost;
        return total == 0 ? 0d : (double) gapFramesLost / (double) total;
    }

    public synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("DATA 帧接收=").append(totalDataFramesRx)
          .append(" 缺口事件=").append(gapEvents)
          .append(" 丢帧=").append(gapFramesLost)
          .append(" 最大连续丢帧=").append(maxGapBurstLength)
          .append(" 重复帧=").append(duplicateFrames)
          .append(" 丢帧率=").append(String.format("%.3e", frameLossRate()))
          .append(" ACK补发=").append(periodicAckSent)
          .append(" ACK保活=").append(ackKeepAliveSent)
          .append(" ACK软停滞=").append(ackStallEvents)
          .append(" ACK恢复=").append(ackStallRecoveries)
          .append(" 最长停滞=").append(maxAckStallMs).append("ms");
        if (!teardownReasons.isEmpty()) {
            sb.append(" 拆链原因=").append(teardownReasons);
        }
        return sb.toString();
    }

    public synchronized void reset() {
        totalDataFramesRx = 0;
        gapEvents = 0;
        gapFramesLost = 0;
        maxGapBurstLength = 0;
        duplicateFrames = 0;
        ackStallEvents = 0;
        ackStallRecoveries = 0;
        maxAckStallMs = 0;
        periodicAckSent = 0;
        ackKeepAliveSent = 0;
        windowBlockedMs = 0;
        linkUpTimeMs = 0;
        totalBytesRx = 0;
        totalBytesTx = 0;
        teardownReasons.clear();
    }
}
