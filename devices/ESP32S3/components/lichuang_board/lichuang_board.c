#include "lichuang_board.h"
#include "pca9557.h"

#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "driver/i2s_std.h"
#include "esp_check.h"
#include "esp_codec_dev.h"
#include "esp_codec_dev_defaults.h"
#include "esp_log.h"
#include "es7210_adc.h"

static const char *TAG = "lichuang_board";

static i2c_master_bus_handle_t s_i2c_bus;
static pca9557_t s_pca9557;

static const board_lcd_config_t s_lcd_config = {
    .host = SPI3_HOST,
    .mosi_gpio = STICK_LC_PIN_LCD_MOSI,
    .sck_gpio = STICK_LC_PIN_LCD_SCK,
    .dc_gpio = STICK_LC_PIN_LCD_DC,
    .cs_gpio = -1,                              /* PCA9557 IO0 预置拉低 */
    .rst_gpio = -1,                             /* NC */
    .bl_gpio = STICK_LC_PIN_LCD_BL,
    .backlight_invert = true,                   /* 低电平亮 */
    .h_res = STICK_LC_LCD_H_RES,
    .v_res = STICK_LC_LCD_V_RES,
    .x_gap = 0,
    .y_gap = 0,
    .pixel_clock_hz = 80 * 1000 * 1000,         /* 与 xiaozhi 一致 */
    .color_invert = true,                       /* ST7789 反色，与 xiaozhi 一致 */
    .spi_mode = 2,                              /* 与 xiaozhi 一致 */
    .swap_xy = true,                            /* 240x320 原生 → 320x240 横屏 */
    .mirror_x = true,
    .mirror_y = false,
};

static esp_err_t init_i2c(void)
{
    if (s_i2c_bus) {
        i2c_del_master_bus(s_i2c_bus);
        s_i2c_bus = NULL;
    }

    const i2c_master_bus_config_t bus_config = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = STICK_LC_PIN_I2C_SDA,
        .scl_io_num = STICK_LC_PIN_I2C_SCL,
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };
    return i2c_new_master_bus(&bus_config, &s_i2c_bus);
}

static esp_err_t init_pca9557(void)
{
    esp_err_t err = pca9557_init(&s_pca9557, s_i2c_bus,
                                 STICK_LC_PCA9557_ADDR,
                                 STICK_LC_PCA9557_I2C_FREQ);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PCA9557 init failed: %s", esp_err_to_name(err));
        return err;
    }

    /* 与 xiaozhi-esp32 立创派参考实现一致：
     *   OUTPUT_PORT(0x01) = 0x03  -> IO0=1, IO1=1（LCD_CS 暂时拉高，等 panel_reset 后再拉低）
     *   CONFIG(0x03)      = 0xf8  -> IO0/IO1/IO2 输出，IO3..IO7 输入
     */
    err = pca9557_write_reg(&s_pca9557, PCA9557_REG_OUTPUT_PORT, 0x03);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PCA9557 output reg write failed: %s", esp_err_to_name(err));
        return err;
    }
    err = pca9557_write_reg(&s_pca9557, PCA9557_REG_CONFIG, 0xf8);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PCA9557 config reg write failed: %s", esp_err_to_name(err));
        return err;
    }

    ESP_LOGI(TAG, "PCA9557 ready: IO0/IO1 out=1, IO2 out=0, IO3-IO7 input");
    return ESP_OK;
}

/* 在 LCD panel_reset 之后调用：拉低 LCD_CS（IO0）以使能屏幕。
 * xiaozhi 的初始化顺序：panel_reset -> SetOutputState(0, 0) -> panel_init。
 * 由于本项目的 panel_reset/init 都在 ui_status.c 里，这里提供一个钩子让
 * ui_status 在 reset 之后、init 之前调用。
 */
esp_err_t lichuang_board_lcd_enable_cs(void)
{
    return pca9557_update_reg(&s_pca9557, PCA9557_REG_OUTPUT_PORT,
                              STICK_LC_PCA9557_IO_LCD_CS, 0);
}

