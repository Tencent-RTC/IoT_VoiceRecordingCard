#include "audio_pipeline.h"

#include <inttypes.h>
#include <stdatomic.h>

#include "esp_check.h"
#include "esp_codec_dev.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "audio_processor.h"
#include "board_hal.h"

static const char *TAG = "audio_pipeline";

#define AUDIO_SAMPLE_RATE 16000
#define AUDIO_SAMPLE_BITS 16
#define AUDIO_CHANNELS 1
#define AUDIO_FRAME_MS 60
#define OPUS_BITRATE 20000
#define OPUS_MAX_PACKET_SIZE 220

#define TASK_EXIT_WAIT_MS 800

static atomic_bool s_running;
static bool s_initialized;
static bool s_manager_initialized;
static TaskHandle_t s_audio_task;

static esp_codec_dev_handle_t s_codec;
static audio_recorder_handle_t s_recorder;

static audio_push_cb_t s_push_cb;
static audio_stopped_cb_t s_on_stopped;

static esp_err_t rec_io_read_cb(uint8_t *data, int data_size, void *ctx);
static void audio_task(void *arg);
static void deinit_session_resources(void);

esp_err_t audio_pipeline_init(void)
{
    if (s_initialized) {
        return ESP_OK;
    }

    s_initialized = true;
    ESP_LOGI(TAG, "audio pipeline ready (resources allocated on demand)");
    return ESP_OK;
}

void audio_pipeline_set_push_cb(audio_push_cb_t push)
{
    s_push_cb = push;
}

static esp_err_t rec_io_read_cb(uint8_t *data, int data_size, void *ctx)
{
    if (ctx == NULL || data == NULL || data_size <= 0) {
        return ESP_ERR_INVALID_ARG;
    }

    esp_codec_dev_handle_t codec = (esp_codec_dev_handle_t)ctx;
    esp_err_t err = esp_codec_dev_read(codec, data, data_size);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "codec read failed: %s", esp_err_to_name(err));
        return ESP_FAIL;
    }
    return ESP_OK;
}

esp_err_t audio_pipeline_start(void)
{
    ESP_RETURN_ON_FALSE(s_initialized, ESP_ERR_INVALID_STATE, TAG, "not initialized");
    if (atomic_load(&s_running)) {
        return ESP_OK;
    }

    TickType_t deadline = xTaskGetTickCount() + pdMS_TO_TICKS(TASK_EXIT_WAIT_MS);
    while (s_audio_task != NULL && xTaskGetTickCount() < deadline) {
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    if (s_audio_task != NULL) {
        ESP_LOGW(TAG, "previous session still exiting");
        return ESP_ERR_TIMEOUT;
    }

    board_hal_audio_t audio = {0};
    esp_err_t err = board_hal_audio_open(&audio);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "board_hal_audio_open: %s", esp_err_to_name(err));
        return err;
    }
    s_codec = audio.codec_dev;

    audio_manager_config_t manager_config = DEFAULT_AUDIO_MANAGER_CONFIG();
    manager_config.rec_io.read_cb = rec_io_read_cb;
    manager_config.rec_io.read_ctx = s_codec;
    audio_manager_config_set_rec_io_format(&manager_config, AUDIO_SAMPLE_RATE,
                                           AUDIO_SAMPLE_BITS, AUDIO_CHANNELS);
    err = audio_manager_init(&manager_config);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "audio_manager_init: %s", esp_err_to_name(err));
        deinit_session_resources();
        return err;
    }
    s_manager_initialized = true;

    audio_recorder_config_t recorder_config = DEFAULT_AUDIO_RECORDER_CONFIG();
#ifdef CONFIG_AUDIO_PIPELINE_ENABLE_AFE
    av_processor_afe_config_t afe_config = DEFAULT_AV_PROCESSOR_AFE_CONFIG();
    recorder_config.afe_config = afe_config;
    recorder_config.afe_config.frontend_type = AV_PROCESSOR_FRONTEND_TYPE_AFE;
    recorder_config.afe_config.mode.afe.afe_type = AFE_TYPE_VC;
    recorder_config.afe_config.mode.afe.afe_mode = AFE_MODE_HIGH_PERF;
