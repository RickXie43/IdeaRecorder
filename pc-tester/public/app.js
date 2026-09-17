const $ = (selector) => document.querySelector(selector);
const examples = {
  projects: 'Idea Recorder项目 | 离线语音记录与 AI 整理工具\n官网改版 | 品牌官网的设计与功能优化',
  idea: '我希望在想法记录项目里呃，每个项目记录自己的生成时间和完成的完成时间，并且这个项目可以导出并备份。官网首页的配色也想再研究一下。',
};

let lastResult = null;

const parseProjects = (text) => text.split(/\r?\n/)
  .map((line, index) => {
    const [name, ...descriptionParts] = line.split('|');
    return { id: `p${index + 1}`, name: name.trim(), description: descriptionParts.join('|').trim() };
  })
  .filter((project) => project.name);

const escapeHtml = (value) => String(value ?? '').replace(/[&<>'"]/g, (char) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;',
})[char]);

const labelFor = (value) => ({
  idea: '想法', change_request: '修改要求', decision: '决定', summary: '总结', task: '待办', question: '问题',
})[value] || value;

const render = (result) => {
  $('#resultPanel').classList.remove('hidden');
  $('#summary').textContent = result.summary || '模型没有生成摘要。';
  $('#jsonResult').textContent = JSON.stringify(result, null, 2);
  const groups = (result.groups || []).map((group) => `
    <article class="group">
      <div class="group-head">
        <h3>${escapeHtml(group.project_name)}</h3>
        <span class="badge">${group.match_type === 'existing' ? '已有项目' : '建议新项目'}</span>
      </div>
      ${(group.ideas || []).map((idea) => `
        <div class="idea">
          <p><strong>${escapeHtml(labelFor(idea.type))}：</strong>${escapeHtml(idea.idea)}</p>
          <p class="source">原文：${escapeHtml(idea.source_excerpt)}</p>
        </div>`).join('') || '<p class="hint">该项目没有可显示的独立要点。</p>'}
    </article>`).join('');
  const unassigned = result.unassigned_ideas?.length ? `
    <section class="unassigned">
      <h3>待归类想法</h3>
      ${result.unassigned_ideas.map((idea) => `<div class="idea"><p>${escapeHtml(idea.idea)}</p><p class="source">${escapeHtml(idea.reason)} · 原文：${escapeHtml(idea.source_excerpt)}</p></div>`).join('')}
    </section>` : '';
  $('#readableResult').innerHTML = groups || '<p class="hint">没有识别到项目分组。</p>';
  $('#readableResult').insertAdjacentHTML('beforeend', unassigned);
};

$('#fillProjects').addEventListener('click', () => { $('#projects').value = examples.projects; });
$('#fillIdea').addEventListener('click', () => { $('#ideaText').value = examples.idea; });

const selectedModel = () => 'deepseek-v4-flash';

const chatMessages = [];
let recognition;
let recognizing = false;
let speaking = false;
let continuousMode = false;
let currentAudio = null;
let pendingSpeech = '';
let speechCommitTimer = null;
const speechEndDelayMs = 3000;
const incompleteSpeechDelayMs = 7000;

const likelyIncompleteSpeech = (text) => /(?:我想问你一下你对|你对|关于|对|是|在|把|给|和|或|以及|但是|然后|这个|那个|怎么|为什么|能不能|可不可以|嗯|呃|额)[，。？！、…~\s]*$/i.test(text.trim()) || text.trim().length <= 1;

const renderConversation = () => {
  const container = $('#conversation');
  if (!chatMessages.length) { container.innerHTML = '<div class="empty-call">点下面的麦克风，开始说话<br><span>建议使用 Chrome 或 Edge，并允许麦克风权限</span></div>'; return; }
  container.innerHTML = chatMessages.map((item) => `<div class="bubble-row ${item.role}"><div class="bubble">${escapeHtml(item.content)}</div></div>`).join('');
  container.scrollTop = container.scrollHeight;
};

