#include <stdio.h>
#include <string.h>

#include "esp_efuse.h"
#include "esp_mac.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "app_power.h"
#include "app_recorder_message.h"
#include "app_ui.h"
#include "app_ui_model.h"
#include "cJSON.h"
#include "ui_status.h"

#define UI_TASK_STACK 4096
#define UI_TASK_PRIORITY 5
#define UI_TASK_POLL_MS 500

/* 唯一 UI 运行时 owner 持有的状态。 */
static app_recorder_inbox_t s_inbox;
static app_ui_model_t s_model;
static portMUX_TYPE s_model_lock = portMUX_INITIALIZER_UNLOCKED;
static TaskHandle_t s_ui_task;

/* 最近一次重绘的 view / 录音秒数 / 传输百分比（去重基准）。 */
static app_ui_view_kind_t s_last_view;
static uint32_t s_last_elapsed_sec;
static int s_last_transfer_percent;
static bool s_rendered_once;

/* 最近一次文件传输展示数据（UI task 归并 batch 时捕获）。 */
static app_recorder_file_progress_t s_transfer_progress;
static bool s_transfer_progress_valid;
static char s_transfer_name[APP_RECORDER_FILE_NAME_CAP];

/* 录音时长格式化：mm:ss */
static void format_elapsed(uint32_t sec, char *buf, size_t cap)
{
    snprintf(buf, cap, "%02u:%02u", (unsigned)(sec / 60), (unsigned)(sec % 60));
}

static void capture_file_progress(const app_recorder_message_batch_t *batch)
{
    if (batch->file_progress.valid &&
        batch->file_progress.message.kind == APP_RECORDER_MESSAGE_FILE_PROGRESS) {
        s_transfer_progress = batch->file_progress.message.data.file_progress;
        s_transfer_progress_valid = true;
    }
    if (batch->file_lifecycle.valid &&
        batch->file_lifecycle.message.kind == APP_RECORDER_MESSAGE_FILE_STATE) {
        const app_recorder_file_state_t *fs = &batch->file_lifecycle.message.data.file_state;
        if (fs->event == APP_RECORDER_FILE_EVENT_STARTED) {
            snprintf(s_transfer_name, sizeof(s_transfer_name), "%s", fs->file_name);
        }
    }
}

/* 按 view 映射到 ui_status 场景（只此 task 调用 ui_status_*）。 */
static void render_view(app_ui_view_kind_t view, const app_ui_model_t *model, uint64_t now_ms)
{
    switch (view) {
    case APP_UI_VIEW_WAITING:
        ui_status_set_link_down();
        break;
    case APP_UI_VIEW_CONNECTING:
        ui_status_set_connecting();
        break;
    case APP_UI_VIEW_READY:
        ui_status_set_idle();
        break;
    case APP_UI_VIEW_STARTING:
        ui_status_set_busy("Starting recording", "Please wait");
        break;
    case APP_UI_VIEW_RECORDING: {
        char elapsed[16];
        format_elapsed(app_ui_model_elapsed_seconds(model, now_ms), elapsed, sizeof(elapsed));
        const bool offline = (model->connection != APP_RECORDER_CONNECTION_READY);
        ui_status_set_recording(elapsed, offline);
        break;
    }
    case APP_UI_VIEW_STOPPING:
        ui_status_set_busy("Saving recording", "Please wait");
        break;
    case APP_UI_VIEW_FILE_TRANSFER:
        ui_status_set_file_transfer(
            (int)s_transfer_progress.percent,
            s_transfer_progress_valid ? s_transfer_name : "",
            s_transfer_progress.transferred_bytes, s_transfer_progress.total_bytes);
        break;
    case APP_UI_VIEW_FILE_COMPLETED:
        ui_status_set_busy("Sync complete", "Saved to App");
        break;
    case APP_UI_VIEW_FILE_PAUSED:
        ui_status_set_busy("Sync paused", "Resume in App");
        break;
    case APP_UI_VIEW_FILE_CANCELED:
        ui_status_set_busy("Sync canceled", "See App");
        break;
    case APP_UI_VIEW_ERROR: {
        const app_ui_error_slot_t *err = NULL;
        for (int i = 0; i < APP_RECORDER_ERROR_SOURCE_COUNT; i++) {
            if (model->errors[i].active) {
                err = &model->errors[i];
                break;
            }
        }
        ui_status_set_error(err ? err->message : "Unknown error");
        break;
    }
    default:
        break;
    }
}

