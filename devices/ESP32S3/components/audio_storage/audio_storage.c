#include "audio_storage.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/unistd.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "esp_vfs_fat.h"
#include "sdmmc_cmd.h"
#include "driver/sdmmc_host.h"

static const char *TAG = "audio_storage";

#define BSP_SD_CLK       47
#define BSP_SD_CMD       48
#define BSP_SD_D0        21
#define MOUNT_POINT      "/sdcard"
#define VOICE_DIR        MOUNT_POINT "/voice"

static bool s_sd_ready = false;

typedef struct {
    FILE *fp;
    bool  failed;
    uint32_t frame_count;
    uint32_t recording_id;
    char  tmp_path[96];
} storage_handle_t;

/* audio_pipeline 保证同一时刻只有一个活跃 session，单槽足够 */
static storage_handle_t s_handle_pool[1];

static uint32_t s_crc32_table[256];
static bool s_crc32_init_done;

static void crc32_table_init(void) {
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int k = 0; k < 8; k++) {
            c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
        }
        s_crc32_table[i] = c;
    }
    s_crc32_init_done = true;
}

/* 计算文件完整 CRC-32（ISO-HDLC/zlib，与 java.util.zip.CRC32 一致）。
 * 成功返回 true 并写 *out_crc；fopen/fread 失败返回 false（不输出伪 CRC）。 */
static bool crc32_file(const char *path, uint32_t *out_crc) {
    if (!s_crc32_init_done) {
        crc32_table_init();
    }
    FILE *f = fopen(path, "rb");
    if (!f) {
        return false;
    }
    uint32_t crc = 0xFFFFFFFFu;
    uint8_t buf[512];
    size_t n;
    bool ok = true;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
        for (size_t i = 0; i < n; i++) {
            crc = (crc >> 8) ^ s_crc32_table[(crc ^ buf[i]) & 0xFFu];
        }
    }
    if (ferror(f)) {
        ok = false;
    }
    fclose(f);
    if (ok && out_crc) {
        *out_crc = crc ^ 0xFFFFFFFFu;
    }
    return ok;
}

/* 从文件名解析 id/seq/crc；非 "<id>_<seq>_<crc>.opus" 模式返回 false。
 * crc 为 8 位大写十六进制（如 _1A2B3C4D.opus）。 */
static bool parse_final_name(const char *name, uint32_t *id, uint32_t *seq,
                             uint32_t *crc) {
    unsigned u0, u1, u2;
    char ext[8];
    if (sscanf(name, "%u_%u_%08X.%7s", &u0, &u1, &u2, ext) != 4) {
        return false;
    }
    if (strcmp(ext, "opus") != 0) {
        return false;
    }
    *id = (uint32_t)u0;
    *seq = (uint32_t)u1;
    *crc = (uint32_t)u2;
    return true;
}

esp_err_t audio_storage_init(void)
{
    if (s_sd_ready) {
        return ESP_OK;
    }

    /* 第一次尝试：不格式化（保护用户数据） */
    esp_vfs_fat_sdmmc_mount_config_t mount_config = {
        .format_if_mount_failed = false,
        .max_files = 4,
        .allocation_unit_size = 16 * 1024,
    };

    sdmmc_host_t host = SDMMC_HOST_DEFAULT();
    sdmmc_slot_config_t slot_config = SDMMC_SLOT_CONFIG_DEFAULT();
    slot_config.width = 1;
    slot_config.clk = BSP_SD_CLK;
    slot_config.cmd = BSP_SD_CMD;
    slot_config.d0  = BSP_SD_D0;
    slot_config.flags |= SDMMC_SLOT_FLAG_INTERNAL_PULLUP;

    sdmmc_card_t *card = NULL;
    esp_err_t err = esp_vfs_fat_sdmmc_mount(MOUNT_POINT, &host, &slot_config,
                                            &mount_config, &card);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "mount failed: 0x%x", err);
        return err;
    }

    s_sd_ready = true;
    sdmmc_card_print_info(stdout, card);
    ESP_LOGI(TAG, "SD card mounted at %s", MOUNT_POINT);
    return ESP_OK;
}

