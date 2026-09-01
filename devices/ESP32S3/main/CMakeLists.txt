idf_component_register(
    SRCS "main.c" "app_ui.c" "app_power.c" "app_audio.c" "app_input.c" "app_recorder_message.c" "app_ui_model.c"
    INCLUDE_DIRS "."
    REQUIRES lichuang_board tc_iot_sdk audio_pipeline audio_storage ui_status button esp_pm esp_timer nvs_flash efuse espressif__cjson
)
