package com.idearecorder.app

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One-way WebDAV backup. Local data is always the source of truth. */
class WebDavSync(
    private val protocol: () -> String,
    private val address: () -> String,
    private val username: () -> String,
    private val password: () -> String,
    private val autoSync: () -> Boolean,
    private val payload: () -> String,
    private val mergePayload: (String) -> Unit,
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private var pending: ScheduledFuture<*>? = null

    fun enabled(): Boolean = autoSync() && address().trim().isNotBlank()

    fun schedule() {
        if (!enabled()) return
        pending?.cancel(false)
        pending = executor.schedule({ runCatching { upload() } }, 900, TimeUnit.MILLISECONDS)
    }

    fun testConnection(): Pair<Boolean, String> = runCatching {
        ensureCollection(targetUrl())
        syncOnce()
        true to "连接成功，IdeaRecorder 文件夹已准备好"
    }.getOrElse { false to (it.message ?: "连接失败") }

    private fun upload() {
        if (enabled()) runCatching { syncOnce() }
    }

    /** Pull the remote snapshot first, merge it locally, then publish the merged snapshot. */
    private fun syncOnce() {
        val folder = targetUrl()
        ensureCollection(folder)
        val remoteProjects = getJson("$folder/projects.json")
        val remoteNotes = getJson("$folder/notes.json")
        if (remoteProjects != null || remoteNotes != null) {
            val remote = org.json.JSONObject()
                .put("schema_version", 8)
                .put("projects", remoteProjects?.optJSONArray("projects") ?: org.json.JSONArray())
                .put("notes", remoteNotes?.optJSONArray("notes") ?: org.json.JSONArray())
                .put("deleted", remoteProjects?.optJSONArray("deleted") ?: org.json.JSONArray())
            remoteProjects?.optString("device_id")?.takeIf { it.isNotBlank() }?.let { remote.put("device_id", it) }
            remoteNotes?.optString("device_id")?.takeIf { it.isNotBlank() }?.let { remote.put("device_id", it) }
            remoteProjects?.optString("api_key")?.takeIf { it.isNotBlank() }?.let { remote.put("api_key", it) }
            mergePayload(remote.toString())
        }
        uploadPayloads(payload())
    }

    private fun uploadPayloads(fullPayload: String) {
        val root = org.json.JSONObject(fullPayload)
        val folder = targetUrl()
        ensureCollection(folder)
        val exportedAt = root.optLong("exported_at", System.currentTimeMillis())
        putJson("$folder/projects.json", org.json.JSONObject()
            .put("schema_version", root.optInt("schema_version", 7))
            .put("device_id", root.optString("device_id"))
            .put("exported_at", exportedAt)
            .put("api_key", root.optString("api_key"))
            .put("deleted", root.optJSONArray("deleted") ?: org.json.JSONArray())
            .put("projects", root.optJSONArray("projects") ?: org.json.JSONArray()).toString(2))
        putJson("$folder/notes.json", org.json.JSONObject()
            .put("schema_version", root.optInt("schema_version", 7))
            .put("device_id", root.optString("device_id"))
            .put("exported_at", exportedAt)
            .put("notes", root.optJSONArray("notes") ?: org.json.JSONArray()).toString(2))
        putJson("$folder/metadata.json", org.json.JSONObject()
            .put("app", "Idea Recorder")
            .put("schema_version", root.optInt("schema_version", 7))
            .put("exported_at", exportedAt)
            .put("device_id", root.optString("device_id"))
            .put("synced_at", System.currentTimeMillis()).toString(2))
    }

    private fun targetUrl(): String {
        val raw = address().trim().removeSuffix("/")
        require(raw.isNotBlank()) { "请输入 WebDAV 地址" }
        val withoutScheme = raw.removePrefix("http://").removePrefix("https://").trim('/').trim()
        require(withoutScheme.isNotBlank() && !withoutScheme.contains(" ")) { "WebDAV 地址格式不正确" }
        return "${protocol().ifBlank { "https" }}://$withoutScheme/IdeaRecorder"
    }

    private fun ensureCollection(url: String) {
        request("MKCOL", "$url/").use { response ->
            if (response.code !in setOf(200, 201, 204, 405)) {
                error("创建同步文件夹失败：HTTP ${response.code}")
            }
        }
    }

    private fun putJson(url: String, content: String) {
        val body = content.toRequestBody("application/json; charset=utf-8".toMediaType())
        request("PUT", url, body).use { response ->
            if (!response.isSuccessful) {
                val hint = if (response.code == 405) "；服务器未开放 WebDAV PUT，请检查 WebDAV 地址和端口" else ""
                error("上传同步文件失败：HTTP ${response.code}$hint")
            }
        }
    }

    private fun getJson(url: String): org.json.JSONObject? {
        request("GET", url).use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) error("下载同步文件失败：HTTP ${response.code}")
            return org.json.JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun request(method: String, url: String, body: RequestBody? = null): okhttp3.Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "IdeaRecorder/0.3.1")
        if (username().isNotBlank()) builder.header("Authorization", basicAuth())
        return client.newCall(builder.method(method, body).build()).execute()
    }

    private fun basicAuth(): String {
        val token = "${username()}:${password()}".toByteArray(StandardCharsets.UTF_8)
        return "Basic ${Base64.encodeToString(token, Base64.NO_WRAP)}"
    }

    fun shutdown() { pending?.cancel(false); executor.shutdownNow() }
}