void *audio_storage_session_begin(uint32_t session_id)
{
    if (!s_sd_ready) {
        return NULL;
    }

    /* mkdir /sdcard/voice；已存在会返回 -1/EEXIST，忽略 */
    mkdir(VOICE_DIR, 0777);

    char path[96];
    s_handle_pool[0].fp = NULL;

    /* O_CREAT|O_EXCL 防覆盖（design §6.6）：同名冲突时递增文件后缀重试，
     * 保证本地临时文件唯一性。最终文件名在 end 时由 recording_id/seq/crc 决定。 */
    for (int i = 0; i < 100; i++) {
        if (i == 0) {
            snprintf(path, sizeof(path), VOICE_DIR "/.rec_%u_%02d.opus.partial",
                     (unsigned)session_id, 0);
        } else {
            snprintf(path, sizeof(path), VOICE_DIR "/.rec_%u_%02d.opus.partial",
                     (unsigned)session_id, i);
        }
        int fd = open(path, O_WRONLY | O_CREAT | O_EXCL, 0644);
        if (fd >= 0) {
            s_handle_pool[0].fp = fdopen(fd, "wb");
            s_handle_pool[0].recording_id = session_id;
            s_handle_pool[0].frame_count = 0;
            snprintf(s_handle_pool[0].tmp_path, sizeof(s_handle_pool[0].tmp_path), "%s", path);
            break;
        }
        if (errno != EEXIST) {
            ESP_LOGW(TAG, "open %s failed: %s", path, strerror(errno));
            break;
        }
        /* EEXIST：冲突，下一轮用后缀重试 */
    }

    if (s_handle_pool[0].fp == NULL) {
        ESP_LOGW(TAG, "create recording file failed after retries");
        return NULL;
    }

    s_handle_pool[0].failed = false;
    s_handle_pool[0].frame_count = 0;
    ESP_LOGI(TAG, "session begin %s", path);
    return &s_handle_pool[0];
}

esp_err_t audio_storage_write(void *handle, const uint8_t *opus_payload, size_t len)
{
    if (handle == NULL) {
        return ESP_OK;
    }
    storage_handle_t *h = (storage_handle_t *)handle;
    if (h->failed) {
        return ESP_FAIL;
    }

    /* 帧格式：[2 字节小端长度][opus payload]
     * 便于后续从 SD 文件按帧切分并通过 BLE 重新发送给 PC。 */
    if (len > UINT16_MAX) {
        ESP_LOGW(TAG, "frame too large: %zu, aborting storage", len);
        h->failed = true;
        fclose(h->fp);
        h->fp = NULL;
        return ESP_FAIL;
    }
    uint16_t flen = (uint16_t)len;
    uint8_t hdr[2] = { (uint8_t)(flen & 0xff), (uint8_t)((flen >> 8) & 0xff) };

    size_t wh = fwrite(hdr, 1, sizeof(hdr), h->fp);
    size_t wp = fwrite(opus_payload, 1, len, h->fp);
    if (wh != sizeof(hdr) || wp != len) {
        ESP_LOGW(TAG, "fwrite short: hdr=%zu/%zu payload=%zu/%zu, aborting storage",
                 wh, sizeof(hdr), wp, len);
        h->failed = true;
        fclose(h->fp);
        h->fp = NULL;
        return ESP_FAIL;
    }
    h->frame_count++;
    return ESP_OK;
}

void audio_storage_session_end(void *handle, uint32_t frame_count)
{
    if (handle == NULL) {
        return;
    }
    storage_handle_t *h = (storage_handle_t *)handle;
    if (h->fp != NULL) {
        long pos = ftell(h->fp);
        fclose(h->fp);
        h->fp = NULL;
        ESP_LOGI(TAG, "session end, wrote %ld bytes", pos);
    }
    h->failed = false;
    h->frame_count = frame_count;
    if (frame_count == 0 || h->tmp_path[0] == '\0') {
        if (h->tmp_path[0] != '\0') {
            remove(h->tmp_path);
        }
        h->tmp_path[0] = '\0';
        ESP_LOGW(TAG, "empty recording discarded");
        return;
    }
    uint32_t seq_max = frame_count - 1;
    uint32_t crc = 0;
    bool crc_ok = crc32_file(h->tmp_path, &crc);
    if (!crc_ok) {
        ESP_LOGW(TAG, "crc32 failed for %s, marking crc=00000000", h->tmp_path);
        crc = 0;
    }
    char final_path[128];
    snprintf(final_path, sizeof(final_path), VOICE_DIR "/%u_%u_%08X.opus",
             (unsigned)h->recording_id, (unsigned)seq_max, (unsigned)crc);
    if (rename(h->tmp_path, final_path) != 0) {
        ESP_LOGW(TAG, "rename failed: %s -> %s", h->tmp_path, final_path);
    }
    h->tmp_path[0] = '\0';
    ESP_LOGI(TAG, "recording finalized %s", final_path);
}

