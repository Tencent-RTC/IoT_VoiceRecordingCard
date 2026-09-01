#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

esp_err_t ui_status_init(void);
esp_err_t ui_status_set_brightness(uint8_t brightness);
void ui_status_prepare_deep_sleep(void);
void ui_status_set_device_name(const char *device_name);
void ui_status_set_advertising(void);
void ui_status_set_pairing(const char *device_name);
void ui_status_set_connecting(void);
void ui_status_set_idle(void);
void ui_status_set_idle_dimmed(bool dimmed);
void ui_status_set_recording(const char *elapsed, bool offline);
void ui_status_set_busy(const char *status, const char *hint);
void ui_status_set_link_down(void);
void ui_status_set_battery(int level_percent, bool charging, bool usb_powered);
void ui_status_set_error(const char *message);
/* 上传进度（design 布局 B：大百分比 + 进度条 + 文件名/字节数两行）。 */
void ui_status_set_file_transfer(int percent, const char *file_name,
                                 uint64_t sent, uint64_t total);
