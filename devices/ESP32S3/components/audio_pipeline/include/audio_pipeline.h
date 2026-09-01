#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/* 供上层注入的回调，由 main 接线时把录音业务实现赋给本组件。
 * audio_pipeline 只负责采集与 Opus 编码，不依赖业务实现，只依赖这些函数指针。 */

/* 上报一帧编码完成的裸 Opus 包。audio 只产生并上报，不阻塞、不关心是否被接纳。
 * 返回 true 表示已接纳；false 表示不在推送状态，audio 继续下一帧，不重试、不丢弃。 */
typedef bool (*audio_push_cb_t)(const uint8_t *opus, size_t len);

/* 初始化。仅分配一次资源，会话资源按需分配。 */
esp_err_t audio_pipeline_init(void);

/* 注册推送回调；在 audio_pipeline_init 之后调用。 */
void audio_pipeline_set_push_cb(audio_push_cb_t push);

/* 启动采集+编码。 */
esp_err_t audio_pipeline_start(void);

/* 请求异步停止。立即返回；audio_task 完成当前帧、释放会话资源后，
 * 调用 on_stopped 通知上层。 */
typedef void (*audio_stopped_cb_t)(void);
void audio_pipeline_request_stop(audio_stopped_cb_t on_stopped);

#ifdef __cplusplus
}
#endif
