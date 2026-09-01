#include "app_power.h"
#include "board_hal.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "driver/rtc_io.h"
#include "esp_log.h"
#include "esp_pm.h"
#include "esp_sleep.h"
#include "esp_timer.h"
#include "hal/gpio_types.h"
#include "ui_status.h"

static const char *TAG = "app_power";

#define BATTERY_REFRESH_FALLBACK_MS (10 * 1000)
#define DISPLAY_DIM_TIMEOUT_MS (30 * 1000)
#define DISPLAY_ACTIVE_BRIGHTNESS 128
#define DISPLAY_DIM_BRIGHTNESS 32
#define DISPLAY_DIM_TIMEOUT_US (DISPLAY_DIM_TIMEOUT_MS * 1000ULL)
#define BATTERY_REFRESH_FALLBACK_US (BATTERY_REFRESH_FALLBACK_MS * 1000ULL)
#define DEEP_SLEEP_TIMEOUT_MS (5 * 60 * 1000)
#define DEEP_SLEEP_TIMEOUT_US (DEEP_SLEEP_TIMEOUT_MS * 1000ULL)

static bool s_display_dimmed;
static bool s_recording_pm_locked;
static bool s_recording_active;
static bool s_battery_charging;
static bool s_usb_powered;
static esp_pm_lock_handle_t s_cpu_freq_lock;
static esp_timer_handle_t s_display_dim_timer;
static esp_timer_handle_t s_deep_sleep_timer;
static esp_timer_handle_t s_battery_refresh_timer;

static void restart_display_dim_timer(void);
static void restart_deep_sleep_timer(void);

static bool is_external_powered(void)
{
    return s_battery_charging || s_usb_powered;
}

esp_err_t app_power_acquire_recording(void)
{
    if (s_recording_pm_locked) {
        return ESP_OK;
    }
    esp_err_t err = esp_pm_lock_acquire(s_cpu_freq_lock);
    if (err != ESP_OK) {
        return err;
    }
    s_recording_pm_locked = true;
    return ESP_OK;
}

void app_power_release_recording(void)
{
    if (!s_recording_pm_locked) {
        return;
    }
    (void)esp_pm_lock_release(s_cpu_freq_lock);
    s_recording_pm_locked = false;
}

void app_power_set_recording_active(bool active)
{
    s_recording_active = active;
    if (active) {
        /* 录音活动：恢复正常亮度，停止 dim/deep-sleep 计时。 */
        if (s_display_dimmed) {
            if (ui_status_set_brightness(DISPLAY_ACTIVE_BRIGHTNESS) == ESP_OK) {
                s_display_dimmed = false;
                ui_status_set_idle_dimmed(false);
            }
        }
        if (s_display_dim_timer) {
            (void)esp_timer_stop(s_display_dim_timer);
        }
        if (s_deep_sleep_timer) {
            (void)esp_timer_stop(s_deep_sleep_timer);
        }
    } else {
        restart_display_dim_timer();
        restart_deep_sleep_timer();
    }
}

static void restart_display_dim_timer(void)
{
    if (!s_display_dim_timer) {
        return;
    }
    (void)esp_timer_stop(s_display_dim_timer);
    if (!s_recording_active) {
        esp_err_t err = esp_timer_start_once(s_display_dim_timer, DISPLAY_DIM_TIMEOUT_US);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "start dim timer failed: %s", esp_err_to_name(err));
        }
    }
}

static void restart_deep_sleep_timer(void)
{
    if (!board_hal_deep_sleep_enabled()) {
        return;
    }
    if (!s_deep_sleep_timer) {
        return;
    }
    (void)esp_timer_stop(s_deep_sleep_timer);
    if (!s_recording_active && !is_external_powered()) {
        esp_err_t err = esp_timer_start_once(s_deep_sleep_timer, DEEP_SLEEP_TIMEOUT_US);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "start deep sleep timer failed: %s", esp_err_to_name(err));
        }
    } else if (is_external_powered()) {
        ESP_LOGD(TAG, "deep sleep timer paused while external power is present");
    }
}

