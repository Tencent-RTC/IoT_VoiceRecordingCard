// Copyright (c) 2026 Tencent. All rights reserved.
// 录音消息解析与分类覆盖槽实现（纯 C）。cJSON 解析 SDK on_message 的版本化 JSON，
// 深拷贝为固定结构；inbox 按分类覆盖槽隔离快照、进度、生命周期与多域错误。
#include "app_recorder_message.h"

#include <stdio.h>
#include <string.h>

#include "cJSON.h"

/* 解析一个字符串字段到目标缓冲区（深拷贝 + NUL 结尾）。缺失/非字符串返回 false。 */
static bool parse_string(const cJSON *obj, const char *key, char *out, size_t cap) {
  const cJSON *item = cJSON_GetObjectItemCaseSensitive(obj, key);
  if (!cJSON_IsString(item)) {
    return false;
  }
  snprintf(out, cap, "%s", cJSON_GetStringValue(item));
  return true;
}

/* 解析一个可选字符串字段；缺失时不报错。 */
static bool parse_optional_string(const cJSON *obj, const char *key, char *out, size_t cap,
                                  bool *present) {
  const cJSON *item = cJSON_GetObjectItemCaseSensitive(obj, key);
  if (item == NULL) {
    *present = false;
    return true;
  }
  if (!cJSON_IsString(item)) {
    return false;
  }
  snprintf(out, cap, "%s", cJSON_GetStringValue(item));
  *present = true;
  return true;
}

/* 解析必填 uint64 数值字段（用标准 cJSON_GetNumberValue 转 uint64；<2^53 无损）。 */
static bool parse_uint64(const cJSON *obj, const char *key, uint64_t *out) {
  const cJSON *item = cJSON_GetObjectItemCaseSensitive(obj, key);
  if (!cJSON_IsNumber(item)) {
    return false;
  }
  *out = (uint64_t)cJSON_GetNumberValue(item);
  return true;
}

/* 解析必填 uint32 数值字段。 */
static bool parse_uint32(const cJSON *obj, const char *key, uint32_t *out) {
  const cJSON *item = cJSON_GetObjectItemCaseSensitive(obj, key);
  if (!cJSON_IsNumber(item)) {
    return false;
  }
  *out = (uint32_t)cJSON_GetNumberValue(item);
  return true;
}

static bool map_connection(const char *name, app_recorder_connection_state_t *out) {
  if (strcmp(name, "disconnected") == 0) {
    *out = APP_RECORDER_CONNECTION_DISCONNECTED;
  } else if (strcmp(name, "connecting") == 0) {
    *out = APP_RECORDER_CONNECTION_CONNECTING;
  } else if (strcmp(name, "ready") == 0) {
    *out = APP_RECORDER_CONNECTION_READY;
  } else {
    return false;
  }
  return true;
}

static bool map_recording(const char *name, app_recorder_recording_state_t *out) {
  if (strcmp(name, "idle") == 0) {
    *out = APP_RECORDER_RECORDING_IDLE;
  } else if (strcmp(name, "starting") == 0) {
    *out = APP_RECORDER_RECORDING_STARTING;
  } else if (strcmp(name, "recording") == 0) {
    *out = APP_RECORDER_RECORDING_RECORDING;
  } else if (strcmp(name, "stopping") == 0) {
    *out = APP_RECORDER_RECORDING_STOPPING;
  } else {
    return false;
  }
  return true;
}

static bool map_file_transfer(const char *name, app_recorder_file_transfer_state_t *out) {
  if (strcmp(name, "idle") == 0) {
    *out = APP_RECORDER_FILE_IDLE;
  } else if (strcmp(name, "transferring") == 0) {
    *out = APP_RECORDER_FILE_TRANSFERRING;
  } else {
    return false;
  }
  return true;
}

static bool map_file_event(const char *name, app_recorder_file_event_t *out) {
  if (strcmp(name, "started") == 0) {
    *out = APP_RECORDER_FILE_EVENT_STARTED;
  } else if (strcmp(name, "paused") == 0) {
    *out = APP_RECORDER_FILE_EVENT_PAUSED;
  } else if (strcmp(name, "completed") == 0) {
    *out = APP_RECORDER_FILE_EVENT_COMPLETED;
  } else if (strcmp(name, "canceled") == 0) {
    *out = APP_RECORDER_FILE_EVENT_CANCELED;
  } else {
    return false;
  }
  return true;
}

