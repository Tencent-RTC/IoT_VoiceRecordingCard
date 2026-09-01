#pragma once
#include <stdint.h>

#include "app_ui_model.h"
#include "esp_err.h"

/*
 * app_ui.h — 消息驱动的 UI 运行时 owner（design §7）。
 *
 * app_ui 是唯一 owner：持有 app_recorder_inbox、app_ui_model 与单一 UI task。
 * SDK 的 on_message 经 app_ui_post_recorder_message 入 inbox；UI task 周期归并
 * inbox 到 model、应用电源策略并渲染 ui_status 场景。只此 task 调用 ui_status_*。
 * 设备名按 TXVR-<MAC 末两字节> 规则在 init 时设置。
 */

/* 创建并启动 UI task（持有 inbox + model + 设备名）。仅调用一次，在
 * ui_status_init 之后。 */
esp_err_t app_ui_init(void);

/* 入一条 SDK on_message JSON 到 inbox（锁外解析 + critical section store +
 * 通知 UI task）。可从任意线程（含 IoT message loop）调用；不阻塞、不直连 LVGL。 */
void app_ui_post_recorder_message(int error_code, const char *json_msg);

/* 本地 pipeline 启动成功：记单调计时起点（app_audio 调用）。 */
void app_ui_note_pipeline_started(uint64_t monotonic_ms);

/* 本地错误（非 SDK 消息路径，如 audio/recorder init 失败）：注入 model。 */
void app_ui_set_local_error(app_recorder_error_source_t source,
                            const char *message, bool recoverable);

/* 当前按键动作（由 UI task 归并后的 model 决定）。任意线程可读。 */
app_ui_button_action_t app_ui_get_button_action(void);
