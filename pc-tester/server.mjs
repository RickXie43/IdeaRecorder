import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFile, rename, writeFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const port = Number(process.env.PORT || 4173);
const publicDir = join(process.cwd(), 'public');
const settingsPath = join(process.cwd(), 'local-settings.json');
const volcenginePath = join(process.cwd(), 'volcengine.local.json');
const staticTypes = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
};

const json = (res, status, value) => {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};

const readJsonBody = async (req) => {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 1_000_000) throw new Error('请求内容过大。');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
};

const readSettings = async () => {
  try {
    const parsed = JSON.parse(await readFile(settingsPath, 'utf8'));
    let volc = {};
    try { volc = JSON.parse(await readFile(volcenginePath, 'utf8')); } catch (error) { if (error?.code !== 'ENOENT') throw error; }
    return {
      apiKey: typeof parsed.apiKey === 'string' ? parsed.apiKey : '',
      ttsApiUrl: typeof parsed.ttsApiUrl === 'string' ? parsed.ttsApiUrl : '',
      ttsApiKey: typeof parsed.ttsApiKey === 'string' ? parsed.ttsApiKey : '',
      ttsVoice: typeof parsed.ttsVoice === 'string' ? parsed.ttsVoice : 'shimmer',
      volcAppId: typeof parsed.volcAppId === 'string' && parsed.volcAppId ? parsed.volcAppId : String(volc.appId || '').trim(),
      volcAccessToken: typeof parsed.volcAccessToken === 'string' && parsed.volcAccessToken ? parsed.volcAccessToken : String(volc.accessToken || '').trim(),
      volcVoiceType: typeof parsed.volcVoiceType === 'string' && parsed.volcVoiceType && parsed.volcVoiceType !== 'BV001_streaming' ? parsed.volcVoiceType : String(volc.voiceType || 'saturn_zh_female_keainvsheng_tob').trim(),
      model: parsed.model === 'deepseek-v4-pro' ? 'deepseek-v4-pro' : 'deepseek-v4-flash',
    };
  } catch (error) {
    if (error && error.code === 'ENOENT') return { apiKey: '', ttsApiUrl: '', ttsApiKey: '', ttsVoice: 'shimmer', volcAppId: '', volcAccessToken: '', volcVoiceType: 'saturn_zh_female_keainvsheng_tob', model: 'deepseek-v4-flash' };
    throw new Error('本机配置文件无法读取。');
  }
};

const writeSettings = async (settings) => {
  const apiKey = String(settings?.apiKey || '').trim();
  const ttsApiUrl = String(settings?.ttsApiUrl || '').trim();
  const ttsApiKey = String(settings?.ttsApiKey || '').trim();
  const ttsVoice = String(settings?.ttsVoice || 'shimmer').trim() || 'shimmer';
  const volcAppId = String(settings?.volcAppId || '').trim();
  const volcAccessToken = String(settings?.volcAccessToken || '').trim();
  const volcVoiceType = String(settings?.volcVoiceType || 'saturn_zh_female_keainvsheng_tob').trim();
  const model = settings?.model === 'deepseek-v4-pro' ? 'deepseek-v4-pro' : 'deepseek-v4-flash';
  const tempPath = `${settingsPath}.tmp`;
  await writeFile(tempPath, JSON.stringify({ apiKey, ttsApiUrl, ttsApiKey, ttsVoice, volcAppId, volcAccessToken, volcVoiceType, model }, null, 2), { encoding: 'utf8', mode: 0o600 });
  await rename(tempPath, settingsPath);
  return { apiKey, ttsApiUrl, ttsApiKey, ttsVoice, volcAppId, volcAccessToken, volcVoiceType, model };
};

const normalizeProjects = (projects) => (Array.isArray(projects) ? projects : [])
  .map((item, index) => ({
    id: String(item?.id || `p${index + 1}`).trim(),
    name: String(item?.name || '').trim(),
    description: String(item?.description || '').trim(),
  }))
  .filter((project) => project.name)
  .slice(0, 50);

const withDailyProject = (projects) => {
  const existingDaily = projects.find((project) => project.name === '日常');
  if (existingDaily) return { projects, dailyProject: existingDaily };
  const dailyProject = {
    id: '__daily__',
    name: '日常',
    description: '未能明确归入已有项目的零散想法、个人事务和通用记录。',
  };
  return { projects: [...projects, dailyProject], dailyProject };
};