const speak = (text) => {
  const request = fetch('/api/tts/volcengine', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text, volcAppId: $('#volcAppId').value.trim(), volcAccessToken: $('#volcAccessToken').value.trim(), volcVoiceType: $('#volcVoiceType').value.trim() }) }).then(async (response) => { if (!response.ok) { const data = await response.json().catch(() => ({})); throw new Error(data.error || 'TTS 请求失败。'); } return URL.createObjectURL(await response.blob()); }).then((url) => new Promise((resolve, reject) => { currentAudio = new Audio(url); currentAudio.onplay = () => { speaking = true; $('#callState').textContent = 'AI 正在说话（可随时打断）'; }; currentAudio.onended = () => { speaking = false; URL.revokeObjectURL(url); currentAudio = null; $('#callState').textContent = continuousMode ? '持续聆听中' : '可以继续说话'; resolve(); }; currentAudio.onerror = () => reject(new Error('音频播放失败。')); currentAudio.play().catch(reject); })).catch((error) => { speaking = false; $('#callState').textContent = `TTS 失败：${error.message}`; });
  return request;
};

const sendVoiceText = async (text) => {
  const clean = text.trim(); if (!clean) return;
  const apiKey = $('#apiKey').value.trim();
  if (!apiKey) { $('#callState').textContent = '请先填写 API Key'; return; }
  chatMessages.push({ role: 'user', content: clean }); renderConversation();
  $('#callState').textContent = '正在思考'; $('#micButton').disabled = true;
  try {
    const response = await fetch('/api/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ apiKey, messages: chatMessages }) });
    const data = await response.json(); if (!response.ok) throw new Error(data.error || '请求失败。');
    chatMessages.push({ role: 'assistant', content: data.text }); renderConversation(); speak(data.text);
  } catch (error) { $('#callState').textContent = `对话失败：${error.message}`; }
  finally { $('#micButton').disabled = false; }
};

const setupRecognition = () => {
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  const showFallback = (message) => { $('#callState').textContent = message; $('#textFallback').classList.remove('hidden'); };
  if (!Recognition) { showFallback('当前浏览器不支持语音识别'); $('#micButton').disabled = true; return; }
  recognition = new Recognition(); recognition.lang = 'zh-CN'; recognition.continuous = true; recognition.interimResults = true;
  recognition.onstart = () => { recognizing = true; pendingSpeech = ''; clearTimeout(speechCommitTimer); $('#micLabel').textContent = '结束监听'; $('#callState').textContent = '持续聆听中'; $('#transcript').textContent = ''; };
  recognition.onresult = (event) => { let interim = ''; let final = ''; for (let i = event.resultIndex; i < event.results.length; i++) { const t = event.results[i][0].transcript; event.results[i].isFinal ? final += t : interim += t; } if (interim || final) { if (speaking) { currentAudio?.pause(); currentAudio = null; speaking = false; $('#callState').textContent = '你打断了我，我在听'; } if (final) pendingSpeech += final; $('#transcript').textContent = pendingSpeech + interim; if (final) { clearTimeout(speechCommitTimer); const waitMs = likelyIncompleteSpeech(pendingSpeech) ? incompleteSpeechDelayMs : speechEndDelayMs; $('#callState').textContent = waitMs > speechEndDelayMs ? '这句话还没说完，我继续等你' : '等你说完…'; speechCommitTimer = setTimeout(() => { const text = pendingSpeech.trim(); pendingSpeech = ''; $('#transcript').textContent = ''; if (text) sendVoiceText(text); }, waitMs); } } };
  recognition.onerror = (event) => { recognizing = false; if (event.error === 'not-allowed') { continuousMode = false; $('#micLabel').textContent = '开始说话'; showFallback('麦克风权限被拒绝'); } else if (event.error === 'network') { continuousMode = false; $('#micLabel').textContent = '开始说话'; showFallback('当前浏览器的语音识别服务不可用，请改用 Chrome 或 Edge'); } else $('#callState').textContent = `识别失败：${event.error}`; };
  recognition.onend = () => { recognizing = false; if (continuousMode) { $('#callState').textContent = '正在重新连接麦克风…'; setTimeout(() => { if (continuousMode && !recognizing) recognition.start(); }, 250); } else { $('#micLabel').textContent = '开始说话'; if (!speaking) $('#callState').textContent = '可以继续说话'; } };
};

$('#micButton').addEventListener('click', () => { if (recognizing || continuousMode) { continuousMode = false; recognition?.stop(); $('#micLabel').textContent = '开始说话'; $('#callState').textContent = '已停止监听'; } else { continuousMode = true; currentAudio?.pause(); currentAudio = null; speaking = false; recognition?.start(); } });
$('#stopSpeaking').addEventListener('click', () => { currentAudio?.pause(); currentAudio = null; speaking = false; $('#callState').textContent = continuousMode ? '持续聆听中' : '可以继续说话'; });
$('#clearChat').addEventListener('click', () => { chatMessages.length = 0; currentAudio?.pause(); currentAudio = null; speaking = false; pendingSpeech = ''; clearTimeout(speechCommitTimer); $('#transcript').textContent = ''; $('#callState').textContent = continuousMode ? '持续聆听中' : '准备开始'; renderConversation(); });
$('#fallbackSend').addEventListener('click', () => { const input = $('#fallbackInput'); const text = input.value.trim(); if (!text) return; input.value = ''; sendVoiceText(text); });
$('#fallbackInput').addEventListener('keydown', (event) => { if (event.key === 'Enter') $('#fallbackSend').click(); });

const saveSettings = async () => {
  const response = await fetch('/api/settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ apiKey: $('#apiKey').value.trim(), volcAppId: $('#volcAppId').value.trim(), volcAccessToken: $('#volcAccessToken').value.trim(), volcVoiceType: $('#volcVoiceType').value.trim(), model: selectedModel() }),
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '无法保存本机配置。');
  return data;
};

const loadSettings = async () => {
  try {
    const response = await fetch('/api/settings');
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || '无法读取本机配置。');
    $('#apiKey').value = data.apiKey || '';
    $('#volcAppId').value = data.volcAppId || '';
    $('#volcAccessToken').value = data.volcAccessToken || '';
    $('#volcVoiceType').value = data.volcVoiceType || 'saturn_zh_female_keainvsheng_tob';
    const model = document.querySelector(`input[name="model"][value="${data.model}"]`);
    if (model) model.checked = true;
    if (data.apiKey) $('#status').textContent = '已读取本机保存的 API Key。';
  } catch (error) {
    $('#status').textContent = `无法读取本机配置：${error.message}`;
  }
};

