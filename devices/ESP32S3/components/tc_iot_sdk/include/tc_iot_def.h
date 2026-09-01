// Copyright (c) 2026 Tencent. All rights reserved.

#ifndef __TC_IOT_DEFINE_H__
#define __TC_IOT_DEFINE_H__

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
  TC_IOT_AUDIO_CODEC_UNKNOWN = 0,
  TC_IOT_AUDIO_CODEC_PCM = 1,
  TC_IOT_AUDIO_CODEC_AAC = 2,
  TC_IOT_AUDIO_CODEC_OPUS = 3,
  TC_IOT_AUDIO_CODEC_G722 = 4,
} tc_iot_audio_codec_e;

typedef enum {
  TC_IOT_AUDIO_SAMPLE_RATE_UNKNOWN = 0,
  TC_IOT_AUDIO_SAMPLE_RATE_8000 = 8000,
  TC_IOT_AUDIO_SAMPLE_RATE_16000 = 16000,
  TC_IOT_AUDIO_SAMPLE_RATE_32000 = 32000,
  TC_IOT_AUDIO_SAMPLE_RATE_48000 = 48000,
} tc_iot_audio_sample_rate_e;

typedef enum {
  TC_IOT_AUDIO_CHANNEL_UNKNOWN = 0,
  TC_IOT_AUDIO_CHANNEL_MONO = 1,
  TC_IOT_AUDIO_CHANNEL_STEREO = 2,
} tc_iot_audio_channel_e;

typedef enum {
  TC_IOT_VIDEO_CODEC_UNKNOWN = 0,
  TC_IOT_VIDEO_CODEC_H264 = 1,
  TC_IOT_VIDEO_CODEC_H265 = 2,
  TC_IOT_VIDEO_CODEC_MJPEG = 3,
} tc_iot_video_codec_e;

typedef enum {
  TC_IOT_VIDEO_FRAME_TYPE_UNKNOWN = 0,
  TC_IOT_VIDEO_FRAME_TYPE_IDR = 1,
  TC_IOT_VIDEO_FRAME_TYPE_P = 2,
} tc_iot_video_frame_type_e;

typedef enum {
  TC_IOT_VIDEO_ROTATION_0 = 0,
  TC_IOT_VIDEO_ROTATION_90 = 90,
  TC_IOT_VIDEO_ROTATION_180 = 180,
  TC_IOT_VIDEO_ROTATION_270 = 270,
} tc_iot_video_rotation_e;

typedef enum {
  TC_IOT_VIDEO_QUALITY_UNKNOWN = 0,
  TC_IOT_VIDEO_QUALITY_LD = 1,
  TC_IOT_VIDEO_QUALITY_SD = 2,
  TC_IOT_VIDEO_QUALITY_HD = 3,
  TC_IOT_VIDEO_QUALITY_FHD = 4,
} tc_iot_video_quality_e;

typedef enum {
  TC_IOT_MEDIA_CONTENT_UNKNOWN = 0,
  TC_IOT_MEDIA_CONTENT_AUDIO = 1,
  TC_IOT_MEDIA_CONTENT_VIDEO = 2,
  TC_IOT_MEDIA_CONTENT_AUDIO_VIDEO = 3,
} tc_iot_media_content_e;

typedef struct {
  tc_iot_video_codec_e codec;
  tc_iot_video_frame_type_e type;
  tc_iot_video_rotation_e rotation;
  uint32_t width;
  uint32_t height;
  uint8_t *data;
  size_t data_size;
  uint64_t pts_ms;
} tc_iot_video_frame;

// 离线录音文件固定帧时长（毫秒）。编码配置、送帧描述与 storage 持久化必须引用它。
#define TC_IOT_AUDIO_FRAME_DURATION_MS 60u

typedef struct {
  tc_iot_audio_codec_e codec;
  tc_iot_audio_sample_rate_e sample_rate;
  tc_iot_audio_channel_e channels;
  uint32_t frame_duration_ms;
  uint8_t *data;
  size_t data_size;
  uint64_t pts_ms;
} tc_iot_audio_frame;

#ifdef __cplusplus
}
#endif

#endif /* __TC_IOT_DEFINE_H__ */
