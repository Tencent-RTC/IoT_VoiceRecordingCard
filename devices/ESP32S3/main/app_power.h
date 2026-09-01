#pragma once
#include "esp_err.h"

/*
 * app_power.h — 电源 / 电池 / 深睡 / 息屏管理。
 *
 * 负责 CPU 频率 PM 锁（录音期间保频）、电池状态读取、显示息屏、以及
 * BOOT 按键唤醒的深睡流程。持有 dim / deep_sleep / battery_refresh 三个
 * 定时器；深睡/息屏/电池判定读取 app_power_set_recording_active 维护的
 * s_recording_active。
 */

/* 创建 PM 配置与 CPU 锁，并建立 dim / deep_sleep 定时器。
 * 仅调用一次，在 app_init 之后、board_hal_init 之前或之后均可（不依赖 board_hal）。 */
esp_err_t app_power_init(void);

/* 建立并启动 battery_refresh 周期定时器（10s）。需在 ui_status_init 之后调用，
 * 以保持与重构前"最后一刻再启电池刷新"的时序一致。 */
esp_err_t app_power_start_battery_timer(void);

/* 录音期间获取/释放 CPU 频率锁（app_audio 启动/停止时调用）。可重入。 */
esp_err_t app_power_acquire_recording(void);
void app_power_release_recording(void);

/* 由 UI task 按 model 的 keep_awake 设置录音活动态：active 时恢复正常亮度并
 * 停止 dim/deep-sleep 定时器；idle 时重启这些定时器。 */
void app_power_set_recording_active(bool active);

/* 用户交互活动：恢复亮度 + 重启息屏/深睡定时器。 */
void app_power_note_activity(void);

/* 刷新电池状态并更新 UI（BATTERY_REFRESH 事件目标）。 */
void app_power_refresh_battery(void);

/* 进入深睡（ENTER_DEEP_SLEEP 事件目标；内部做录音/外部供电等守卫）。 */
void app_power_enter_deep_sleep(void);
