#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "driver/i2c_master.h"
#include "driver/i2s_std.h"
#include "esp_codec_dev.h"
#include "esp_err.h"
#include "esp_lcd_panel_io.h"
#include "hal/spi_types.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    i2s_chan_handle_t rx_handle;
    esp_codec_dev_handle_t codec_dev;
} board_hal_audio_t;

typedef struct {
    spi_host_device_t host;          /* SPI 外设（SPI2_HOST / SPI3_HOST） */
    int mosi_gpio;
    int sck_gpio;
    int dc_gpio;
    int cs_gpio;                     /* -1 表示 CS 走外部（PCA9557 预置拉低） */
    int rst_gpio;                    /* -1 表示无 RST */
    int bl_gpio;
    bool backlight_invert;           /* true 表示低电平亮，需 LEDC output_invert */
    int h_res;
    int v_res;
    int x_gap;
    int y_gap;
    int pixel_clock_hz;
    bool color_invert;               /* esp_lcd_panel_invert_color 参数 */
    int spi_mode;                    /* 0/2/3 */
    bool swap_xy;                    /* esp_lcd_panel_swap_xy，ST7789 原生 240x320 → 320x240 */
    bool mirror_x;                   /* esp_lcd_panel_mirror 第 1 参数 */
    bool mirror_y;                   /* esp_lcd_panel_mirror 第 2 参数 */
} board_lcd_config_t;

/* 基础初始化：I2C / PCA9557(立创) / 按键 GPIO */
esp_err_t board_hal_init(void);
i2c_master_bus_handle_t board_hal_i2c_bus(void);

/* 音频资源：板实现负责创建 I2S RX 通道 + ADC codec dev */
esp_err_t board_hal_audio_open(board_hal_audio_t *out);
void board_hal_audio_close(void);

/* 显示屏参数（分辨率/引脚/背光反相等由实现决定） */
const board_lcd_config_t *board_hal_lcd_config(void);

/* 按键 */
gpio_num_t board_hal_primary_button_gpio(void);
bool board_hal_primary_button_pressed(void);
bool board_hal_has_secondary_button(void);

/* 电源/休眠（立创板为空桩） */
esp_err_t board_hal_battery_level(int *level_percent);
esp_err_t board_hal_battery_charging(bool *charging);
esp_err_t board_hal_usb_powered(bool *usb_powered);
esp_err_t board_hal_clear_power_irqs(uint8_t *sys_status);
bool board_hal_deep_sleep_enabled(void);
void board_hal_prepare_deep_sleep(void);

#ifdef __cplusplus
}
#endif
