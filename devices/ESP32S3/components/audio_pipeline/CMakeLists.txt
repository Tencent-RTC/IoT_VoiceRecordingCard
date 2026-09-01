idf_component_register(
    SRCS "audio_pipeline.c"
    INCLUDE_DIRS "include"
    REQUIRES board_hal esp_codec_dev jason-mao__av_processor
)
