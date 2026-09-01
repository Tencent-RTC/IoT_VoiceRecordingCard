// Copyright (c) 2026 Tencent. All rights reserved.
// 录音卡单一 UI 状态模型实现（纯 C）：归并 batch 的 error/recovered/snapshot/
// file lifecycle/progress，维护唯一状态并输出画面、按键与电源策略。
#include "app_ui_model.h"

#include <stdio.h>
#include <string.h>

#include "app_recorder_message.h"

/* 找出要展示的错误：不可恢复优先，同级取 sequence 最大。返回槽指针或 NULL。 */
static const app_ui_error_slot_t *winning_error(const app_ui_model_t *model) {
  const app_ui_error_slot_t *best = NULL;
  for (int pass = 0; pass < 2; pass++) {
    bool want_fatal = (pass == 0);
    best = NULL;
    for (int i = 0; i < APP_RECORDER_ERROR_SOURCE_COUNT; i++) {
      const app_ui_error_slot_t *e = &model->errors[i];
      if (!e->active) {
        continue;
      }
      if (e->recoverable == want_fatal) {
        continue; /* pass 0 只要 fatal，pass 1 只要 recoverable */
      }
      if (!best || e->sequence > best->sequence) {
        best = e;
      }
    }
    if (best) {
      return best; /* 有 fatal 则取 fatal；否则回到 recoverable */
    }
  }
  return best;
}

static bool any_recording_active(const app_ui_model_t *model) {
  return model->recording_state != APP_RECORDER_RECORDING_IDLE;
}

static void apply_error_event(app_ui_model_t *model,
                              const app_recorder_message_t *message, uint64_t seq) {
  if (message->kind == APP_RECORDER_MESSAGE_ERROR) {
    app_recorder_error_source_t src = message->data.error.source;
    if (src >= APP_RECORDER_ERROR_SOURCE_COUNT) {
      return;
    }
    app_ui_error_slot_t *slot = &model->errors[src];
    /* 已锁存不可恢复错误不被新的可恢复错误覆盖。 */
    if (slot->active && !slot->recoverable && message->data.error.recoverable) {
      return;
    }
    slot->active = true;
    slot->recoverable = message->data.error.recoverable;
    slot->sequence = seq;
    snprintf(slot->message, sizeof(slot->message), "%s", message->data.error.message);
  } else if (message->kind == APP_RECORDER_MESSAGE_ERROR_RECOVERED) {
    app_recorder_error_source_t src = message->data.recovered.source;
    if (src == APP_RECORDER_ERROR_ALL) {
      for (int i = 0; i < APP_RECORDER_ERROR_SOURCE_COUNT; i++) {
        model->errors[i].active = false;
        model->errors[i].recoverable = false;
      }
      return;
    }
    if (src < APP_RECORDER_ERROR_SOURCE_COUNT) {
      app_ui_error_slot_t *slot = &model->errors[src];
      /* recovered 只清可恢复错误；不可恢复错误保持锁存。 */
      if (slot->active && slot->recoverable) {
        slot->active = false;
        slot->recoverable = false;
      }
    }
  }
}

static void apply_snapshot(app_ui_model_t *model,
                           const app_recorder_snapshot_t *snap, uint64_t now_ms) {
  model->connection = snap->connection;
  model->recording_state = snap->recording_state;
  model->file_transfer_state = snap->file_transfer_state;
  model->has_started_at_timestamp = snap->has_started_at_timestamp;
  model->started_at_timestamp = snap->started_at_timestamp;

  /* 单调计时：同一 recording_id 不重置 origin；新 recording_id 且无本地 origin 时
   * 用快照 current_timestamp - started_at_timestamp 推算 origin。 */
  if (snap->has_recording_id) {
    bool new_id = (snap->recording_id != model->recording_id);
    if (new_id || !model->has_recording_origin) {
      if (!model->has_recording_origin && snap->has_started_at_timestamp) {
        uint64_t elapsed_s =
            snap->current_timestamp >= snap->started_at_timestamp
                ? snap->current_timestamp - snap->started_at_timestamp
                : 0u;
        uint64_t elapsed_ms = elapsed_s * 1000u;
        uint64_t origin = now_ms > elapsed_ms ? now_ms - elapsed_ms : 0u;
        model->recording_origin_ms = origin;
        model->has_recording_origin = true;
      }
    }
    model->recording_id = snap->recording_id;
    model->has_recording_id = true;
  }
}

static void apply_file_state(app_ui_model_t *model, const app_recorder_file_state_t *fs,
                             uint64_t now_ms) {
  if (fs->event == APP_RECORDER_FILE_EVENT_STARTED) {
    /* 新 started 清旧提示，标记 active transfer。 */
    model->file_notice_active = false;
    model->file_transfer_active = true;
    return;
  }
  /* paused/completed/canceled 为终态：建立提示期限，不再 active transfer。 */
  model->file_notice_active = true;
  model->file_notice_event = fs->event;
  model->file_transfer_active = false;
  uint32_t deadline = (fs->event == APP_RECORDER_FILE_EVENT_COMPLETED)
                          ? APP_UI_NOTICE_COMPLETED_MS
                          : APP_UI_NOTICE_PAUSED_CANCELED_MS;
  model->file_notice_deadline_ms = now_ms + deadline;
}

static void apply_file_progress(app_ui_model_t *model) {
  /* 终态后的 stale progress 不重新激活 active transfer。 */
  if (model->file_notice_active) {
    return;
  }
  model->file_transfer_active = true;
}

