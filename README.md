# Idea Recorder

中国大陆 Android 单机版的语音想法整理工具，当前版本为 1.0.0 第一版。鸿蒙 Android 兼容层位于 `harmony-compat`，与 Android 版使用相同版本号。

## 当前状态

- 已完成：按设计稿重做的“我的项目”卡片首页、可展开项目待办、待办详情编辑页、设置页、优先级/截止时间、项目与待办本地 SQLite、API Key Android Keystore 存储、DeepSeek Responses API 客户端、WAV 录音、Vosk 中文离线识别、JSON 数据导出、无 Google 服务构建。
- 当前限制：待办详情和 AI 结果仍是单机轻量编辑流程；需要在真实 Android 设备上继续验证录音质量、Vosk 性能和中国大陆网络下的 DeepSeek 连接。

## 构建

在 Android Studio JBR 或 JDK 21 环境执行（本机没有 Gradle 命令时可使用用户 Gradle 发行版的 `bin\gradle.bat`）：

```powershell
./build-release.ps1
```

所有发布结果统一放在 `dist`：

- `Idea-Recorder-1.0.0-debug.apk`
- `Idea-Recorder-1.0.0-release.apk`
- `Idea-Recorder-Harmony-1.0.0-debug.apk`
- `Idea-Recorder-Harmony-1.0.0-release.apk`

内测 Release 产物：`app/build/outputs/apk/release/app-release.apk`，使用本机 Debug keystore 签名，仅用于测试安装；正式发布前必须替换为专用签名证书。

不要把 API Key 写入源码、Gradle 文件或命令行历史。请在 App 设置页填写。
