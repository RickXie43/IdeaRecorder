package com.idearecorder.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DeepSeekClient(private val apiKey: String, private val model: String = "deepseek-v4-flash") {
    private data class MarkdownTask(val project: String, val title: String, val description: String, val original: String, val dueDate: String?)

    private fun parseMarkdownTasks(text: String): List<MarkdownTask> {
        val heading = Regex("^#{1,6}\\s*(?:项目[：:]\\s*)?(.+?)\\s*$")
        val bullet = Regex("^[-*+]\\s+(?:\\[[ xX]\\]\\s*)?(.+)$")
        val parsed = mutableListOf<MarkdownTask>()
        var project = ""
        var current: StringBuilder? = null
        fun flush() {
            val content = current?.toString()?.trim().orEmpty()
            if (content.isBlank()) return
            val parts = content.split('；').map { it.trim() }.filter { it.isNotBlank() }
            val title = parts.firstOrNull().orEmpty().ifBlank { content.take(80) }
            val description = parts.drop(1).joinToString("；").ifBlank { content }
            val due = Regex("截止时间[：:]\\s*([^；\\n]+)").find(content)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() && it != "未设置" && it != "null" }
            parsed.add(MarkdownTask(project, title, description, content, due))
            current = null
        }
        text.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            heading.find(line.trim())?.let { match -> flush(); project = match.groupValues[1].trim(); return@forEach }
            bullet.find(line)?.let { match -> flush(); current = StringBuilder(match.groupValues[1].trim()); return@forEach }
            if (current != null && line.isNotBlank()) current?.append('；')?.append(line.trim())
        }
        flush()
        return parsed
    }

    private fun enforceMarkdownTasks(result: JSONObject, source: String): JSONObject {
        val parsed = parseMarkdownTasks(source)
        if (parsed.isEmpty()) return result
        val modelGroups = result.optJSONArray("groups") ?: JSONArray()
        val byProject = linkedMapOf<String, MutableList<MarkdownTask>>()
        parsed.forEach { byProject.getOrPut(it.project) { mutableListOf() }.add(it) }
        val rebuilt = JSONArray()
        byProject.entries.forEachIndexed { index, (projectName, tasks) ->
            val candidate = (0 until modelGroups.length()).mapNotNull { modelGroups.optJSONObject(it) }.firstOrNull { group ->
                val names = listOf(group.optString("project_name"), group.optString("suggested_project_name"), group.optString("instruction"))
                projectName.isNotBlank() && names.filter { it.isNotBlank() }.any { it.contains(projectName, true) || projectName.contains(it, true) }
            } ?: modelGroups.optJSONObject(index) ?: modelGroups.optJSONObject(0)
            val group = if (candidate == null) JSONObject() else JSONObject(candidate.toString())
            if (!group.has("project_id")) group.put("project_id", JSONObject.NULL)
            if (group.optString("suggested_project_name").isBlank() && projectName.isNotBlank()) group.put("suggested_project_name", projectName)
            if (group.optString("project_name").isBlank() && projectName.isNotBlank()) group.put("project_name", projectName)
            group.put("tasks", JSONArray().also { array -> tasks.forEach { task ->
                array.put(JSONObject().put("title", task.title).put("description", task.description)
                    .put("original_text", task.original).put("due_date", task.dueDate ?: JSONObject.NULL))
            } })
            group.put("ideas", JSONArray())
            rebuilt.put(group)
        }
        result.put("groups", rebuilt)
        return result
    }

    /** Keep a model response from creating a project shell without the idea that belongs in it. */
    private fun ensureOrganizedTasks(result: JSONObject, source: String): JSONObject {
        val groups = result.optJSONArray("groups") ?: return result
        val fallbackTitle = source.trim().replace(Regex("\\s+"), " ").take(100)
        for (i in 0 until groups.length()) {
            val group = groups.optJSONObject(i) ?: continue
            val tasks = group.optJSONArray("tasks")
            if (tasks != null && tasks.length() > 0) continue
            val rebuilt = JSONArray()
            val ideas = group.optJSONArray("ideas")
            if (ideas != null) for (j in 0 until ideas.length()) {
                val raw = ideas.opt(j)
                val item = raw as? JSONObject
                val title = if (item != null) item.optString("title", item.optString("idea")).trim() else raw.toString().trim()
                if (title.isNotBlank() && title != "null") {
                    rebuilt.put(JSONObject().put("title", title).put("description", item?.optString("description").orEmpty())
                        .put("original_text", item?.optString("original_text").orEmpty().ifBlank { source }).put("due_date", JSONObject.NULL))
                }
            }
            if (rebuilt.length() == 0 && fallbackTitle.isNotBlank()) {
                rebuilt.put(JSONObject().put("title", fallbackTitle).put("description", source.trim())
                    .put("original_text", source.trim()).put("due_date", JSONObject.NULL))
            }
            group.put("tasks", rebuilt)
            group.put("ideas", JSONArray())
        }
        return result
    }

    private fun parseCandidate(value: String): JSONObject? { val cleaned=value.replace("```json","",true).replace("```","").trim(); val start=cleaned.indexOf('{'); val end=cleaned.lastIndexOf('}'); if(start<0||end<=start)return null; return runCatching{JSONObject(cleaned.substring(start,end+1))}.getOrNull() }
    fun testConnection(): Pair<Int,String> { val conn=(URL("https://api.deepseek.com/models").openConnection() as HttpURLConnection).apply{requestMethod="GET";connectTimeout=10000;readTimeout=15000;setRequestProperty("Authorization","Bearer $apiKey")}; val code=conn.responseCode; val stream=if(code in 200..299)conn.inputStream else conn.errorStream; val detail=stream?.bufferedReader()?.use{it.readText().take(240)}.orEmpty(); conn.disconnect(); return code to detail }

    private fun requestJson(prompt: String, webSearch: Boolean = false): JSONObject {
        return runCatching { requestJsonOnce(prompt, webSearch) }.getOrElse { firstError ->
            // Web search can occasionally return an empty JSON content block or time out. Retry once
            // without the optional tool so the user still gets a usable note instead of a hard failure.
            if (!webSearch) throw firstError
            runCatching { requestJsonOnce(prompt, false) }.getOrElse { throw firstError }
        }
    }

    private fun requestJsonOnce(prompt: String, webSearch: Boolean): JSONObject {
        val body = JSONObject().put("model", model).put("reasoning", JSONObject().put("effort", if (webSearch) "low" else "none"))
            .put("input", prompt).put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
            .put("max_output_tokens", if (webSearch) 12000 else 6000)
        if (webSearch) {
            body.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            body.put("tool_choice", JSONObject().put("type", "web_search"))
        }
        val conn = (URL("https://api.deepseek.com/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = if (webSearch) 90000 else 45000; doOutput = true
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Authorization", "Bearer $apiKey")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) error("DeepSeek $code: $response")
        val envelope = JSONObject(response)
        parseCandidate(envelope.optString("output_text", "").trim())?.let { return it }
        val choices = envelope.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            parseCandidate(choices.optJSONObject(0)?.optJSONObject("message")?.optString("content", "").orEmpty())?.let { return it }
        }
        val output = envelope.optJSONArray("output") ?: return envelope
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") != "output_text") continue
                parseCandidate(part.optString("text", part.optString("output_text", "")).trim())?.let { return it }
            }
        }
        if (envelope.has("questions") || envelope.has("markdown") || envelope.has("groups")) return envelope
        error("DeepSeek 返回了空的 JSON 内容")
    }

    fun noteQuestions(text: String): JSONObject {
        val prompt = """你是一个善于澄清需求的中文产品规划助手。用户想把下面这段零散想法整理成一份高质量 Markdown 计划笔记。先不要生成计划，只提出 3 到 5 个最有价值的澄清问题。
用户想法：
$text

每个问题必须帮助确定目标、范围、优先顺序、交付形式或关键约束。每个问题给出恰好 3 个具体选项，用户可以同时选择多个选项；同时允许用户自定义输入。不要问用户已经明确表达过的内容，不要重复问题。只返回 JSON：
{"questions":[{"question":"问题文本","options":["选项一","选项二","选项三"],"allow_custom":true,"allow_multiple":true}]}
""".trimIndent()
        return requestJson(prompt)
    }

    fun generateMarkdownNote(text: String, answers: String): JSONObject {
        val prompt = """你是一个有较高自主性的中文信息整理助手。请按照用户原始想法的“对象和产物”来整理 Markdown 笔记，不要擅自改写成工作流程。

原始想法：
$text

用户回答：
$answers

生成前优先使用联网搜索深入查找真实、具体、目前仍有效的信息，并交叉核对关键字段。优先采用官方网站、政府/文旅页面、品牌官方页面、可信地图或成熟生活服务平台；价格可能浮动时写“约”或价格区间，并保留检索到的来源链接。如果联网搜索暂时不可用，也要直接根据用户原文生成有用的笔记，不得返回空内容、占位条目或报错。
请自行判断合理的标题和项目边界，允许一个想法拆分成多个项目。Markdown 正文只允许保留项目标题和一层待办列表，不要目标/背景、待办规划、风险、验收标准、依赖、过程步骤或其他说明性章节。每个待办必须是用户最终想得到的真实对象或成果，而不是“收集资料、确定名单、撰写文档”这类制作流程。
例如用户要整理“餐厅攻略”，先联网检索真实餐厅并核对，再让每一家具体餐厅/具体美食成为一条待办，写明真实名称、详细地址、招牌菜、人均价格、推荐理由和来源；不要生成“确定餐厅名单”“收集餐厅信息”“撰写攻略”，也绝对不要生成“餐厅条目（推荐第1位）”之类占位条目。用户要清单、目录、地点、产品、人物或方案时，条目必须分别对应检索到的真实对象。
每条待办使用一层 Markdown 无序任务项。允许在同一条待办下面换行写清楚细节，但只能使用普通缩进文本，不能再嵌套第二层项目符号或任务项。例如：
- [ ] 真实餐厅名称
  详细地址：...；招牌/核心信息：...；人均价格：约...；推荐理由：...；来源：[来源名称](https://...)
换行内容仍属于上一条待办，严格保留用户要求的字段和排序方式。只收录核心字段能够被搜索结果支持的对象；宁可减少数量，也不要输出“未提供”“待补充”“未知”或虚构内容。每个顶级待办必须对应一个真实对象，不能合并。只返回 JSON：
{"title":"简洁明确的笔记标题","markdown":"## 项目：项目名称\\n- [ ] 真实条目名称\\n  详细地址：...；招牌/核心信息：...；人均价格：约...；推荐理由：...；来源：[来源名称](https://...)"}
""".trimIndent()
        val result = requestJson(prompt, webSearch = true)
        val markdown = result.optString("markdown").trim()
        if (markdown.isBlank() || Regex("餐厅条目|推荐第\\d+位|未提供|待补充|未知").containsMatchIn(markdown)) {
            error("联网搜索没有返回足够具体的有效条目，请调整问题后重试")
        }
        return result
    }

    fun organize(text: String, projects: List<Project>, preferMarkdownItems: Boolean = false): JSONObject {
        val context = projects.joinToString("\n") { "${it.id}: ${it.name} - ${it.summary}" }
        val markdownRule = if (preferMarkdownItems) "\n12. 这是从 Markdown 笔记整理待办。必须优先尊重 Markdown 结构：每一个顶级任务项或顶级无序列表项都必须原样对应一条独立待办，绝对不可合并、概括或改写成一条总任务；子级内容只归入所属待办的 description。源文有几条顶级分点，返回就必须有几条 tasks，并保持原顺序。" else ""
        val prompt = """你是中文项目整理助手。只返回 JSON，不要 Markdown。请把用户的自然语言记录还原成用户真正想记录的项目和待办，不要擅自改造成工作流程。

已有项目（id、名称、描述）：
$context

用户记录：
$text

规则：
1. 项目归属必须优先遵守用户在原文中的明确表达。如果用户说“归入/属于/放到/添加到某项目”，这是最高优先级；项目名不要求与已有项目完全相同，要结合简称、别名、上下文和项目描述做语义匹配。例如“日常里”“旅行那个项目”“液滴实验”都可能是对已有项目的自然称呼。匹配到已有项目时必须填写对应的 project_id，不能只写 project_name，也不能因为名称不完全一致就新建项目。
2. 只有原文没有明确项目归属、且已有项目从目标和语境上也无法合理包含时，才返回 project_id:null，并给出 suggested_project_name 和 suggested_project_summary，供确认页新建项目。不要默认强行归入“日常”。
3. 待办拆分必须尊重原文的结构和顺序。如果用户说“第一条待办是 A，第二条待办是 B”“分别做 A、B、C”或连续列出多个事项，就必须生成多条独立 tasks，一项对应一条，保持原顺序；绝不能只生成第一条，也不能把多项合并为一条概括性待办。编号、序号、分号、换行和“另外/然后/再”等连接词都要作为拆分线索。
4. 如果用户只表达了一个完整想法，就生成一条对应的真实待办；如果表达的是多个具体对象或成果，就按对象/成果逐条生成。不要把用户想要的最终对象改成“收集资料、确定名单、撰写文档、分析内容”这类工作流程，除非用户明确要求这些流程本身。
5. 每个用户想法都必须至少对应一条 tasks；识别到已有项目时，也必须在该项目下创建待办，不能只返回项目判断或项目空壳。不得把内容留在 unassigned，该数组必须为空。
6. 待办标题只保留核心对象或成果；description（整理描述）必须在不改变原意的前提下重新组织语言，把原文中的目标、对象、范围、约束和输出要求写得逻辑通顺、明确可执行。整理描述通常控制在原文长度附近，最多比原文略长，不要大段扩写，不要添加用户没有说过的背景、流程、风险、验收标准、技术方案、日期或结论；不要只机械复制原文。尽量保留每条待办的原文依据到 original_text。没有明确截止时间时 due_date 必须为 null。
7. 每个 group 都必须有 confidence 和 reason，简短说明项目归属判断。一个 group 只能对应一个项目，tasks 数组必须包含该组的全部待办。
$markdownRule

JSON格式：{"summary":"","groups":[{"project_id":null,"project_name":"","suggested_project_name":"","suggested_project_summary":"","confidence":0.9,"reason":"","instruction":"","ideas":[],"tasks":[{"title":"","description":"","original_text":"","due_date":null}]}],"unassigned":[]}"""
        val result = requestJson(prompt)
        val normalized = if (preferMarkdownItems) enforceMarkdownTasks(result, text) else result
        return ensureOrganizedTasks(normalized, text)
    }
    fun validate(result: JSONObject): Boolean {
        if (!result.has("groups")) return false
        val groups=result.optJSONArray("groups") ?: return false
        for(i in 0 until groups.length()){val g=groups.optJSONObject(i)?:return false; if(!g.has("project_id")&&!g.has("suggested_project_name"))return false; val tasks=g.optJSONArray("tasks")?:continue; for(j in 0 until tasks.length()){val t=tasks.optJSONObject(j)?:return false; if(t.optString("title").isBlank())return false}}
        return true
    }
}
