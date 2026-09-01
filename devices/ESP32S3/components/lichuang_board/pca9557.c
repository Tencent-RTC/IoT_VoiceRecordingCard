#include "pca9557.h"

#include "esp_log.h"

static const char *TAG = "pca9557";

esp_err_t pca9557_init(pca9557_t *pca, i2c_master_bus_handle_t bus,
                       uint8_t dev_addr, uint32_t scl_speed_hz)
{
    if (!pca || !bus) {
        return ESP_ERR_INVALID_ARG;
    }

    const i2c_device_config_t dev_config = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = dev_addr,
        .scl_speed_hz = scl_speed_hz,
    };
    return i2c_master_bus_add_device(bus, &dev_config, &pca->dev);
}

esp_err_t pca9557_write_reg(pca9557_t *pca, uint8_t reg, uint8_t value)
{
    if (!pca || !pca->dev) {
        return ESP_ERR_INVALID_STATE;
    }
    const uint8_t data[] = {reg, value};
    return i2c_master_transmit(pca->dev, data, sizeof(data), 100);
}

esp_err_t pca9557_read_reg(pca9557_t *pca, uint8_t reg, uint8_t *value)
{
    if (!pca || !pca->dev || !value) {
        return ESP_ERR_INVALID_ARG;
    }
    return i2c_master_transmit_receive(pca->dev, &reg, 1, value, 1, 100);
}

esp_err_t pca9557_update_reg(pca9557_t *pca, uint8_t reg,
                             uint8_t clear_mask, uint8_t set_mask)
{
    uint8_t value = 0;
    esp_err_t err = pca9557_read_reg(pca, reg, &value);
    if (err != ESP_OK) {
        return err;
    }
    value &= ~clear_mask;
    value |= set_mask;
    return pca9557_write_reg(pca, reg, value);
}