esp_err_t board_hal_init(void)
{
    esp_err_t err = init_i2c();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "I2C init failed: %s", esp_err_to_name(err));
        return err;
    }

    err = init_pca9557();
    if (err != ESP_OK) {
        return err;
    }

    const gpio_config_t button_config = {
        .pin_bit_mask = (1ULL << STICK_LC_PIN_BUTTON),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    return gpio_config(&button_config);
}

i2c_master_bus_handle_t board_hal_i2c_bus(void)
{
    return s_i2c_bus;
}

#define LC_AUDIO_SAMPLE_RATE 16000

static i2s_chan_handle_t s_rx_handle;
static esp_codec_dev_handle_t s_codec;
static const audio_codec_ctrl_if_t *s_ctrl_if;
static const audio_codec_data_if_t *s_data_if;
static const audio_codec_if_t *s_codec_if;

esp_err_t lichuang_board_audio_open(board_hal_audio_t *out)
{
    if (!out) {
        return ESP_ERR_INVALID_ARG;
    }

    /* I2S_NUM_0（spec 指定，与 StickS3 的 I2S_NUM_1 区分） */
    i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chan_cfg.auto_clear = true;
    ESP_RETURN_ON_ERROR(i2s_new_channel(&chan_cfg, NULL, &s_rx_handle),
                        TAG, "create i2s channel");

    i2s_std_config_t std_cfg = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(LC_AUDIO_SAMPLE_RATE),
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT,
                                                        I2S_SLOT_MODE_MONO),
        .gpio_cfg = {
            .mclk = STICK_LC_PIN_ES7210_MCLK,
            .bclk = STICK_LC_PIN_ES7210_BCLK,
            .ws = STICK_LC_PIN_ES7210_WS,
            .dout = -1,
            .din = STICK_LC_PIN_ES7210_DIN,
            .invert_flags = {
                .mclk_inv = false,
                .bclk_inv = false,
                .ws_inv = false,
            },
        },
    };
    std_cfg.clk_cfg.mclk_multiple = I2S_MCLK_MULTIPLE_256;

    esp_err_t err = i2s_channel_init_std_mode(s_rx_handle, &std_cfg);
    if (err != ESP_OK) {
        goto fail_i2s;
    }
    err = i2s_channel_enable(s_rx_handle);
    if (err != ESP_OK) {
        goto fail_i2s;
    }

    /* ES7210 I2C 控制：与 PCA9557 共用 I2C_NUM_0 总线。
     * 立创派板上 ES7210 的 8 位写地址为 0x82（7 位地址 0x41），
     * 与 ES7210_CODEC_DEFAULT_ADDR(0x80) 不同。esp_codec_dev 内部会 >>1，
     * 所以这里直接传 8 位地址 0x82。
     */
    audio_codec_i2c_cfg_t i2c_cfg = {
        .port = I2C_NUM_0,
        .addr = 0x82,
        .bus_handle = s_i2c_bus,
    };
    s_ctrl_if = audio_codec_new_i2c_ctrl(&i2c_cfg);
    if (!s_ctrl_if) {
        err = ESP_ERR_NO_MEM;
        goto fail_i2s;
    }

    audio_codec_i2s_cfg_t i2s_cfg = {
        .port = I2S_NUM_0,
        .rx_handle = s_rx_handle,
        .tx_handle = NULL,
    };
    s_data_if = audio_codec_new_i2s_data(&i2s_cfg);
    if (!s_data_if) {
        err = ESP_ERR_NO_MEM;
        goto fail_ctrl;
    }

    /* ES7210 单 MIC，从模式，MCLK 由 I2S master 提供 */
    es7210_codec_cfg_t es7210_cfg = {
        .ctrl_if = s_ctrl_if,
        .master_mode = false,
        .mic_selected = ES7210_SEL_MIC1,
        .mclk_src = ES7210_MCLK_FROM_PAD,
        .mclk_div = 256,
    };
    s_codec_if = es7210_codec_new(&es7210_cfg);
    if (!s_codec_if) {
        err = ESP_ERR_NO_MEM;
        goto fail_data;
    }

    esp_codec_dev_cfg_t dev_cfg = {
        .dev_type = ESP_CODEC_DEV_TYPE_IN,
        .codec_if = s_codec_if,
        .data_if = s_data_if,
    };
    s_codec = esp_codec_dev_new(&dev_cfg);
    if (!s_codec) {
        err = ESP_ERR_NO_MEM;
        goto fail_codec_if;
    }

    esp_codec_dev_sample_info_t sample_cfg = {
        .bits_per_sample = I2S_DATA_BIT_WIDTH_16BIT,
        .channel = 1,
        .channel_mask = I2S_STD_SLOT_LEFT,
        .sample_rate = LC_AUDIO_SAMPLE_RATE,
        .mclk_multiple = 0,
    };
    if (esp_codec_dev_open(s_codec, &sample_cfg) != ESP_CODEC_DEV_OK) {
        err = ESP_FAIL;
        goto fail_codec_dev;
    }
    /* 原 ES8311 设 36.0dB，ES7210 同样设 36.0（实测调参） */
    if (esp_codec_dev_set_in_gain(s_codec, 36.0) != ESP_CODEC_DEV_OK) {
        esp_codec_dev_close(s_codec);
        err = ESP_FAIL;
        goto fail_codec_dev;
    }

    out->rx_handle = s_rx_handle;
    out->codec_dev = s_codec;
    return ESP_OK;