static bool map_error_source(const char *name, app_recorder_error_source_t *out) {
  if (strcmp(name, "initialization") == 0) {
    *out = APP_RECORDER_ERROR_INITIALIZATION;
  } else if (strcmp(name, "connection") == 0) {
    *out = APP_RECORDER_ERROR_CONNECTION;
  } else if (strcmp(name, "recording") == 0) {
    *out = APP_RECORDER_ERROR_RECORDING;
  } else if (strcmp(name, "storage") == 0) {
    *out = APP_RECORDER_ERROR_STORAGE;
  } else if (strcmp(name, "file_transfer") == 0) {
    *out = APP_RECORDER_ERROR_FILE_TRANSFER;
  } else if (strcmp(name, "all") == 0) {
    *out = APP_RECORDER_ERROR_ALL;
  } else {
    return false;
  }
  return true;
}

static bool parse_snapshot(const cJSON *data, app_recorder_snapshot_t *snap) {
  memset(snap, 0, sizeof(*snap));
  if (!parse_uint64(data, "current_timestamp", &snap->current_timestamp)) {
    return false;
  }
  char name[16];
  if (!parse_string(data, "connection", name, sizeof(name)) ||
      !map_connection(name, &snap->connection)) {
    return false;
  }
  const cJSON *rec = cJSON_GetObjectItemCaseSensitive(data, "recording");
  if (!cJSON_IsObject(rec)) {
    return false;
  }
  if (!parse_string(rec, "state", name, sizeof(name)) ||
      !map_recording(name, &snap->recording_state)) {
    return false;
  }
  uint32_t val32;
  if (cJSON_GetObjectItemCaseSensitive(rec, "recording_id") != NULL) {
    if (!parse_uint32(rec, "recording_id", &val32)) {
      return false;
    }
    snap->has_recording_id = true;
    snap->recording_id = val32;
  }
  uint64_t val64;
  if (cJSON_GetObjectItemCaseSensitive(rec, "started_at_timestamp") != NULL) {
    if (!parse_uint64(rec, "started_at_timestamp", &val64)) {
      return false;
    }
    snap->has_started_at_timestamp = true;
    snap->started_at_timestamp = val64;
  }
  const cJSON *ft = cJSON_GetObjectItemCaseSensitive(data, "file_transfer");
  if (!cJSON_IsObject(ft)) {
    return false;
  }
  if (!parse_string(ft, "state", name, sizeof(name)) ||
      !map_file_transfer(name, &snap->file_transfer_state)) {
    return false;
  }
  if (!parse_optional_string(ft, "file_name", snap->file_name,
                             APP_RECORDER_FILE_NAME_CAP, &snap->has_file_name)) {
    return false;
  }
  return true;
}

static bool parse_file_state(const cJSON *data, app_recorder_file_state_t *fs) {
  memset(fs, 0, sizeof(*fs));
  char name[16];
  if (!parse_string(data, "state", name, sizeof(name)) ||
      !map_file_event(name, &fs->event)) {
    return false;
  }
  if (!parse_uint32(data, "transfer_id", &fs->transfer_id)) {
    return false;
  }
  if (!parse_string(data, "file_name", fs->file_name, APP_RECORDER_FILE_NAME_CAP)) {
    return false;
  }
  if (!parse_uint64(data, "transferred_bytes", &fs->transferred_bytes) ||
      !parse_uint64(data, "total_bytes", &fs->total_bytes)) {
    return false;
  }
  if (!parse_uint32(data, "percent", &fs->percent)) {
    return false;
  }
  return parse_optional_string(data, "reason", fs->reason, APP_RECORDER_ERROR_MESSAGE_CAP,
                               &fs->has_reason);
}

static bool parse_file_progress(const cJSON *data, app_recorder_file_progress_t *fp) {
  memset(fp, 0, sizeof(*fp));
  if (!parse_uint32(data, "transfer_id", &fp->transfer_id) ||
      !parse_uint64(data, "transferred_bytes", &fp->transferred_bytes) ||
      !parse_uint64(data, "total_bytes", &fp->total_bytes) ||
      !parse_uint32(data, "percent", &fp->percent)) {
    return false;
  }
  return true;
}

