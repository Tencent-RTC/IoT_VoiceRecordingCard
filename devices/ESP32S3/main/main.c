#include <string.h>

#include "esp_log.h"
#include "esp_system.h"
#include "esp_sleep.h"
#include "nvs_flash.h"

#include "app_audio.h"
#include "app_input.h"
#include "app_power.h"
#include "app_ui.h"
#include "audio_pipeline.h"
#include "audio_storage.h"
#include "board_hal.h"
#include "tc_iot_audio_recorder.h"
#include "ui_status.h"

static const char *TAG = "AudioRecordingCard";

/* ---- tc_iot audio recorder observer（回调运行在 IoT message loop） ---- */

/* App 远端 start：启动录音（PM 锁 + pipeline + 状态 + UI）。 */
static void on_rec_start(const char *params, void *user_data)
{
    (void)params;
    (void)user_data;
    if (!app_audio_start_recording())
    {
        /* pipeline 启动失败：记录错误，通过 stop 清理 SDK 录音状态。 */
        (void)tc_iot_audio_recorder_stop();
    }
}

/* App 远端 stop：请求 pipeline 异步停止。 */
static void on_rec_stop(const char *params, void *user_data)
{
    (void)params;
    (void)user_data;
    app_audio_request_stop();
}

/* SDK 消息：JSON 入 app_ui inbox，由 UI task 归并渲染（不阻塞、不直连 LVGL）。 */
static void on_rec_message(tc_iot_error_e error_code, const char *json_msg, void *user_data)
{
    (void)user_data;
    app_ui_post_recorder_message((int)error_code, json_msg);
}

static void on_log(tc_iot_log_level_e level, const char *log)
{
    switch (level)
    {
    case TC_IOT_LOG_LEVEL_ERROR:
        ESP_LOGE("[TC-IOT]", "%s", log);
        break;
    case TC_IOT_LOG_LEVEL_WARN:
        ESP_LOGW("[TC-IOT]", "%s", log);
        break;
    case TC_IOT_LOG_LEVEL_INFO:
        ESP_LOGI("[TC-IOT]", "%s", log);
        break;
    default:
        ESP_LOGD("[TC-IOT]", "%s", log);
        break;
    }
}

void app_main(void)
{
    ESP_LOGI(TAG, "boot reset_reason=%d wakeup_cause=%d ext1_status=0x%llx",
             esp_reset_reason(), esp_sleep_get_wakeup_cause(),
             (unsigned long long)esp_sleep_get_ext1_wakeup_status());

    /* NVS 是系统级通用能力，必须在任何可能用到的组件（BLE/WiFi/RF 校准/
     * 用户设置）之前初始化，统一放在 app_main 入口。 */
    esp_err_t nvs_err = nvs_flash_init();
    if (nvs_err == ESP_ERR_NVS_NO_FREE_PAGES || nvs_err == ESP_ERR_NVS_NEW_VERSION_FOUND)
    {
        ESP_ERROR_CHECK(nvs_flash_erase());
        nvs_err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(nvs_err);

    /* 1) 板级 / 电源 / UI 定时器。 */
    ESP_ERROR_CHECK(app_power_init());
    ESP_ERROR_CHECK(board_hal_init());
    ESP_ERROR_CHECK(ui_status_init());
    ESP_ERROR_CHECK(app_ui_init());

    /* 2) SD 挂载（文件内容与元数据由 SDK 写入，这里只保证卷已就绪）。 */
    esp_err_t storage_err = audio_storage_init();
    if (storage_err != ESP_OK)
    {
        ESP_LOGW(TAG, "SD card storage unavailable: %s", esp_err_to_name(storage_err));
    }

    /* 3) audio_pipeline：纯采集 + Opus 编码。 */
    esp_err_t audio_err = audio_pipeline_init();
    if (audio_err != ESP_OK)
    {
        ESP_LOGE(TAG, "audio init failed: %s", esp_err_to_name(audio_err));
        app_ui_set_local_error(APP_RECORDER_ERROR_INITIALIZATION, "Audio init failed", false);
    }

    /* 4) audio recorder（方案 C：自持 message loop，无需 tc_iot_init 云端栈）。 */
    tc_iot_audio_recorder_params_s recorder_params = {
        .storage_path = "/sdcard/voice",
        .log_level = TC_IOT_LOG_LEVEL_DEBUG,
        .on_log = on_log,
    };
    static tc_iot_audio_recorder_observer_s recorder_observer = {
        .on_audio_recorder_start = on_rec_start,
        .on_audio_recorder_stop = on_rec_stop,
        .on_message = on_rec_message,
    };
    tc_iot_error_e iot_err = tc_iot_audio_recorder_init(&recorder_params, &recorder_observer, NULL);
    if (iot_err != TC_IOT_ERR_SUCCESS)
    {
        ESP_LOGE(TAG, "audio recorder init failed: %d", (int)iot_err);
        app_ui_set_local_error(APP_RECORDER_ERROR_INITIALIZATION, "Recorder init failed", false);
        return;
    }

    /* 5) 注册 audio_pipeline 推送回调 → tc_iot audio recorder。 */
    app_audio_set_push_cb();

    app_power_note_activity();

    ESP_ERROR_CHECK(app_input_init());
    app_power_refresh_battery();
    ESP_ERROR_CHECK(app_power_start_battery_timer());

    ESP_LOGI(TAG, "tc_iot audio recorder stack booted");
}
