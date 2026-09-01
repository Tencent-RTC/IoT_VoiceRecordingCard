// Copyright (c) 2026 Tencent. All rights reserved.
// 录音卡消息解析与分类覆盖槽（纯 C，不依赖 LVGL/FreeRTOS/board HAL）。
// 用 cJSON 解析 SDK on_message 发来的版本化 JSON，深拷贝为固定结构；
// inbox 按分类覆盖槽隔离快照、进度、生命周期与多域错误。
#ifndef APP_RECORDER_MESSAGE_H
#define APP_RECORDER_MESSAGE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define APP_RECORDER_FILE_NAME_CAP 64u
#define APP_RECORDER_ERROR_MESSAGE_CAP 96u
#define APP_RECORDER_EVENT_CAP 6u /* SOURCE_COUNT 个 source + recover_all */

typedef enum {
  APP_RECORDER_MESSAGE_IGNORED = 0,
  APP_RECORDER_MESSAGE_SNAPSHOT,
  APP_RECORDER_MESSAGE_FILE_STATE,
  APP_RECORDER_MESSAGE_FILE_PROGRESS,
  APP_RECORDER_MESSAGE_ERROR,
  APP_RECORDER_MESSAGE_ERROR_RECOVERED,
} app_recorder_message_kind_t;

typedef enum {
  APP_RECORDER_ERROR_INITIALIZATION = 0,
  APP_RECORDER_ERROR_CONNECTION,
  APP_RECORDER_ERROR_RECORDING,
  APP_RECORDER_ERROR_STORAGE,
  APP_RECORDER_ERROR_FILE_TRANSFER,
  APP_RECORDER_ERROR_SOURCE_COUNT,
  APP_RECORDER_ERROR_ALL,
} app_recorder_error_source_t;

typedef enum {
  APP_RECORDER_CONNECTION_DISCONNECTED = 0,
  APP_RECORDER_CONNECTION_CONNECTING,
  APP_RECORDER_CONNECTION_READY,
} app_recorder_connection_state_t;

typedef enum {
  APP_RECORDER_RECORDING_IDLE = 0,
  APP_RECORDER_RECORDING_STARTING,
  APP_RECORDER_RECORDING_RECORDING,
  APP_RECORDER_RECORDING_STOPPING,
} app_recorder_recording_state_t;

typedef enum {
  APP_RECORDER_FILE_IDLE = 0,
  APP_RECORDER_FILE_TRANSFERRING,
} app_recorder_file_transfer_state_t;

typedef enum {
  APP_RECORDER_FILE_EVENT_STARTED = 0,
  APP_RECORDER_FILE_EVENT_PAUSED,
  APP_RECORDER_FILE_EVENT_COMPLETED,
  APP_RECORDER_FILE_EVENT_CANCELED,
} app_recorder_file_event_t;

typedef struct {
  uint64_t current_timestamp;
  app_recorder_connection_state_t connection;
  app_recorder_recording_state_t recording_state;
  bool has_recording_id;
  uint32_t recording_id;
  bool has_started_at_timestamp;
  uint64_t started_at_timestamp;
  app_recorder_file_transfer_state_t file_transfer_state;
  bool has_file_name;
  char file_name[APP_RECORDER_FILE_NAME_CAP];
} app_recorder_snapshot_t;

typedef struct {
  app_recorder_file_event_t event;
  uint32_t transfer_id;
  char file_name[APP_RECORDER_FILE_NAME_CAP];
  uint64_t transferred_bytes;
  uint64_t total_bytes;
  uint32_t percent;
  bool has_reason;
  char reason[APP_RECORDER_ERROR_MESSAGE_CAP];
} app_recorder_file_state_t;

typedef struct {
  uint32_t transfer_id;
  uint64_t transferred_bytes;
  uint64_t total_bytes;
  uint32_t percent;
} app_recorder_file_progress_t;

typedef struct {
  int error_code;
  app_recorder_error_source_t source;
  bool recoverable;
  char message[APP_RECORDER_ERROR_MESSAGE_CAP];
} app_recorder_error_t;

typedef struct {
  app_recorder_error_source_t source;
} app_recorder_error_recovered_t;

typedef struct {
  app_recorder_message_kind_t kind;
  union {
    app_recorder_snapshot_t snapshot;
    app_recorder_file_state_t file_state;
    app_recorder_file_progress_t file_progress;
    app_recorder_error_t error;
    app_recorder_error_recovered_t recovered;
  } data;
} app_recorder_message_t;

/* 分类覆盖槽：同类别/同 source 只覆盖旧值，不同 source 错误互不覆盖。 */
typedef struct {
  bool valid;
  uint64_t sequence;
  app_recorder_message_t message;
} app_recorder_slot_t;

typedef struct {
  app_recorder_slot_t snapshot;
  app_recorder_slot_t file_lifecycle;
  app_recorder_slot_t file_progress;
  app_recorder_slot_t errors[APP_RECORDER_ERROR_SOURCE_COUNT];
  bool recover_all_pending;
  uint64_t recover_all_sequence;
  uint64_t next_sequence;
} app_recorder_inbox_t;

/* drain 输出：error/recovered 按 sequence 排序的 events，随后三类覆盖槽。 */
typedef struct {
  uint32_t event_count;
  app_recorder_message_t events[APP_RECORDER_EVENT_CAP];
  app_recorder_slot_t snapshot;
  app_recorder_slot_t file_lifecycle;
  app_recorder_slot_t file_progress;
} app_recorder_message_batch_t;

/* 解析一条 JSON 消息。成功返回 true 并填充 out（kind != IGNORED）；未知/非法返回 false。 */
bool app_recorder_message_parse(int error_code, const char *json,
                                app_recorder_message_t *out);

void app_recorder_inbox_init(app_recorder_inbox_t *inbox);
void app_recorder_inbox_store(app_recorder_inbox_t *inbox,
                              const app_recorder_message_t *message);
void app_recorder_inbox_drain(app_recorder_inbox_t *inbox,
                              app_recorder_message_batch_t *out);

#ifdef __cplusplus
}
#endif

#endif /* APP_RECORDER_MESSAGE_H */
