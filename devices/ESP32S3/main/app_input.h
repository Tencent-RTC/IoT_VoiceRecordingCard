#pragma once
#include "esp_err.h"

/*
 * app_input.h — 按键输入 + 应用事件队列。
 *
 * 管理 BOOT 前按键（iOT button）与应用事件队列（app_event_task 串行消费），
 * 把按键/定时器事件分发到各模块。按键动作由 app_ui_get_button_action 决策
 * （基于 UI model），故队列仅承载 FRONT_PRESSED / BATTERY_REFRESH /
 * ENTER_DEEP_SLEEP。
 */

/* 创建事件队列 + 注册按键 + 启动事件任务。仅调用一次，在 recorder 栈就绪后。 */
esp_err_t app_input_init(void);
