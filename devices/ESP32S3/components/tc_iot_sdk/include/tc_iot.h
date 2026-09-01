// Copyright (c) 2026 Tencent. All rights reserved.

#ifndef __TC_IOT_H__
#define __TC_IOT_H__

#include <stddef.h>
#include <stdint.h>

#include "tc_iot_err.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
  TC_IOT_LOG_LEVEL_NONE = 0,
  TC_IOT_LOG_LEVEL_ERROR = 1,
  TC_IOT_LOG_LEVEL_WARN = 2,
  TC_IOT_LOG_LEVEL_INFO = 3,
  TC_IOT_LOG_LEVEL_DEBUG = 4,
} tc_iot_log_level_e;

typedef enum {
  TC_IOT_DEV_EVENT_ONLINE = 0,
  TC_IOT_DEV_EVENT_OFFLINE = 1,
  TC_IOT_DEV_EVENT_RECONNECTING = 2,
} tc_iot_device_event_type_e;

typedef struct {
  const char *storage_path;

  tc_iot_log_level_e log_level;
  void (*on_log)(tc_iot_log_level_e level, const char *log);

  void (*on_device_event)(tc_iot_device_event_type_e event_type, const void *event_data);
} tc_iot_config_s;

typedef struct {
  const char *product_id;
  const char *device_id;
  const char *device_secret;
  const char *region;
} tc_iot_device_info_s;

typedef struct {
  const char *product_id;
  const char *device_id;
  const char *product_secret;
} tc_iot_dynamic_register_param_s;

typedef void (*tc_iot_login_cb)(tc_iot_error_e error_code, const char *error_message);

typedef void (*tc_iot_dynamic_register_cb)(tc_iot_error_e error_code, const char *error_message,
                                           const char *device_key);

tc_iot_error_e tc_iot_init(const tc_iot_config_s *config);

tc_iot_error_e tc_iot_deinit(void);

tc_iot_error_e tc_iot_login(const tc_iot_device_info_s *device_info, tc_iot_login_cb callback);

tc_iot_error_e tc_iot_logout(void);

tc_iot_error_e tc_iot_dynamic_register(const tc_iot_dynamic_register_param_s *param,
                                       tc_iot_dynamic_register_cb callback);

tc_iot_error_e tc_iot_get_ntp_time(uint64_t *utc_time_ms);

#ifdef __cplusplus
}
#endif

#endif
