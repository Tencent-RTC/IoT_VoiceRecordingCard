#include "esp_log.h"
#include "esp_timer.h"

#include "app_audio.h"
#include "app_power.h"
#include "app_ui.h"
#include "audio_pipeline.h"
#include "tc_iot_audio_recorder.h"

static const char *TAG = "app_audio";

/* 启动录音（BOOT 本地 start 成功回调与 App 远端 start observer 共用）。
 * 失败时已释放 PM 锁并注入本地错误，返回 false 让调用方清理 SDK 录音状态。 */
bool app_audio_start_recording(void)
{
    esp_err_t err = app_power_acquire_recording();
    if (err != ESP_OK) {
        app_ui_set_local_error(APP_RECORDER_ERROR_RECORDING, "Power lock failed", true);
        return false;
    }
    err = audio_pipeline_start();
    if (err != ESP_OK) {
        app_power_release_recording();
        ESP_LOGE(TAG, "audio start failed: %s", esp_err_to_name(err));
        app_ui_set_local_error(APP_RECORDER_ERROR_RECORDING, "Audio start failed", true);
        return false;
    }
    app_ui_note_pipeline_started((uint64_t)esp_timer_get_time() / 1000u);
    return true;
}

/* audio 异步停止完成回调（audio_task 线程）：先停止 SDK（落盘/StopResult），
 * 再释放 PM 锁。UI 转 idle 由 SDK snapshot 驱动，不在此直接改 UI。 */
static void on_audio_stopped(void)
{
    (void)tc_iot_audio_recorder_stop();
    app_power_release_recording();
}

void app_audio_request_stop(void)
{
    audio_pipeline_request_stop(on_audio_stopped);
}

/* BOOT 本地 start 的操作回调（运行在 IoT message loop）：成功后启动录音。 */
static void boot_start_cb(tc_iot_error_e error_code, const char *error_message)
{
    if (error_code != TC_IOT_ERR_SUCCESS) {
        ESP_LOGE(TAG, "boot recorder start failed: %d msg=%s",
                 (int)error_code, error_message ? error_message : "");
        app_ui_set_local_error(APP_RECORDER_ERROR_RECORDING, "Recorder start failed", true);
        return;
    }
    if (!app_audio_start_recording()) {
        (void)tc_iot_audio_recorder_stop();
    }
}

void app_audio_boot_start(void)
{
    tc_iot_error_e err = tc_iot_audio_recorder_start(boot_start_cb);
    if (err != TC_IOT_ERR_SUCCESS) {
        ESP_LOGE(TAG, "boot recorder start submit failed: %d", (int)err);
        app_ui_set_local_error(APP_RECORDER_ERROR_RECORDING, "Recorder start failed", true);
    }
}

/* audio_pipeline push_cb：audio_task 上报一帧裸 Opus，构造 tc_iot_audio_frame
 * 交给 SDK（落盘 + 远端实时推送由 SDK 负责）。 */
static bool audio_push_cb(const uint8_t *opus, size_t len)
{
    const tc_iot_audio_frame frame = {
        .codec = TC_IOT_AUDIO_CODEC_OPUS,
        .sample_rate = TC_IOT_AUDIO_SAMPLE_RATE_16000,
        .channels = TC_IOT_AUDIO_CHANNEL_MONO,
        .frame_duration_ms = TC_IOT_AUDIO_FRAME_DURATION_MS,
        .data = (uint8_t *)opus,
        .data_size = len,
    };
    return tc_iot_audio_recorder_send_audio(&frame) == TC_IOT_ERR_SUCCESS;
}

void app_audio_set_push_cb(void)
{
    audio_pipeline_set_push_cb(audio_push_cb);
}
