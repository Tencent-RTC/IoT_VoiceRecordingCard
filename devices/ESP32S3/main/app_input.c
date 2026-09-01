#include "app_audio.h"
#include "app_input.h"
#include "app_power.h"
#include "app_ui.h"
#include "board_hal.h"
#include "button_gpio.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "hal/gpio_types.h"
#include "iot_button.h"

static const char *TAG = "app_input";

typedef enum {
    APP_EVENT_FRONT_PRESSED,
    APP_EVENT_BATTERY_REFRESH,
    APP_EVENT_ENTER_DEEP_SLEEP,
} app_event_type_t;

typedef struct {
    app_event_type_t type;
} app_event_t;

static QueueHandle_t s_app_event_queue;
static button_handle_t s_front_button;

static void queue_app_event(app_event_type_t type)
{
    if (s_app_event_queue) {
        app_event_t event = { .type = type };
        (void)xQueueSend(s_app_event_queue, &event, 0);
    }
}

static void front_button_down_cb(void *button_handle, void *usr_data)
{
    (void)button_handle;
    (void)usr_data;
    queue_app_event(APP_EVENT_FRONT_PRESSED);
}

static void app_event_task(void *arg)
{
    (void)arg;
    app_event_t event;
    while (true) {
        if (xQueueReceive(s_app_event_queue, &event, portMAX_DELAY) != pdTRUE) {
            continue;
        }
        switch (event.type) {
        case APP_EVENT_FRONT_PRESSED:
            ESP_LOGI(TAG, "button front pressed");
            app_power_note_activity();
            /* 按键动作由 UI model 决策：START→本地录音，STOP→停止，IGNORE→忽略。 */
            switch (app_ui_get_button_action()) {
            case APP_UI_BUTTON_START:
                app_audio_boot_start();
                break;
            case APP_UI_BUTTON_STOP:
                app_audio_request_stop();
                break;
            case APP_UI_BUTTON_IGNORE:
            default:
                break;
            }
            break;
        case APP_EVENT_BATTERY_REFRESH:
            app_power_refresh_battery();
            break;
        case APP_EVENT_ENTER_DEEP_SLEEP:
            app_power_enter_deep_sleep();
            break;
        }
    }
}

static esp_err_t init_gpio_button(gpio_num_t gpio_num, button_handle_t *button)
{
    const button_config_t button_config = {0};
    const button_gpio_config_t gpio_config = {
        .gpio_num = gpio_num,
        .active_level = 0,
        .enable_power_save = true
    };
    return iot_button_new_gpio_device(&button_config, &gpio_config, button);
}

esp_err_t app_input_init(void)
{
    s_app_event_queue = xQueueCreate(8, sizeof(app_event_t));
    if (!s_app_event_queue) {
        return ESP_ERR_NO_MEM;
    }

    esp_err_t err = init_gpio_button(board_hal_primary_button_gpio(), &s_front_button);
    if (err != ESP_OK) {
        return err;
    }

    err = iot_button_register_cb(s_front_button, BUTTON_PRESS_DOWN, NULL,
                                 front_button_down_cb, NULL);
    if (err != ESP_OK) {
        return err;
    }

    BaseType_t ok = xTaskCreate(app_event_task, "app_event_task", 4096,
                                NULL, 6, NULL);
    return ok == pdPASS ? ESP_OK : ESP_ERR_NO_MEM;
}
