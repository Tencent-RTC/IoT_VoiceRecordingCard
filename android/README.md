# Recorder Client

这是录音笔 Android 客户端的独立源码快照。该工程可直接作为 Android Studio
项目根目录打开，也可在安装 Android SDK 和 JDK 17 后运行：

```sh
./gradlew :app:assembleDebug
```

## 包含范围

- `app`：Android 客户端界面、BLE 连接、离线同步、播放、分享和 ASR 调用代码；
- `business-protocol`：Protobuf wire schema 与业务报文编解码；
- `transport`：可靠传输层；
- `app-session`：App 侧业务会话与 Request 调度。

该快照不包含 mock 录音笔、内部回归测试、设计资料、Prompt 或开发辅助工具。

## 运行前提

完整录音、实时音频和离线同步功能需要兼容的 BLE 录音笔。SDK 仅包含当前
`arm64-v8a` 验证过的 VoiceAI ABI。

为防止密钥泄露，发布源码中的 `TrtcUserSigConfig` 已被置为空配置。生产环境
必须由受信任的业务服务端签发短期 UserSig，客户端不得硬编码长期密钥。未接入
该服务端前，云端 ASR 功能无法完成鉴权；其余本地和 BLE 功能仍可构建、浏览和调试。

## 第三方组件

工程引用了随源码提供的 Tencent VoiceAI AAR，以及通过 Maven 获取的 Protobuf、
Gson、AndroidX Core 和 WorkManager。对外再分发前，请确认所有第三方组件的适用
许可、归属声明和通知要求。
