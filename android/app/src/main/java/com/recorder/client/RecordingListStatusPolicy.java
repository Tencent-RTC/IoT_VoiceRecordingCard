package com.recorder.client;

/**
 * 录音列表中“文件同步状态”和“云端转写状态”的纯 Java 展示策略。
 *
 * <p>该类不依赖 Android View 或持久化实现，集中约束两套正交状态机的展示优先级，
 * 避免首页与“录音文件”页后续演进出不同判断。文件未完整同步时永远优先展示同步状态；
 * 文件完整后，活动转写、成功和失败状态才覆盖普通完成/确认状态。
 */
public final class RecordingListStatusPolicy {

    public enum DisplayState {
        /** 继续由列表现有同步分支渲染等待、断点或环形进度。 */
        FILE_TRANSFER,
        /** 文件已落盘，但设备侧 complete 尚未确认。 */
        DEVICE_CONFIRMING,
        /** 文件已完成同步，尚未请求转写。 */
        COMPLETED,
        TRANSCRIBING,
        TRANSCRIBED,
        TRANSCRIPTION_FAILED
    }

    private RecordingListStatusPolicy() {
    }

    public static DisplayState resolve(boolean fileTransmitted,
                                       boolean deviceDeletionConfirmed,
                                       boolean transcriptionActive,
                                       boolean transcriptionSucceeded,
                                       boolean transcriptionFailed) {
        if (!fileTransmitted) {
            return DisplayState.FILE_TRANSFER;
        }
        if (transcriptionActive) {
            return DisplayState.TRANSCRIBING;
        }
        if (transcriptionSucceeded) {
            return DisplayState.TRANSCRIBED;
        }
        if (transcriptionFailed) {
            return DisplayState.TRANSCRIPTION_FAILED;
        }
        return deviceDeletionConfirmed
                ? DisplayState.COMPLETED : DisplayState.DEVICE_CONFIRMING;
    }

    /** 首版删除策略：活动转写期间不能删除本地音频。 */
    public static boolean canDelete(boolean fileTransmitted,
                                    boolean deviceDeletionConfirmed,
                                    boolean transcriptionActive) {
        return fileTransmitted && deviceDeletionConfirmed && !transcriptionActive;
    }
}
