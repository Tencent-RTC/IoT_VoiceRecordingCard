// Copyright (c) 2026 Tencent. All rights reserved.

#ifndef __TC_IOT_AUDIO_RECORDER_H__
#define __TC_IOT_AUDIO_RECORDER_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

#include "tc_iot_def.h"
#include "tc_iot_err.h"
#include "tc_iot.h"

typedef struct {
  const char *storage_path;
  tc_iot_log_level_e log_level;
  void (*on_log)(tc_iot_log_level_e level, const char *log);
} tc_iot_audio_recorder_params_s;

typedef struct {
  void (*on_audio_recorder_start)(const char *params, void *user_data);
  void (*on_audio_recorder_stop)(const char *params, void *user_data);
  void (*on_message)(tc_iot_error_e error_code, const char *json_msg, void *user_data);
} tc_iot_audio_recorder_observer_s;

typedef void (*tc_iot_audio_recorder_operation_cb)(tc_iot_error_e error_code, const char *error_message);

tc_iot_error_e tc_iot_audio_recorder_init(tc_iot_audio_recorder_params_s *init_params,
                                          tc_iot_audio_recorder_observer_s *observers, void *user_data);
tc_iot_error_e tc_iot_audio_recorder_deinit(void);

tc_iot_error_e tc_iot_audio_recorder_start(tc_iot_audio_recorder_operation_cb callback);
tc_iot_error_e tc_iot_audio_recorder_stop(void);

tc_iot_error_e tc_iot_audio_recorder_send_audio(const tc_iot_audio_frame *frame);

#ifdef __cplusplus
}
#endif

#endif /* __TC_IOT_AUDIO_RECORDER_H__ */