static bool parse_error(const cJSON *data, int error_code, app_recorder_error_t *err) {
  memset(err, 0, sizeof(*err));
  char name[16];
  if (!parse_string(data, "source", name, sizeof(name)) ||
      !map_error_source(name, &err->source)) {
    return false;
  }
  if (err->source >= APP_RECORDER_ERROR_SOURCE_COUNT) {
    return false; /* error.source 必须是真实 source，不能是 all */
  }
  if (!parse_string(data, "message", err->message, APP_RECORDER_ERROR_MESSAGE_CAP)) {
    return false;
  }
  const cJSON *rec = cJSON_GetObjectItemCaseSensitive(data, "recoverable");
  if (!cJSON_IsBool(rec)) {
    return false;
  }
  err->recoverable = cJSON_IsTrue(rec);
  /* 初始化错误强制不可恢复。 */
  if (err->source == APP_RECORDER_ERROR_INITIALIZATION) {
    err->recoverable = false;
  }
  err->error_code = error_code;
  return true;
}

static bool parse_recovered(const cJSON *data, app_recorder_error_recovered_t *rc) {
  memset(rc, 0, sizeof(*rc));
  char name[16];
  if (!parse_string(data, "source", name, sizeof(name)) ||
      !map_error_source(name, &rc->source)) {
    return false;
  }
  return true;
}

bool app_recorder_message_parse(int error_code, const char *json,
                                app_recorder_message_t *out) {
  if (!out) {
    return false;
  }
  memset(out, 0, sizeof(*out));
  out->kind = APP_RECORDER_MESSAGE_IGNORED;
  if (!json || json[0] == '\0') {
    return false;
  }
  cJSON *root = cJSON_Parse(json);
  if (!cJSON_IsObject(root)) {
    cJSON_Delete(root);
    return false;
  }
  const cJSON *version = cJSON_GetObjectItemCaseSensitive(root, "version");
  if (!cJSON_IsNumber(version) || (int)cJSON_GetNumberValue(version) != 1) {
    cJSON_Delete(root);
    return false;
  }
  const cJSON *type_item = cJSON_GetObjectItemCaseSensitive(root, "type");
  if (!cJSON_IsString(type_item)) {
    cJSON_Delete(root);
    return false;
  }
  const char *type = cJSON_GetStringValue(type_item);
  const cJSON *data = cJSON_GetObjectItemCaseSensitive(root, "data");
  if (!cJSON_IsObject(data)) {
    cJSON_Delete(root);
    return false;
  }

  bool ok = false;
  if (strcmp(type, "state.snapshot") == 0) {
    ok = parse_snapshot(data, &out->data.snapshot);
    if (ok) {
      out->kind = APP_RECORDER_MESSAGE_SNAPSHOT;
    }
  } else if (strcmp(type, "file.download.state") == 0) {
    ok = parse_file_state(data, &out->data.file_state);
    if (ok) {
      out->kind = APP_RECORDER_MESSAGE_FILE_STATE;
    }
  } else if (strcmp(type, "file.download.progress") == 0) {
    ok = parse_file_progress(data, &out->data.file_progress);
    if (ok) {
      out->kind = APP_RECORDER_MESSAGE_FILE_PROGRESS;
    }
  } else if (strcmp(type, "error") == 0) {
    ok = parse_error(data, error_code, &out->data.error);
    if (ok) {
      out->kind = APP_RECORDER_MESSAGE_ERROR;
    }
  } else if (strcmp(type, "error.recovered") == 0) {
    ok = parse_recovered(data, &out->data.recovered);
    if (ok) {
      out->kind = APP_RECORDER_MESSAGE_ERROR_RECOVERED;
    }
  } else {
    ok = false; /* 未知 type */
  }

  cJSON_Delete(root);
  if (!ok) {
    out->kind = APP_RECORDER_MESSAGE_IGNORED;
  }
  return ok;
}

void app_recorder_inbox_init(app_recorder_inbox_t *inbox) {
  if (!inbox) {
    return;
  }
  memset(inbox, 0, sizeof(*inbox));
  inbox->next_sequence = 1;
}

