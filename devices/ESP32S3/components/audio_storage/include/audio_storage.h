#pragma once

#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"

/* 文件名缓冲大小（含结尾 '\0'） */
#define AUDIO_STORAGE_FILE_NAME_MAX_SIZE 64u

#ifdef __cplusplus
extern "C" {
#endif

/* 启动时调用一次：挂载 SD 卡到 /sdcard。
 * 成功返回 ESP_OK；失败返回非 OK，调用方应忽略并继续启动。
 * 多次调用安全（内部有 s_initialized 守卫）。
 */
esp_err_t audio_storage_init(void);

/* 一条离线录音文件的元数据（从文件名解析，无索引文件）。
 * 此类型是离线文件同步的统一信息结构：audio_storage 生产，recorder_session
 * 通过 recorder_file_ops_t vtable 消费（recorder_session.h include 本头以复用）。 */
typedef struct {
    char     file_name[AUDIO_STORAGE_FILE_NAME_MAX_SIZE]; /* "<id>_<seq>_<crc>.opus" */
    uint32_t recording_id;                                /* created_time（秒） */
    uint32_t seq_max;                                     /* 本地音频帧数 - 1 */
    uint32_t duration_ms;                                 /* (seq_max+1) * 60 */
    uint64_t file_size;                                   /* 文件字节数 */
    uint32_t crc32;                                       /* zlib CRC-32 */
} recorder_file_info_t;

/* 开启新会话文件（临时文件）。返回非 NULL handle 表示本次录音落盘；
 * 返回 NULL 表示 SD 不可用，调用方应停止调用 write/end。 */
void *audio_storage_session_begin(uint32_t session_id);

/* 写一帧 opus 包到当前会话文件。handle 为 NULL 时静默 no-op。 */
esp_err_t audio_storage_write(void *handle, const uint8_t *opus_payload, size_t len);

/* 关闭会话文件并重命名为 <recording_id>_<seq_max>_<crc32>.opus
 * （seq_max = frame_count - 1）。frame_count==0 时丢弃临时文件。
 * handle 为 NULL 时 no-op。调用后 handle 不再有效。 */
void audio_storage_session_end(void *handle, uint32_t frame_count);

/* 列出 VOICE_DIR 下所有符合 "<id>_<seq>_<crc>.opus" 的文件。
 * out==NULL 或 cap==0 时仅返回条数；否则填充最多 cap 条。
 * 返回条数；出错返回负值。 */
int audio_storage_list(recorder_file_info_t *out, int cap);

/* 打开文件读取句柄，定位到 offset。失败返回 NULL。 */
void *audio_storage_open_read(const char *file_name, uint64_t offset);
/* 读 n 字节，返回实际读取字节数；出错返回负值。 */
int audio_storage_read(void *fh, uint8_t *buf, int n);
void audio_storage_close_read(void *fh);

/* 删除文件。成功返回 0；失败返回负值。 */
int audio_storage_delete(const char *file_name);

#ifdef __cplusplus
}
#endif
