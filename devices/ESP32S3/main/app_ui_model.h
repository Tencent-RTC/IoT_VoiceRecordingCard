// Copyright (c) 2026 Tencent. All rights reserved.
// 纯 C 单一 UI 状态模型：归并 app_recorder_message 的 batch，维护连接/录音/下载/
// 多域错误状态，输出确定的画面、按键动作与电源保持判定。不依赖 LVGL/FreeRTOS/
// board HAL/esp_log，可 host 编译。
#ifndef APP_UI_MODEL_H
#define APP_UI_MODEL_H

#include <stdbool.h>
#include <stdint.h>

#include "app_recorder_message.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
  APP_UI_VIEW_WAITING = 0,
  APP_UI_VIEW_CONNECTING,
  APP_UI_VIEW_READY,
  APP_UI_VIEW_STARTING,
  APP_UI_VIEW_RECORDING,
  APP_UI_VIEW_STOPPING,
  APP_UI_VIEW_FILE_TRANSFER,
  APP_UI_VIEW_FILE_COMPLETED,
  APP_UI_VIEW_FILE_PAUSED,
  APP_UI_VIEW_FILE_CANCELED,
  APP_UI_VIEW_ERROR,
} app_ui_view_kind_t;

typedef enum {
  APP_UI_BUTTON_START = 0,
  APP_UI_BUTTON_STOP,
  APP_UI_BUTTON_IGNORE,
} app_ui_button_action_t;

/* 终态提示 deadline（基于 monotonic_ms）。 */
#define APP_UI_NOTICE_COMPLETED_MS 1000u
#define APP_UI_NOTICE_PAUSED_CANCELED_MS 2000u

/* 每 source 错误锁存槽：只留最新；可恢复错误不覆盖已锁存不可恢复。 */
typedef struct {
  bool active;
  bool recoverable;
  uint64_t sequence;
  char message[APP_RECORDER_ERROR_MESSAGE_CAP];
} app_ui_error_slot_t;

typedef struct {
  app_recorder_connection_state_t connection;
  app_recorder_recording_state_t recording_state;
  uint32_t recording_id;
  bool has_recording_id;
  uint64_t started_at_timestamp;
  bool has_started_at_timestamp;
  uint64_t recording_origin_ms;
  bool has_recording_origin;
  app_recorder_file_transfer_state_t file_transfer_state;
  bool file_transfer_active;
  bool file_notice_active;
  app_recorder_file_event_t file_notice_event;
  uint64_t file_notice_deadline_ms;
  app_ui_error_slot_t errors[APP_RECORDER_ERROR_SOURCE_COUNT];
} app_ui_model_t;

void app_ui_model_init(app_ui_model_t *model);
void app_ui_model_apply_batch(app_ui_model_t *model,
                              const app_recorder_message_batch_t *batch,
                              uint64_t monotonic_ms);
void app_ui_model_note_pipeline_started(app_ui_model_t *model, uint64_t monotonic_ms);
void app_ui_model_set_local_error(app_ui_model_t *model,
                                  app_recorder_error_source_t source,
                                  const char *message, bool recoverable);
app_ui_view_kind_t app_ui_model_view(const app_ui_model_t *model, uint64_t monotonic_ms);
app_ui_button_action_t app_ui_model_button_action(const app_ui_model_t *model,
                                                  uint64_t monotonic_ms);
bool app_ui_model_keep_awake(const app_ui_model_t *model);

/* 单调录音计时（helper）：当前已录制秒数，基于 monotonic_ms。 */
uint32_t app_ui_model_elapsed_seconds(const app_ui_model_t *model, uint64_t monotonic_ms);

#ifdef __cplusplus
}
#endif

#endif /* APP_UI_MODEL_H */