static void ui_task(void *arg)
{
    (void)arg;
    while (true) {
        (void)ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(UI_TASK_POLL_MS));
        const uint64_t now_ms = (uint64_t)esp_timer_get_time() / 1000u;

        /* 归并 inbox 到 model（模型受锁保护；渲染在锁外做）。 */
        portENTER_CRITICAL(&s_model_lock);
        app_recorder_message_batch_t batch;
        app_recorder_inbox_drain(&s_inbox, &batch);
        capture_file_progress(&batch);
        app_ui_model_apply_batch(&s_model, &batch, now_ms);
        const app_ui_view_kind_t view = app_ui_model_view(&s_model, now_ms);
        const bool keep_awake = app_ui_model_keep_awake(&s_model);
        const uint32_t elapsed = app_ui_model_elapsed_seconds(&s_model, now_ms);
        portEXIT_CRITICAL(&s_model_lock);

        app_power_set_recording_active(keep_awake);

        /* 只在 view 变化、录音秒数变化或传输百分比变化时重绘。 */
        bool redraw = !s_rendered_once || (view != s_last_view);
        if (view == APP_UI_VIEW_RECORDING && elapsed != s_last_elapsed_sec) {
            redraw = true;
        }
        if (view == APP_UI_VIEW_FILE_TRANSFER &&
            (int)s_transfer_progress.percent != s_last_transfer_percent) {
            redraw = true;
        }
        if (redraw) {
            render_view(view, &s_model, now_ms);
            s_last_view = view;
            s_last_elapsed_sec = elapsed;
            s_last_transfer_percent = (int)s_transfer_progress.percent;
            s_rendered_once = true;
        }
    }
}

/* 设备名：TXVR-<MAC 末两字节>（与 SDK HAL_BLE_esp 同一规则）。 */
static void init_device_name(void)
{
    uint8_t mac[6] = {0};
    if (esp_efuse_mac_get_default(mac) != ESP_OK) {
        return;
    }
    char name[16];
    snprintf(name, sizeof(name), "TXVR-%02X%02X", mac[4], mac[5]);
    ui_status_set_device_name(name);
}

esp_err_t app_ui_init(void)
{
    app_recorder_inbox_init(&s_inbox);
    app_ui_model_init(&s_model);
    init_device_name();
    BaseType_t ok = xTaskCreate(ui_task, "app_ui_task", UI_TASK_STACK, NULL,
                                UI_TASK_PRIORITY, &s_ui_task);
    return ok == pdPASS ? ESP_OK : ESP_ERR_NO_MEM;
}

/* SDK on_message：锁外 cJSON 解析，锁内 store，解锁后通知 UI task。 */
void app_ui_post_recorder_message(int error_code, const char *json_msg)
{
    app_recorder_message_t msg;
    if (!app_recorder_message_parse(error_code, json_msg, &msg)) {
        return; /* 未知/非法消息忽略 */
    }
    portENTER_CRITICAL(&s_model_lock);
    app_recorder_inbox_store(&s_inbox, &msg);
    portEXIT_CRITICAL(&s_model_lock);
    if (s_ui_task) {
        xTaskNotifyGive(s_ui_task);
    }
}

void app_ui_note_pipeline_started(uint64_t monotonic_ms)
{
    portENTER_CRITICAL(&s_model_lock);
    app_ui_model_note_pipeline_started(&s_model, monotonic_ms);
    portEXIT_CRITICAL(&s_model_lock);
}

void app_ui_set_local_error(app_recorder_error_source_t source,
                            const char *message, bool recoverable)
{
    portENTER_CRITICAL(&s_model_lock);
    app_ui_model_set_local_error(&s_model, source, message, recoverable);
    portEXIT_CRITICAL(&s_model_lock);
}

app_ui_button_action_t app_ui_get_button_action(void)
{
    const uint64_t now_ms = (uint64_t)esp_timer_get_time() / 1000u;
    app_ui_button_action_t action;
    portENTER_CRITICAL(&s_model_lock);
    action = app_ui_model_button_action(&s_model, now_ms);
    portEXIT_CRITICAL(&s_model_lock);
    return action;
}