#endif
    recorder_config.encoder_cfg.format = AV_PROCESSOR_FORMAT_ID_OPUS;
    recorder_config.encoder_cfg.params.opus.audio_info.sample_rate = AUDIO_SAMPLE_RATE;
    recorder_config.encoder_cfg.params.opus.audio_info.sample_bits = AUDIO_SAMPLE_BITS;
    recorder_config.encoder_cfg.params.opus.audio_info.channels = AUDIO_CHANNELS;
    recorder_config.encoder_cfg.params.opus.audio_info.frame_duration = AUDIO_FRAME_MS;
    recorder_config.encoder_cfg.params.opus.enable_vbr = true;
    recorder_config.encoder_cfg.params.opus.bitrate = OPUS_BITRATE;

    err = audio_recorder_open(&recorder_config, &s_recorder);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "audio_recorder_open: %s", esp_err_to_name(err));
        deinit_session_resources();
        return err;
    }

    s_on_stopped = NULL;
    atomic_store(&s_running, true);

    BaseType_t ok = xTaskCreatePinnedToCore(audio_task, "audio_pipeline", 8192,
                                            NULL, 5, &s_audio_task, 1);
    if (ok != pdPASS) {
        ESP_LOGE(TAG, "create audio_task failed (free internal=%" PRIu32 "B)",
                 (uint32_t)esp_get_free_internal_heap_size());
        atomic_store(&s_running, false);
        s_audio_task = NULL;
        deinit_session_resources();
        return ESP_ERR_NO_MEM;
    }

    ESP_LOGI(TAG, "capture started");
    return ESP_OK;
}

static void audio_task(void *arg)
{
    (void)arg;
    uint8_t opus_buf[OPUS_MAX_PACKET_SIZE];

    while (atomic_load(&s_running)) {
        int ret = audio_recorder_read_data(s_recorder, opus_buf, sizeof(opus_buf));
        if (ret > 0) {
            if (s_push_cb) {
                s_push_cb(opus_buf, (size_t)ret);
            }
        } else if (ret == 0) {
            vTaskDelay(pdMS_TO_TICKS(1));
        } else {
            ESP_LOGW(TAG, "recorder read failed: %d", ret);
            vTaskDelay(pdMS_TO_TICKS(20));
        }
    }

    ESP_LOGI(TAG, "audio task exit");

    deinit_session_resources();

    s_audio_task = NULL;

    audio_stopped_cb_t cb = s_on_stopped;
    s_on_stopped = NULL;
    if (cb) {
        cb();
    }

    vTaskDelete(NULL);
}

static void deinit_session_resources(void)
{
    if (s_recorder != NULL) {
        esp_err_t err = audio_recorder_close(s_recorder);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "audio_recorder_close: %s", esp_err_to_name(err));
        }
        s_recorder = NULL;
    }
    if (s_manager_initialized) {
        esp_err_t err = audio_manager_deinit();
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "audio_manager_deinit: %s", esp_err_to_name(err));
        }
        s_manager_initialized = false;
    }
    if (s_codec != NULL) {
        board_hal_audio_close();
        s_codec = NULL;
    }
    ESP_LOGI(TAG, "session resources released");
}

void audio_pipeline_request_stop(audio_stopped_cb_t on_stopped)
{
    s_on_stopped = on_stopped;
    if (!atomic_load(&s_running)) {
        if (s_on_stopped) {
            audio_stopped_cb_t cb = s_on_stopped;
            s_on_stopped = NULL;
            cb();
        }
        return;
    }
    atomic_store(&s_running, false);
    if (s_audio_task != NULL) {
        xTaskAbortDelay(s_audio_task);
    }
    ESP_LOGI(TAG, "request stop");
}
