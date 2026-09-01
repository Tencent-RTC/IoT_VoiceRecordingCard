#pragma once

#include "driver/i2c_master.h"
#include "esp_err.h"
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    i2c_master_dev_handle_t dev;
} pca9557_t;

/* PCA9557 寄存器 */
#define PCA9557_REG_INPUT_PORT  0x00
#define PCA9557_REG_OUTPUT_PORT 0x01
#define PCA9557_REG_POLARITY    0x02
#define PCA9557_REG_CONFIG      0x03

esp_err_t pca9557_init(pca9557_t *pca, i2c_master_bus_handle_t bus,
                       uint8_t dev_addr, uint32_t scl_speed_hz);
esp_err_t pca9557_write_reg(pca9557_t *pca, uint8_t reg, uint8_t value);
esp_err_t pca9557_read_reg(pca9557_t *pca, uint8_t reg, uint8_t *value);
esp_err_t pca9557_update_reg(pca9557_t *pca, uint8_t reg,
                             uint8_t clear_mask, uint8_t set_mask);

#ifdef __cplusplus
}
#endif
