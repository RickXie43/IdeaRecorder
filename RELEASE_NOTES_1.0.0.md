# Idea Recorder 1.0.0

这是 Idea Recorder 的第一版正式版本，包含 Android 版和面向鸿蒙 Android 兼容环境的 APK。

## 主要功能

- 自由记录想法，并支持 AI 整理为项目和待办；
- AI 整理为 Markdown 笔记，支持联网搜索和问卷细化；
- 项目、待办、笔记的展开、编辑、排序、多选、批量删除与导出；
- 待办状态、截止日期、逾期提醒和已完成事项管理；
- Markdown 笔记自动编译与编辑；
- JSON 完整数据导出/导入，包含项目、待办、笔记和 API Key；
- WebDAV 双向同步，支持自动合并、稳定同步 ID 和删除标记；
- 支持 HTTP/HTTPS WebDAV 地址配置；
- 离线中文语音识别能力；
- Android APK 与鸿蒙 Android 兼容 APK 使用相同版本号。

## 下载说明

- Android Release：适合日常安装使用；
- Android Debug：用于测试和问题排查；
- Harmony Release：适合支持 Android 应用的鸿蒙设备；
- Harmony Debug：用于鸿蒙兼容环境测试。

当前 Release 使用测试签名证书，仅适合内测和个人安装；正式上架前需要替换为正式签名证书。

## 注意事项

- 首次使用 AI 功能需要在设置中填写 DeepSeek API Key；
- WebDAV 请填写实际 WebDAV 服务端口，群晖常见端口为 HTTP 5005、HTTPS 5006；
- 导出文件包含 API Key，请妥善保管，不要公开上传。