const moveFallbackItemsToDaily = (result, dailyProject) => {
  const groups = Array.isArray(result.groups) ? result.groups : [];
  const dailyGroup = groups.find((group) => group?.project_id === dailyProject.id || group?.project_name === dailyProject.name);
  const fallbackIdeas = [];
  const retainedGroups = [];

  for (const group of groups) {
    if (group?.match_type === 'new_project') {
      fallbackIdeas.push(...(Array.isArray(group.ideas) ? group.ideas : []));
    } else {
      retainedGroups.push(group);
    }
  }
  for (const item of Array.isArray(result.unassigned_ideas) ? result.unassigned_ideas : []) {
    fallbackIdeas.push({
      source_excerpt: item.source_excerpt || '',
      idea: item.idea || '',
      type: 'idea',
    });
  }

  if (fallbackIdeas.length) {
    const target = retainedGroups.find((group) => group === dailyGroup)
      || { project_id: dailyProject.id, project_name: dailyProject.name, match_type: 'existing', ideas: [] };
    target.project_id = dailyProject.id;
    target.project_name = dailyProject.name;
    target.match_type = 'existing';
    target.ideas = [...(Array.isArray(target.ideas) ? target.ideas : []), ...fallbackIdeas];
    if (!retainedGroups.includes(target)) retainedGroups.push(target);
  }

  return { ...result, groups: retainedGroups, unassigned_ideas: [] };
};

const responseSchema = {
  type: 'object',
  additionalProperties: false,
  required: ['summary', 'groups', 'unassigned_ideas'],
  properties: {
    summary: { type: 'string' },
    groups: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['project_id', 'project_name', 'match_type', 'ideas'],
        properties: {
          project_id: { type: ['string', 'null'] },
          project_name: { type: 'string' },
          match_type: { type: 'string', enum: ['existing', 'new_project'] },
          ideas: {
            type: 'array',
            items: {
              type: 'object',
              additionalProperties: false,
              required: ['source_excerpt', 'idea', 'type'],
              properties: {
                source_excerpt: { type: 'string' },
                idea: { type: 'string' },
                type: { type: 'string', enum: ['idea', 'change_request', 'decision', 'summary', 'task', 'question'] },
              },
            },
          },
        },
      },
    },
    unassigned_ideas: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['source_excerpt', 'idea', 'reason'],
        properties: {
          source_excerpt: { type: 'string' },
          idea: { type: 'string' },
          reason: { type: 'string' },
        },
      },
    },
  },
};

const promptFor = (projects, ideaText) => {
  const projectText = projects.length
    ? projects.map((project) => `- ID: ${project.id}; 名称: ${project.name}; 描述: ${project.description || '无'}`).join('\n')
    : '（当前没有已有项目）';
  return `你是中文项目想法整理助手。将用户的凌乱记录按项目拆分，再将每个项目内的独立想法拆成清晰、简短的条目。\n\n已有项目：\n${projectText}\n\n用户记录：\n${ideaText}\n\n规则：\n1. 只能把确实相关的内容归入已有项目。已有项目匹配时，project_id 必须使用给定 ID，match_type 为 existing。\n2. “日常”是强制兜底项目。任何无法明确匹配其他已有项目的内容，都归入“日常”；不得创建新项目，match_type 必须为 existing。\n3. 不得返回 unassigned_ideas；它必须是空数组。\n4. 一段记录可以拆进多个项目；每个项目可以有多个 ideas。\n5. 保留原意，不虚构需求、日期、技术方案或待办。source_excerpt 只摘录能支撑该条目的原文片段。\n6. idea 使用中文，只写该项目下新增的核心动作、结论或要求，通常不超过 30 个汉字。项目名称已经由 project_name 表达，因此 idea 不得重复项目名称或“在该项目中”等归属语；只有去掉名称会改变实际语义时才保留。\n7. 示例：project_name 为“液滴Rayleigh振荡DNS”，原文为“我想在三维液滴Rayleigh振荡项目里面根据罗东师兄的代码验证接触线部分哪里设置错了”，则 idea 应为“参照罗东师兄的代码，验证接触线部分的设置错误。”，而不是重复项目名称。\n8. type 只能使用 Schema 中的枚举。仅返回符合指定 JSON Schema 的对象。`;
};

const extractOutputText = (envelope) => {
  if (typeof envelope.output_text === 'string' && envelope.output_text.trim()) return envelope.output_text.trim();
  // V4 在开启思考模式时，output 会先包含 reasoning 项；只有 message/output_text
  // 才是最终面向用户的 JSON，不能把 reasoning_text 当作结果解析。
  for (const item of envelope.output || []) {
    if (item.type !== 'message') continue;
    for (const part of item.content || []) {
      if (part.type === 'output_text' && typeof part.text === 'string' && part.text.trim()) return part.text.trim();
      if (typeof part.output_text === 'string' && part.output_text.trim()) return part.output_text.trim();
    }
  }
  throw new Error(`DeepSeek 未返回可读取的最终文本（状态：${envelope.status || '未知'}）。`);
};

