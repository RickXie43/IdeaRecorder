# Idea Recorder PC 测试器

用于在 PC 上测试 DeepSeek 对凌乱想法的“项目归类 + 独立要点拆分”质量，以及通话式语音对话。该工具独立于 Android 工程，所有数据仅在当前浏览器页面和本机 Node 进程中处理。

## 运行

在本目录执行：

```powershell
npm start
```

随后打开 `http://127.0.0.1:4173`。

无需执行 `npm install`，工具仅使用 Node.js 内置模块。需要 Node.js 18 或更高版本。

## 使用

1. 输入 DeepSeek API Key，点击“保存到本机”。Key 会写入 `local-settings.json`，下次启动自动读取；该文件已被 Git 忽略。
2. 输入已有项目，每行格式为 `项目名称 | 项目描述`；项目描述可留空。
3. 输入想法文本。
4. 默认选择 Flash；需要比较复杂案例时可选择 Pro。
5. 点击“开始整理”，查看按项目展示的结果和原始 JSON。

## 通话式语音对话

打开页面后，在顶部语音实验区配置火山引擎 `App ID`、`Access Token` 和女性 `Voice Type`，点击“开始说话”，允许浏览器访问麦克风并直接说中文。开启后会持续监听；AI 音频朗读时如果检测到用户开始说话，会立即停止音频并处理新一句话。浏览器内置的 Web Speech API 负责语音识别，识别出的文本由本机 Node 服务发送到开启联网搜索的 `deepseek-v4-flash`，回复再由火山引擎豆包语音生成 MP3。对话历史只保留在当前页面内。

也可以复制 `volcengine.local.json.example` 为 `volcengine.local.json`，预先填写火山引擎凭据。该文件已加入 Git 忽略，不会被提交。

推荐使用 Chrome 或 Edge 进行识别。TTS 音色由所配置的 AI TTS 服务决定，默认推荐女性音色 `shimmer`；交互风格参考通话式 AI 助手，但不复制豆包的专有音色或品牌身份。

返回格式由本机服务使用 JSON Schema 约束，核心字段为：

```json
{
  "summary": "本次记录摘要",
  "groups": [
    {
      "project_id": "p1",
      "project_name": "Idea Recorder项目",
      "match_type": "existing",
      "ideas": [
        {
          "source_excerpt": "每个项目记录自己的生成时间",
          "idea": "每条想法自动记录创建时间；完成时记录完成时间。",
          "type": "change_request"
        }
      ]
    }
  ],
  "unassigned_ideas": []
}
```

`match_type` 固定为 `existing`。无法匹配输入项目的内容统一进入“日常”；若输入列表不含“日常”，测试器会自动加入该兜底项目。

## 限制

- 调用 DeepSeek 时会发送想法文本和已有项目名称/描述，不会发送音频。
- 每次调用均显式关闭 DeepSeek 的深度思考模式，以提高这类分类任务的响应速度并减少 Token 用量。
- 本机服务器只监听 `127.0.0.1`，不会对局域网开放。
- 调用需要能访问 `https://api.deepseek.com`。
- 语音识别和朗读能力由浏览器提供；不同浏览器的可用性和音色会有差异。
- API Key 在客户端直连方案中仍应只用于个人测试。
