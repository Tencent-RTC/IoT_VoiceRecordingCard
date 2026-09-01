# AudioRecordingCard 录音卡片

基于 **立创实战派 ESP32S3 开发板** 的智能录音卡片固件：设备通过蓝牙与手机 App
配对，支持一键录音、录音实时上传、历史录音下载回放。屏幕以卡通表情直观展示
设备状态。

## 1. 准备清单

| 物品 | 说明 |
|---|---|
| 立创实战派 ESP32S3 开发板 | 板载 320x240 彩屏、麦克风、BOOT 按键 |
| USB 数据线（USB-C） | 必须是**数据线**，纯充电线无法烧录 |
| microSD 卡（可选） | 录音文件保存位置，不插卡时仅支持实时上传 |
| 手机 | 安装配套的录音卡 App |
| 电脑 | Windows / macOS / Linux 均可 |

## 2. 快速体验：直接烧录现成固件（免编译）

`bin/` 目录提供已编译好的**完整 Flash 镜像**，无需安装任何开发环境，几分钟
即可烧录体验：

```
bin/AudioRecordingCard_flash_v0.3.2.bin    # 完整镜像（bootloader + 分区表 + 应用 + 语音模型）
```

### 方式一：Flash Download Tool 图形界面（Windows 推荐）

1. 从乐鑫官网下载 **Flash Download Tool**：
   <https://www.espressif.com/zh-hans/support/download/other-tools>
2. 解压并运行 `flash_download_tool.exe`，Chip Type 选 **ESP32S3**，
   WorkMode 选 **Develop**，点 OK
3. 勾选第一个文件行，选择 `bin/` 下的固件镜像，右侧地址栏填 **0x0**
4. SPI Mode 选 **DIO**，选好 COM 口（插上开发板后自动出现），点 **START**
5. 进度条走完显示 FINISH，设备自动重启进入系统

> 若开发板之前烧录过其他固件，建议先点 **ERASE** 整片擦除后再烧录。

### 方式二：esptool 命令行（已安装 ESP-IDF 或 Python esptool）

```bash
esptool.py --chip esp32s3 -p <串口> --baud 460800 write_flash 0x0 bin/AudioRecordingCard_flash_v0.3.2.bin
```

> 若提示找不到 `esptool.py`，尝试直接用 `esptool` 命令。
> 镜像约 7MB（含语音模型），烧录约 2~4 分钟属正常现象。

烧录完成后，直接跳到第 5 章「上电使用」开始体验；想做二次开发再阅读
第 3、4 章。

## 3. 安装 ESP-IDF 开发环境（版本 6.2）

本项目基于乐鑫官方 ESP-IDF 框架开发，**版本要求 6.2**：

1. 打开乐鑫官方入门教程：<https://docs.espressif.com/projects/esp-idf/zh_CN/v6.2/esp32s3/get-started/>
2. 按文档安装 ESP-IDF v6.2 及工具链：
   - Windows：下载「ESP-IDF 安装器」一路下一步，完成后桌面会出现 **ESP-IDF 终端**
   - macOS / Linux：按文档执行 `install.sh`，之后在终端执行 `. $HOME/esp/esp-idf/export.sh` 激活环境
3. 验证安装：在 ESP-IDF 终端中执行 `idf.py --version`，应输出 6.2

> 后续所有命令都在「ESP-IDF 终端」（或已执行 export.sh 的终端）中输入。

## 4. 编译与烧录

在终端中进入本工程目录（即本 README 所在目录），依次执行：

```bash
idf.py set-target esp32s3        # 第 1 步：指定芯片型号（仅需一次）
idf.py build                     # 第 2 步：编译（首次会联网下载依赖，需几分钟）
idf.py -p <串口> flash monitor   # 第 3 步：烧录并打开串口监视器
```

`<串口>` 的查看方式：

| 系统 | 串口 | 查看方法 |
|---|---|---|
| macOS | `/dev/cu.usbmodem*` | `ls /dev/cu.usbmodem*` |
| Windows | `COMx` | 设备管理器 → 端口(COM 和 LPT) |
| Linux | `/dev/ttyACM0` | `ls /dev/ttyACM*`；若无权限执行 `sudo usermod -aG dialout $USER` 后重新登录 |

烧录完成后设备自动重启，串口监视器中可见启动日志（按 `Ctrl+]` 退出监视器）。

## 5. 上电使用

### 5.1 连接手机 App

1. 用 USB 线给开发板上电，屏幕点亮并显示设备就绪表情
2. 打开手机 App，在设备列表中选择 **TXVR-XXXX**（XXXX 为屏幕/串口日志中
   显示的设备编号）
3. 连接成功后，屏幕表情切换为「已就绪」，即可开始使用

### 5.2 开始录音

- **手机操作**：在 App 中点击「开始录音」，对开发板上的麦克风说话；点击
  「停止」结束
- **按键操作**：短按板上 **BOOT 键** 等效开始/停止录音

录音过程中屏幕显示「聆听中」表情；停止后录音自动上传，文件同时保存在
SD 卡中，可在 App 内回放或下载。

### 5.3 屏幕表情含义

| 状态 | 含义 |
|---|---|
| 猫咪待机 | 设备就绪，等待连接或录音 |
| 猫咪聆听 | 正在录音 |
| 猫咪思考 | 录音处理/上传中 |
| 猫咪配对 | 正在与手机配对 |
| 猫咪错误 | 出现异常（具体错误见串口日志或 App 提示） |

## 6. 工程目录说明

```
AudioRecordingCard/
├── bin/                      # ★ 免编译烧录镜像（直接烧 0x0 即可体验）
├── main/                     # 应用层代码（开源）
├── components/
│   ├── tc_iot_sdk/           # ★ 录音卡闭源 SDK
│   │   ├── include/          #   公开 API 头文件
│   │   └── lib/              #   静态库 libtc_iot_sdk.a（请勿修改）
│   ├── audio_pipeline/       # 音频采集 + Opus 编码（开源）
│   ├── audio_storage/        # SD 卡存储（开源）
│   ├── ui_status/            # 屏幕表情素材（开源）
│   ├── lichuang_board/       # 立创实战派板级适配（开源）
│   └── board_hal/            # 板级硬件抽象（开源）
├── sdkconfig.defaults        # 默认配置（16MB Flash，无需修改）
└── partitions.csv            # 分区表
```

## 7. 常见问题

**Q：烧录时报错连不上串口？**
确认使用了数据线；Windows 下若设备管理器无串口，先安装乐鑫 USB 驱动；
仍失败可**按住 BOOT 键**的同时重新插拔 USB，再执行烧录命令。

**Q：免编译镜像烧完后屏幕无反应？**
若开发板之前烧过其他固件，Flash 中可能残留旧数据。用 Flash Download Tool
的 **ERASE** 按钮整片擦除后重新烧录。

**Q：首次编译卡在下载组件？**
首次编译需联网从乐鑫组件仓库下载依赖，若网络不畅请重试，或参考乐鑫官方
文档配置组件镜像源。

**Q：屏幕不亮？**
检查 USB 供电；重新上电后观察串口日志是否有初始化报错。

**Q：修改代码后如何更新固件？**
在工程目录重新执行 `idf.py build`，再 `idf.py -p <串口> flash monitor` 即可
（`set-target` 无需重复执行）。
