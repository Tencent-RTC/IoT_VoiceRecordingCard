idf_component_register(
    SRCS "audio_storage.c"
    INCLUDE_DIRS "include"
    REQUIRES fatfs sdmmc esp_driver_sdmmc esp_timer
)