void app_power_note_activity(void)
{
    if (s_display_dimmed) {
        esp_err_t err = ui_status_set_brightness(DISPLAY_ACTIVE_BRIGHTNESS);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "restore brightness failed: %s", esp_err_to_name(err));
        } else {
            s_display_dimmed = false;
            ui_status_set_idle_dimmed(false);
        }
    }
    restart_display_dim_timer();
    restart_deep_sleep_timer();
}

void app_power_enter_deep_sleep(void)
{
    if (s_recording_active) {
        restart_deep_sleep_timer();
        return;
    }
    if (is_external_powered()) {
        ESP_LOGI(TAG, "skip deep sleep while charging or USB powered");
        restart_deep_sleep_timer();
        return;
    }

    bool charging = false;
    bool usb_powered = false;
    esp_err_t power_err = board_hal_battery_charging(&charging);
    if (power_err == ESP_OK) {
        power_err = board_hal_usb_powered(&usb_powered);
    }
    if (power_err == ESP_OK && (charging || usb_powered)) {
        s_battery_charging = charging;
        s_usb_powered = usb_powered;
        ESP_LOGI(TAG, "skip deep sleep after fresh power check charging=%d usb=%d",
                 charging, usb_powered);
        restart_deep_sleep_timer();
        return;
    }

    const gpio_num_t wake_gpio = board_hal_primary_button_gpio();
    if (!esp_sleep_is_valid_wakeup_gpio(wake_gpio)) {
        ESP_LOGE(TAG, "GPIO%d cannot wake from deep sleep", wake_gpio);
        restart_deep_sleep_timer();
        return;
    }

    if (gpio_get_level(wake_gpio) == 0) {
        ESP_LOGI(TAG, "skip deep sleep: front button is pressed");
        restart_deep_sleep_timer();
        return;
    }

    ESP_LOGI(TAG, "entering deep sleep, wake on front button GPIO%d low (level=%d)",
             wake_gpio, gpio_get_level(wake_gpio));
    app_power_release_recording();
    ESP_ERROR_CHECK_WITHOUT_ABORT(ui_status_set_brightness(0));
    ui_status_prepare_deep_sleep();
    board_hal_prepare_deep_sleep();

    (void)esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_ALL);

    (void)esp_sleep_pd_config(ESP_PD_DOMAIN_RTC_PERIPH, ESP_PD_OPTION_ON);
    (void)rtc_gpio_pulldown_dis(wake_gpio);
    (void)rtc_gpio_pullup_en(wake_gpio);

    esp_err_t err = esp_sleep_enable_ext1_wakeup_io(1ULL << wake_gpio,
                                                    ESP_EXT1_WAKEUP_ANY_LOW);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "enable deep sleep wake failed: %s", esp_err_to_name(err));
        restart_deep_sleep_timer();
        return;
    }

    int wait_ms = 0;
    while (gpio_get_level(wake_gpio) == 0 && wait_ms < 200) {
        vTaskDelay(pdMS_TO_TICKS(10));
        wait_ms += 10;
    }
    if (gpio_get_level(wake_gpio) == 0) {
        ESP_LOGW(TAG, "front button still low after %d ms, abort deep sleep", wait_ms);
        restart_deep_sleep_timer();
        return;
    }

    ESP_LOGI(TAG, "deep sleep go (wait_ms=%d level=%d)", wait_ms,
             gpio_get_level(wake_gpio));
    esp_deep_sleep_start();
}