/* qsort 比较函数：按 recording_id 降序（创建时间越晚越靠前）。
 * recording_id 即 Unix 时间戳，越大表示越晚创建。 */
static int file_info_cmp_by_id_desc(const void *a, const void *b)
{
    uint32_t id_a = ((const recorder_file_info_t *)a)->recording_id;
    uint32_t id_b = ((const recorder_file_info_t *)b)->recording_id;
    if (id_a < id_b) {
        return 1;
    }
    if (id_a > id_b) {
        return -1;
    }
    return 0;
}

int audio_storage_list(recorder_file_info_t *out, int cap)
{
    if (!s_sd_ready) {
        return -1;
    }
    DIR *d = opendir(VOICE_DIR);
    if (!d) {
        return -1;
    }
    /* 第一遍：仅计数。 */
    int total = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') {
            continue;
        }
        uint32_t id, seq, crc;
        if (!parse_final_name(e->d_name, &id, &seq, &crc)) {
            continue;
        }
        total++;
    }
    closedir(d);

    if (total <= 0) {
        return total;
    }
    if (out == NULL || cap <= 0) {
        return total;
    }

    /* 第二遍：收集全部文件到临时数组，排序后拷贝前 cap 个到 out。
     * 临时数组堆分配避免栈溢出（recorder_file_info_t ~96B，文件多时栈不够）。 */
    recorder_file_info_t *all = calloc((size_t)total, sizeof(recorder_file_info_t));
    if (all == NULL) {
        return -1;
    }
    d = opendir(VOICE_DIR);
    if (!d) {
        free(all);
        return -1;
    }
    int idx = 0;
    while ((e = readdir(d)) != NULL && idx < total) {
        if (e->d_name[0] == '.') {
            continue;
        }
        uint32_t id, seq, crc;
        if (!parse_final_name(e->d_name, &id, &seq, &crc)) {
            continue;
        }
        recorder_file_info_t *fi = &all[idx++];
        strlcpy(fi->file_name, e->d_name, sizeof(fi->file_name));
        fi->recording_id = id;
        fi->seq_max = seq;
        fi->duration_ms = (uint32_t)((uint64_t)(seq + 1) * 60u);
        fi->crc32 = crc;
        fi->file_size = 0;
        char p[300];
        snprintf(p, sizeof(p), VOICE_DIR "/%s", e->d_name);
        struct stat st;
        if (stat(p, &st) == 0) {
            fi->file_size = (uint64_t)st.st_size;
        }
    }
    closedir(d);

    qsort(all, (size_t)total, sizeof(recorder_file_info_t),
          file_info_cmp_by_id_desc);

    int n = total < cap ? total : cap;
    memcpy(out, all, (size_t)n * sizeof(recorder_file_info_t));
    free(all);
    return total;
}

void *audio_storage_open_read(const char *file_name, uint64_t offset)
{
    if (!s_sd_ready || file_name == NULL) {
        return NULL;
    }
    char p[300];
    snprintf(p, sizeof(p), VOICE_DIR "/%s", file_name);
    FILE *f = fopen(p, "rb");
    if (!f) {
        return NULL;
    }
    if (offset > 0 && fseeko(f, (off_t)offset, SEEK_SET) != 0) {
        fclose(f);
        return NULL;
    }
    return f;
}

int audio_storage_read(void *fh, uint8_t *buf, int n)
{
    if (fh == NULL || buf == NULL || n < 0) {
        return -1;
    }
    return (int)fread(buf, 1, (size_t)n, (FILE *)fh);
}

void audio_storage_close_read(void *fh)
{
    if (fh) {
        fclose((FILE *)fh);
    }
}

int audio_storage_delete(const char *file_name)
{
    if (!s_sd_ready || file_name == NULL) {
        return -1;
    }
    char p[300];
    snprintf(p, sizeof(p), VOICE_DIR "/%s", file_name);
    return remove(p) == 0 ? 0 : -1;
}