void app_recorder_inbox_store(app_recorder_inbox_t *inbox,
                              const app_recorder_message_t *message) {
  if (!inbox || !message || message->kind == APP_RECORDER_MESSAGE_IGNORED) {
    return;
  }
  uint64_t seq = inbox->next_sequence++;
  app_recorder_slot_t *slot = NULL;
  if (message->kind == APP_RECORDER_MESSAGE_SNAPSHOT) {
    slot = &inbox->snapshot;
  } else if (message->kind == APP_RECORDER_MESSAGE_FILE_STATE) {
    slot = &inbox->file_lifecycle;
  } else if (message->kind == APP_RECORDER_MESSAGE_FILE_PROGRESS) {
    slot = &inbox->file_progress;
  } else if (message->kind == APP_RECORDER_MESSAGE_ERROR) {
    if (message->data.error.source < APP_RECORDER_ERROR_SOURCE_COUNT) {
      slot = &inbox->errors[message->data.error.source];
    }
  } else if (message->kind == APP_RECORDER_MESSAGE_ERROR_RECOVERED) {
    if (message->data.recovered.source == APP_RECORDER_ERROR_ALL) {
      inbox->recover_all_pending = true;
      inbox->recover_all_sequence = seq;
      return; /* recover_all 用专用槽，不入 errors[] */
    }
    if (message->data.recovered.source < APP_RECORDER_ERROR_SOURCE_COUNT) {
      slot = &inbox->errors[message->data.recovered.source];
    }
  }
  if (slot) {
    slot->valid = true;
    slot->sequence = seq;
    slot->message = *message;
  }
}

/* 收集有效槽消息并按 sequence 升序（插入排序，数量很少）。 */
static void collect_sorted(app_recorder_slot_t *slots, int count, uint64_t extra_seq,
                           bool extra_valid, app_recorder_message_t *extra_msg,
                           app_recorder_message_t *out, uint32_t *out_count) {
  app_recorder_message_t tmp[APP_RECORDER_EVENT_CAP];
  uint64_t seq[APP_RECORDER_EVENT_CAP];
  int n = 0;
  for (int i = 0; i < count; i++) {
    if (slots[i].valid) {
      tmp[n] = slots[i].message;
      seq[n] = slots[i].sequence;
      n++;
    }
  }
  if (extra_valid) {
    tmp[n] = *extra_msg;
    seq[n] = extra_seq;
    n++;
  }
  for (int i = 1; i < n; i++) {
    app_recorder_message_t m = tmp[i];
    uint64_t s = seq[i];
    int j = i - 1;
    while (j >= 0 && seq[j] > s) {
      tmp[j + 1] = tmp[j];
      seq[j + 1] = seq[j];
      j--;
    }
    tmp[j + 1] = m;
    seq[j + 1] = s;
  }
  int cap = APP_RECORDER_EVENT_CAP;
  for (int i = 0; i < n && i < cap; i++) {
    out[i] = tmp[i];
  }
  *out_count = (uint32_t)(n < cap ? n : cap);
}

void app_recorder_inbox_drain(app_recorder_inbox_t *inbox,
                              app_recorder_message_batch_t *out) {
  if (!inbox || !out) {
    return;
  }
  memset(out, 0, sizeof(*out));
  app_recorder_message_t recover_all_msg;
  memset(&recover_all_msg, 0, sizeof(recover_all_msg));
  if (inbox->recover_all_pending) {
    recover_all_msg.kind = APP_RECORDER_MESSAGE_ERROR_RECOVERED;
    recover_all_msg.data.recovered.source = APP_RECORDER_ERROR_ALL;
  }
  collect_sorted(inbox->errors, APP_RECORDER_ERROR_SOURCE_COUNT,
                 inbox->recover_all_pending ? inbox->recover_all_sequence : 0,
                 inbox->recover_all_pending, &recover_all_msg, out->events,
                 &out->event_count);

  out->snapshot = inbox->snapshot;
  out->file_lifecycle = inbox->file_lifecycle;
  out->file_progress = inbox->file_progress;

  /* drain 后清空全部槽。 */
  app_recorder_inbox_init(inbox);
}
