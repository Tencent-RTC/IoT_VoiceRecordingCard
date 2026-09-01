#pragma once

#include "board_hal.h"

/* I2C 总线（与 PCA9557 / ES7210 / ES8311 共用） */
#define STICK_LC_PIN_I2C_SDA 1
#define STICK_LC_PIN_I2C_SCL 2

/* 按键 */
#define STICK_LC_PIN_BUTTON 0   /* BOOT, 低电平有效 */

/* ES7210 I2S（ADC/麦克风） */
#define STICK_LC_PIN_ES7210_MCLK 38
#define STICK_LC_PIN_ES7210_BCLK 14
#define STICK_LC_PIN_ES7210_WS   13
#define STICK_LC_PIN_ES7210_DIN  12

/* LCD SPI3 */
#define STICK_LC_PIN_LCD_MOSI 40
#define STICK_LC_PIN_LCD_SCK  41
#define STICK_LC_PIN_LCD_DC   39
#define STICK_LC_PIN_LCD_BL   42   /* 反相：低电平亮 */

#define STICK_LC_LCD_H_RES 320
#define STICK_LC_LCD_V_RES 240
#define STICK_LC_LCD_BACKLIGHT_INVERT true

/* PCA9557 */
#define STICK_LC_PCA9557_ADDR      0x19
#define STICK_LC_PCA9557_I2C_FREQ  100000

/* PCA9557 IO 引脚分配（bit 位）— 与 xiaozhi-esp32 立创派参考实现一致 */
#define STICK_LC_PCA9557_IO_LCD_CS   (1 << 0)   /* IO0, 输出，0 使能 LCD */
#define STICK_LC_PCA9557_IO_PA_EN    (1 << 1)   /* IO1, 输出，预留（功放/音频） */
#define STICK_LC_PCA9557_IO_DVP_PWDN (1 << 2)   /* IO2, 输出，1 关摄像头 */

/* board_hal 适配 — 由 lichuang_board.c 提供 */
esp_err_t lichuang_board_audio_open(board_hal_audio_t *out);
void lichuang_board_audio_close(void);
const board_lcd_config_t *lichuang_board_lcd_config(void);

/* LCD CS 钩子：在 ui_status.c 调用 esp_lcd_panel_reset 之后、esp_lcd_panel_init
 * 之前调用，把 PCA9557 IO0 拉低使能 LCD。对应 xiaozhi 的 SetOutputState(0, 0)。
 */
esp_err_t lichuang_board_lcd_enable_cs(void);