static void display_dim_timer_cb(void *arg)
{
    (void)arg;
    if (!s_display_dimmed && !s_recording_active) {
        esp_err_t err = ui_status_set_brightness(DISPLAY_DIM_BRIGHTNESS);
        if (err == ESP_OK) {
            s_display_dimmed = true;
            ui_status_set_idle_dimmed(true);
            ESP_LOGI(TAG, "display dimmed after inactivity");
        } else {
            ESP_LOGW(TAG, "dim display failed: %s", esp_err_to_name(err));
        }
    }
}

static esp_err_t init_display_dim_timer(void)
{
    const esp_timer_create_args_t timer_args = {
        .callback = display_dim_timer_cb,
        .name = "display_dim",
    };
    return esp_timer_create(&timer_args, &s_display_dim_timer);
}

static void deep_sleep_timer_cb(void *arg)
{
    (void)arg;
    app_power_enter_deep_sleep();
}

static esp_err_t init_deep_sleep_timer(void)
{
    const esp_timer_create_args_t timer_args = {
        .callback = deep_sleep_timer_cb,
        .name = "deep_sleep",
    };
    return esp_timer_create(&timer_args, &s_deep_sleep_timer);
}

static void battery_refresh_timer_cb(void *arg)
{
    (void)arg;
    app_power_refresh_battery();
}

esp_err_t app_power_start_battery_timer(void)
{
    const esp_timer_create_args_t timer_args = {
        .callback = battery_refresh_timer_cb,
        .name = "battery_refresh",
        .skip_unhandled_events = true,
    };
    esp_err_t err = esp_timer_create(&timer_args, &s_battery_refresh_timer);
    if (err != ESP_OK) {
        return err;
    }
    return esp_timer_start_periodic(s_battery_refresh_timer, BATTERY_REFRESH_FALLBACK_US);
}

void app_power_refresh_battery(void)
{
    /* 板级电源监测不可用时静默跳过（立创板无 PMIC） */
    int dummy = 0;
    if (board_hal_battery_level(&dummy) == ESP_ERR_NOT_SUPPORTED) {
        return;
    }

    uint8_t sys_status = 0;
    esp_err_t irq_err = board_hal_clear_power_irqs(&sys_status);
    if (irq_err == ESP_OK && sys_status) {
        ESP_LOGI(TAG, "PMIC sys irq=0x%02x", sys_status);
    }

    int level = 0;
    bool charging = false;
    bool usb_powered = false;
    esp_err_t err = board_hal_battery_level(&level);
    if (err == ESP_OK) {
        err = board_hal_battery_charging(&charging);
    }
    if (err == ESP_OK) {
        err = board_hal_usb_powered(&usb_powered);
    }
    if (err == ESP_OK) {
        const bool external_power_changed = (charging != s_battery_charging) ||
                                            (usb_powered != s_usb_powered);
        s_battery_charging = charging;
        s_usb_powered = usb_powered;
        ui_status_set_battery(level, charging, usb_powered);
        if (external_power_changed) {
            ESP_LOGI(TAG, "power source changed charging=%d usb=%d",
                     charging, usb_powered);
            restart_deep_sleep_timer();
        }
    } else {
        ESP_LOGW(TAG, "battery read failed: %s", esp_err_to_name(err));
    }
}

esp_err_t app_power_init(void)
{
    const esp_pm_config_t pm_config = {
        .max_freq_mhz = CONFIG_ESP_DEFAULT_CPU_FREQ_MHZ,
        .min_freq_mhz = CONFIG_XTAL_FREQ,
        .light_sleep_enable = false,
    };
    esp_err_t err = esp_pm_configure(&pm_config);
    if (err != ESP_OK) {
        return err;
    }
    err = esp_pm_lock_create(ESP_PM_CPU_FREQ_MAX, 0, "recording_cpu", &s_cpu_freq_lock);
    if (err != ESP_OK) {
        return err;
    }

    err = init_display_dim_timer();
    if (err != ESP_OK) {
        return err;
    }
    err = init_deep_sleep_timer();
    if (err != ESP_OK) {
        return err;
    }
    return ESP_OK;
}