const parseResultJson = (text) => {
  const cleaned = text
    .trim()
    .replace(/^```(?:json)?\s*/i, '')
    .replace(/\s*```$/, '')
    .trim();
  try {
    return JSON.parse(cleaned);
  } catch {
    const start = cleaned.indexOf('{');
    const end = cleaned.lastIndexOf('}');
    if (start >= 0 && end > start) return JSON.parse(cleaned.slice(start, end + 1));
    throw new Error(`DeepSeek 返回内容不是合法 JSON：${cleaned.slice(0, 240)}`);
  }
};

const organize = async (payload) => {
  const apiKey = String(payload.apiKey || '').trim();
  const ideaText = String(payload.ideaText || '').trim();
  const model = payload.model === 'deepseek-v4-pro' ? 'deepseek-v4-pro' : 'deepseek-v4-flash';
  const { projects, dailyProject } = withDailyProject(normalizeProjects(payload.projects));
  if (!apiKey) throw new Error('请填写 DeepSeek API Key。');
  if (!ideaText) throw new Error('请填写需要整理的想法。');

  const upstream = await fetch('https://api.deepseek.com/responses', {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model,
      reasoning: { effort: 'none' },
      input: promptFor(projects, ideaText),
      text: { format: { type: 'json_schema', name: 'idea_project_split', strict: true, schema: responseSchema } },
    }),
    signal: AbortSignal.timeout(90_000),
  });
  const raw = await upstream.text();
  if (!upstream.ok) {
    throw new Error(`DeepSeek 返回 HTTP ${upstream.status}：${raw.slice(0, 500)}`);
  }
  let envelope;
  try { envelope = JSON.parse(raw); } catch { throw new Error('DeepSeek 返回了无法解析的响应。'); }
  const outputText = extractOutputText(envelope);
  const result = moveFallbackItemsToDaily(parseResultJson(outputText), dailyProject);
  return { model, result };
};

const extractChatText = (envelope) => {
  if (typeof envelope.output_text === 'string' && envelope.output_text.trim()) return envelope.output_text.trim();
  for (const item of envelope.output || []) {
    if (item.type !== 'message') continue;
    for (const part of item.content || []) {
      if (part.type === 'output_text' && typeof part.text === 'string' && part.text.trim()) return part.text.trim();
      if (typeof part.output_text === 'string' && part.output_text.trim()) return part.output_text.trim();
    }
  }
  const choice = envelope.choices?.[0]?.message?.content;
  if (typeof choice === 'string' && choice.trim()) return choice.trim();
  throw new Error('DeepSeek 未返回可读取的文本。');
};

const chat = async (payload) => {
  const apiKey = String(payload.apiKey || '').trim();
  const messages = Array.isArray(payload.messages) ? payload.messages
    .filter((item) => ['user', 'assistant'].includes(item?.role) && typeof item.content === 'string')
    .slice(-20)
    .map((item) => ({ role: item.role, content: item.content.slice(0, 4000) })) : [];
  if (!apiKey) throw new Error('请先填写 DeepSeek API Key。');
  if (!messages.length || !messages.at(-1).content.trim()) throw new Error('没有可发送的语音文字。');
  const upstream = await fetch('https://api.deepseek.com/responses', {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: 'deepseek-v4-flash',
      instructions: '请用中文直接回答。普通问题平均回答约100到200个汉字，根据问题复杂程度自行增减；复杂问题可以更详细。输出必须是适合语音朗读的纯文本，不要使用Markdown，不要使用##、**、项目符号、表格、代码块或其他结构化符号。',
      input: messages,
    }),
    signal: AbortSignal.timeout(60_000),
  });
  const raw = await upstream.text();
  if (!upstream.ok) throw new Error(`DeepSeek 返回 HTTP ${upstream.status}：${raw.slice(0, 500)}`);
  let envelope;
  try { envelope = JSON.parse(raw); } catch { throw new Error('DeepSeek 返回了无法解析的响应。'); }
  return { text: extractChatText(envelope), model: 'deepseek-v4-flash' };
};

const synthesize = async (payload) => {
  const text = String(payload.text || '').trim().slice(0, 4000);
  const url = String(payload.ttsApiUrl || '').trim();
  const apiKey = String(payload.ttsApiKey || '').trim();
  const voice = String(payload.ttsVoice || 'shimmer').trim();
  if (!text) throw new Error('没有可朗读的文本。');
  if (!url) throw new Error('请填写 TTS API 地址。');
  if (!apiKey) throw new Error('请填写 TTS API Key。');
  let parsedUrl;
  try { parsedUrl = new URL(url); } catch { throw new Error('TTS API 地址格式不正确。'); }
  if (!['http:', 'https:'].includes(parsedUrl.protocol)) throw new Error('TTS API 地址必须使用 HTTP 或 HTTPS。');
  const upstream = await fetch(url, {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json', Accept: 'audio/mpeg' },
    body: JSON.stringify({ model: 'tts-1', input: text, voice, response_format: 'mp3', speed: 0.96 }),
    signal: AbortSignal.timeout(60_000),
  });
  if (!upstream.ok) throw new Error(`TTS 返回 HTTP ${upstream.status}：${(await upstream.text()).slice(0, 400)}`);
  return { contentType: upstream.headers.get('content-type') || 'audio/mpeg', audio: Buffer.from(await upstream.arrayBuffer()) };
};

