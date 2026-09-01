idf_component_register(
    SRCS "ui_status.c" "ui_status_icons.c"
    INCLUDE_DIRS "include" "."
    EMBED_FILES
        "assets/cat_pairing_argb8888.bin"
        "assets/cat_ready_argb8888.bin"
        "assets/cat_listening_argb8888.bin"
        "assets/cat_thinking_argb8888.bin"
        "assets/cat_error_argb8888.bin"
    REQUIRES esp_driver_ledc esp_driver_spi esp_lcd esp_timer board_hal lichuang_board lvgl
)