$('#saveSettings').addEventListener('click', async () => {
  try {
    await saveSettings();
    $('#status').textContent = $('#apiKey').value.trim() ? 'API Key 已保存到本机文件。' : '已清除本机保存的 API Key。';
  } catch (error) {
    $('#status').textContent = `保存失败：${error.message}`;
  }
});

$('#organize').addEventListener('click', async () => {
  const apiKey = $('#apiKey').value.trim();
  const ideaText = $('#ideaText').value.trim();
  if (!apiKey || !ideaText) {
    $('#status').textContent = !apiKey ? '请先填写 API Key。' : '请填写想法文本。';
    return;
  }
  const button = $('#organize');
  button.disabled = true;
  $('#status').textContent = '正在请求 DeepSeek，通常需要数秒…';
  try {
    const response = await fetch('/api/organize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        apiKey,
        ideaText,
        projects: parseProjects($('#projects').value),
        model: selectedModel(),
      }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || '请求失败。');
    lastResult = data.result;
    render(lastResult);
    $('#status').textContent = `已使用 ${data.model} 完成整理。`;
  } catch (error) {
    $('#status').textContent = `整理失败：${error.message}`;
  } finally {
    button.disabled = false;
  }
});

$('#copyJson').addEventListener('click', async () => {
  if (!lastResult) return;
  try {
    await navigator.clipboard.writeText(JSON.stringify(lastResult, null, 2));
    $('#status').textContent = 'JSON 已复制。';
  } catch {
    $('#status').textContent = '无法自动复制，请从下方 JSON 区域手动复制。';
  }
});

loadSettings();
setupRecognition();