const synthesizeVolcengine = async (payload) => {
  const text = String(payload.text || '').trim().slice(0, 3000);
  const appId = String(payload.volcAppId || '').trim();
  const accessToken = String(payload.volcAccessToken || '').trim();
  const voiceType = String(payload.volcVoiceType || 'saturn_zh_female_keainvsheng_tob').trim();
  if (!text) throw new Error('没有可朗读的文本。');
  if (!appId || !accessToken) throw new Error('请填写火山引擎 App ID 和 Access Token。');
  const requestId = randomUUID();
  const upstream = await fetch('https://openspeech.bytedance.com/api/v3/tts/unidirectional', {
    method: 'POST',
    headers: { 'X-Api-App-Id': appId, 'X-Api-Access-Key': accessToken, 'X-Api-Resource-Id': 'seed-tts-2.0', 'X-Api-Request-Id': requestId, 'Content-Type': 'application/json' },
    body: JSON.stringify({ user: { uid: 'idea-recorder-local' }, req_params: { text, speaker: voiceType, audio_params: { format: 'mp3', sample_rate: 24000, speech_rate: -4, loudness_rate: 0 } } }),
    signal: AbortSignal.timeout(60_000),
  });
  const raw = await upstream.text();
  if (!upstream.ok) throw new Error(`火山引擎返回 HTTP ${upstream.status}：${raw.slice(0, 400)}`);
  const audioParts = [];
  let cursor = 0;
  while (cursor < raw.length) {
    const start = raw.indexOf('{', cursor);
    if (start < 0) break;
    let depth = 0; let quoted = false; let escaped = false; let end = -1;
    for (let i = start; i < raw.length; i++) {
      const char = raw[i];
      if (quoted) { if (escaped) escaped = false; else if (char === '\\') escaped = true; else if (char === '"') quoted = false; continue; }
      if (char === '"') quoted = true; else if (char === '{') depth++; else if (char === '}' && --depth === 0) { end = i + 1; break; }
    }
    if (end < 0) break;
    let item;
    try { item = JSON.parse(raw.slice(start, end)); } catch { cursor = end; continue; }
    if (item.code && ![0, 3000, 20000000].includes(item.code)) throw new Error(`火山引擎 TTS ${item.code}：${item.message || '请求失败'}`);
    if (item.data) audioParts.push(Buffer.from(item.data, 'base64'));
    cursor = end;
  }
  if (!audioParts.length) throw new Error(`火山引擎没有返回音频：${raw.slice(0, 240)}`);
  return Buffer.concat(audioParts);
};

const server = createServer(async (req, res) => {
  try {
    if (req.method === 'POST' && req.url === '/api/organize') {
      const body = await readJsonBody(req);
      const data = await organize(body);
      return json(res, 200, data);
    }
    if (req.method === 'POST' && req.url === '/api/chat') {
      const body = await readJsonBody(req);
      return json(res, 200, await chat(body));
    }
    if (req.method === 'POST' && req.url === '/api/tts') {
      const body = await readJsonBody(req);
      const result = await synthesize(body);
      res.writeHead(200, { 'Content-Type': result.contentType, 'Cache-Control': 'no-store' });
      return res.end(result.audio);
    }
    if (req.method === 'POST' && req.url === '/api/tts/volcengine') {
      const body = await readJsonBody(req);
      const audio = await synthesizeVolcengine(body);
      res.writeHead(200, { 'Content-Type': 'audio/mpeg', 'Cache-Control': 'no-store' });
      return res.end(audio);
    }
    if (req.method === 'GET' && req.url === '/api/settings') {
      return json(res, 200, await readSettings());
    }
    if (req.method === 'POST' && req.url === '/api/settings') {
      const body = await readJsonBody(req);
      return json(res, 200, await writeSettings(body));
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') return json(res, 405, { error: 'Method Not Allowed' });
    const urlPath = new URL(req.url, `http://${req.headers.host}`).pathname;
    const fileName = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
    const filePath = normalize(join(publicDir, fileName));
    if (!filePath.startsWith(publicDir)) return json(res, 403, { error: 'Forbidden' });
    const content = await readFile(filePath);
    res.writeHead(200, { 'Content-Type': staticTypes[extname(filePath)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(req.method === 'HEAD' ? undefined : content);
  } catch (error) {
    const message = error instanceof Error ? error.message : '未知错误';
    json(res, 500, { error: message });
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Idea Recorder PC 测试器已启动：http://127.0.0.1:${port}`);
});