void app_ui_model_init(app_ui_model_t *model) {
  if (!model) {
    return;
  }
  memset(model, 0, sizeof(*model));
  model->connection = APP_RECORDER_CONNECTION_DISCONNECTED;
  model->recording_state = APP_RECORDER_RECORDING_IDLE;
  model->file_transfer_state = APP_RECORDER_FILE_IDLE;
}

void app_ui_model_apply_batch(app_ui_model_t *model,
                              const app_recorder_message_batch_t *batch,
                              uint64_t monotonic_ms) {
  if (!model || !batch) {
    return;
  }
  /* 归并顺序：先 error/recovered（按 sequence），再 snapshot、file lifecycle、progress。 */
  for (uint32_t i = 0; i < batch->event_count; i++) {
    apply_error_event(model, &batch->events[i], (uint64_t)i);
  }
  if (batch->snapshot.valid && batch->snapshot.message.kind == APP_RECORDER_MESSAGE_SNAPSHOT) {
    apply_snapshot(model, &batch->snapshot.message.data.snapshot, monotonic_ms);
  }
  if (batch->file_lifecycle.valid &&
      batch->file_lifecycle.message.kind == APP_RECORDER_MESSAGE_FILE_STATE) {
    apply_file_state(model, &batch->file_lifecycle.message.data.file_state, monotonic_ms);
  }
  if (batch->file_progress.valid &&
      batch->file_progress.message.kind == APP_RECORDER_MESSAGE_FILE_PROGRESS) {
    apply_file_progress(model);
  }
}

void app_ui_model_note_pipeline_started(app_ui_model_t *model, uint64_t monotonic_ms) {
  if (!model) {
    return;
  }
  model->recording_origin_ms = monotonic_ms;
  model->has_recording_origin = true;
}

void app_ui_model_set_local_error(app_ui_model_t *model,
                                  app_recorder_error_source_t source,
                                  const char *message, bool recoverable) {
  if (!model || source >= APP_RECORDER_ERROR_SOURCE_COUNT) {
    return;
  }
  app_ui_error_slot_t *slot = &model->errors[source];
  if (slot->active && !slot->recoverable && recoverable) {
    return;
  }
  slot->active = true;
  slot->recoverable = recoverable;
  slot->sequence++;
  snprintf(slot->message, sizeof(slot->message), "%s", message ? message : "");
}

app_ui_view_kind_t app_ui_model_view(const app_ui_model_t *model, uint64_t monotonic_ms) {
  if (!model) {
    return APP_UI_VIEW_WAITING;
  }
  /* 优先级：fatal error > recoverable error > recording 三态 > 终态提示 >
   * active transfer > waiting/connecting/ready。 */
  const app_ui_error_slot_t *err = winning_error(model);
  if (err) {
    return APP_UI_VIEW_ERROR;
  }
  switch (model->recording_state) {
    case APP_RECORDER_RECORDING_STARTING:
      return APP_UI_VIEW_STARTING;
    case APP_RECORDER_RECORDING_RECORDING:
      return APP_UI_VIEW_RECORDING;
    case APP_RECORDER_RECORDING_STOPPING:
      return APP_UI_VIEW_STOPPING;
    default:
      break;
  }
  if (model->file_notice_active && monotonic_ms < model->file_notice_deadline_ms) {
    switch (model->file_notice_event) {
      case APP_RECORDER_FILE_EVENT_COMPLETED:
        return APP_UI_VIEW_FILE_COMPLETED;
      case APP_RECORDER_FILE_EVENT_PAUSED:
        return APP_UI_VIEW_FILE_PAUSED;
      case APP_RECORDER_FILE_EVENT_CANCELED:
        return APP_UI_VIEW_FILE_CANCELED;
      default:
        break;
    }
  }
  if (model->file_transfer_active) {
    return APP_UI_VIEW_FILE_TRANSFER;
  }
  switch (model->connection) {
    case APP_RECORDER_CONNECTION_CONNECTING:
      return APP_UI_VIEW_CONNECTING;
    case APP_RECORDER_CONNECTION_READY:
      return APP_UI_VIEW_READY;
    default:
      return APP_UI_VIEW_WAITING;
  }
}

app_ui_button_action_t app_ui_model_button_action(const app_ui_model_t *model,
                                                  uint64_t monotonic_ms) {
  if (!model) {
    return APP_UI_BUTTON_IGNORE;
  }
  /* 忽略 error/文件/开始/停止态；仅 idle 连接态可 START，recording 可 STOP。
   * 文件终态提示仅在有效期内视为活动；提示过期后恢复按键。 */
  if (model->recording_state == APP_RECORDER_RECORDING_RECORDING) {
    return APP_UI_BUTTON_STOP;
  }
  const bool notice_active =
      model->file_notice_active && monotonic_ms < model->file_notice_deadline_ms;
  if (!any_recording_active(model) && !winning_error(model) && !notice_active &&
      !model->file_transfer_active) {
    return APP_UI_BUTTON_START;
  }
  return APP_UI_BUTTON_IGNORE;
}

bool app_ui_model_keep_awake(const app_ui_model_t *model) {
  if (!model) {
    return false;
  }
  return any_recording_active(model) || model->file_transfer_active;
}

uint32_t app_ui_model_elapsed_seconds(const app_ui_model_t *model, uint64_t monotonic_ms) {
  if (!model || !model->has_recording_origin) {
    return 0;
  }
  if (monotonic_ms <= model->recording_origin_ms) {
    return 0;
  }
  return (uint32_t)((monotonic_ms - model->recording_origin_ms) / 1000u);
}
