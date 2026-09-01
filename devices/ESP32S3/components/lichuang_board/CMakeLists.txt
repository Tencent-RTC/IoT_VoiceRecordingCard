idf_component_register(
    SRCS "lichuang_board.c" "pca9557.c"
    INCLUDE_DIRS "include" "."
    REQUIRES esp_driver_gpio esp_driver_i2c esp_driver_i2s esp_codec_dev board_hal
)
