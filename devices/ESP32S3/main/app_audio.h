#pragma once
#include <stdbool.h>

/*
 * app_audio.h — 录音启停 + Opus 推送桥（audio_pipeline ↔ tc_iot audio recorder）。
 *
 * 仅采集/编码后的裸 Opus 经 push_cb 构造成 tc_iot_audio_frame 交给 SDK，落盘与
 * 实时推送由 SDK 负责。录音启动/停止（BOOT 本地 + App 远端 observer 共用同一
 * 实现）负责获取/释放 PM 锁、启停 pipeline；UI 状态由 SDK snapshot 经 app_ui
 * 归并驱动，不在此直接写录音状态。
 */

/* 封装 audio_pipeline_set_push_cb（push_cb 在本模块内实现）。init 之后调用。 */
void app_audio_set_push_cb(void);

/* 启动录音：获取 PM 锁 → 启动 pipeline → 记录单调计时起点。
 * 返回 true 表示成功；失败时已释放 PM 锁并注入本地错误，调用方需自行清理 SDK 录音。 */
bool app_audio_start_recording(void);

/* 请求异步停止 pipeline；audio 停止完成回调里再停止 SDK 录音并释放 PM 锁
 * （UI 转 idle 由 SDK snapshot 驱动）。 */
void app_audio_request_stop(void);

/* BOOT 按键本地录音：调用 tc_iot_audio_recorder_start，成功回调里启动录音。 */
void app_audio_boot_start(void);