fail_codec_dev:
    esp_codec_dev_delete(s_codec);
    s_codec = NULL;
fail_codec_if:
    audio_codec_delete_codec_if(s_codec_if);
    s_codec_if = NULL;
fail_data:
    audio_codec_delete_data_if(s_data_if);
    s_data_if = NULL;
fail_ctrl:
    audio_codec_delete_ctrl_if(s_ctrl_if);
    s_ctrl_if = NULL;
fail_i2s:
    if (s_rx_handle) {
        i2s_channel_disable(s_rx_handle);
        i2s_del_channel(s_rx_handle);
        s_rx_handle = NULL;
    }
    ESP_LOGE(TAG, "lichuang audio_open failed: %s", esp_err_to_name(err));
    return err;
}

void lichuang_board_audio_close(void)
{
    if (s_codec) {
        esp_codec_dev_close(s_codec);
        esp_codec_dev_delete(s_codec);
        s_codec = NULL;
    }
    if (s_codec_if) {
        audio_codec_delete_codec_if(s_codec_if);
        s_codec_if = NULL;
    }
    if (s_data_if) {
        audio_codec_delete_data_if(s_data_if);
        s_data_if = NULL;
    }
    if (s_ctrl_if) {
        audio_codec_delete_ctrl_if(s_ctrl_if);
        s_ctrl_if = NULL;
    }
    if (s_rx_handle) {
        i2s_del_channel(s_rx_handle);
        s_rx_handle = NULL;
    }
}

const board_lcd_config_t *lichuang_board_lcd_config(void)
{
    return &s_lcd_config;
}

esp_err_t board_hal_audio_open(board_hal_audio_t *out)
{
    return lichuang_board_audio_open(out);
}

void board_hal_audio_close(void)
{
    lichuang_board_audio_close();
}

const board_lcd_config_t *board_hal_lcd_config(void)
{
    return lichuang_board_lcd_config();
}

gpio_num_t board_hal_primary_button_gpio(void)
{
    return (gpio_num_t)STICK_LC_PIN_BUTTON;
}

bool board_hal_primary_button_pressed(void)
{
    return gpio_get_level(STICK_LC_PIN_BUTTON) == 0;
}

bool board_hal_has_secondary_button(void)
{
    return false;
}

esp_err_t board_hal_battery_level(int *level_percent)
{
    (void)level_percent;
    return ESP_ERR_NOT_SUPPORTED;
}

esp_err_t board_hal_battery_charging(bool *charging)
{
    (void)charging;
    return ESP_ERR_NOT_SUPPORTED;
}

esp_err_t board_hal_usb_powered(bool *usb_powered)
{
    (void)usb_powered;
    return ESP_ERR_NOT_SUPPORTED;
}

esp_err_t board_hal_clear_power_irqs(uint8_t *sys_status)
{
    (void)sys_status;
    return ESP_ERR_NOT_SUPPORTED;
}

bool board_hal_deep_sleep_enabled(void)
{
    return false;   /* 永不深睡，USB 常供电 */
}

void board_hal_prepare_deep_sleep(void)
{
    /* 空操作 */
}
