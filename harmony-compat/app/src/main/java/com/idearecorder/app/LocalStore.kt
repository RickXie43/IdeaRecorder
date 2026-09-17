package com.idearecorder.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Project(
    val id: Long,
    val name: String,
    val summary: String,
    val sortOrder: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
data class Task(
    val id: Long,
    val projectId: Long,
    val title: String,
    val description: String,
    val status: String,
    val priority: String,
    val dueDate: String?,
    val createdAt: Long,
    val originalText: String = description,
    val completedAt: Long? = null,
    val updatedAt: Long = createdAt,
)
data class Note(
    val id: Long,
    val text: String,
    val source: String,
    val state: String,
    val createdAt: Long,
    val title: String = "",
    val updatedAt: Long = createdAt,
    val sortOrder: Long = 0,
    val audioPath: String? = null,
)

data class ImportResult(val projects: Int, val tasks: Int, val notes: Int)
data class TaskDraft(
    val title: String,
    val description: String = "",
    val originalText: String = "",
    val dueDate: String? = null,
)

/** The deliberately small local data store used by the redesigned Android UI. */
class LocalStore(context: Context) : SQLiteOpenHelper(context, "idea_recorder.db", null, 9) {
    var onDataChanged: (() -> Unit)? = null
    private val deviceId = context.getSharedPreferences("idea_recorder_sync", Context.MODE_PRIVATE)
        .getString("device_id", null).let { existing ->
            existing ?: UUID.randomUUID().toString().also { context.getSharedPreferences("idea_recorder_sync", Context.MODE_PRIVATE).edit().putString("device_id", it).apply() }
        }

    private fun changed() { onDataChanged?.invoke() }

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS tasks")
            db.execSQL("DROP TABLE IF EXISTS notes")
            db.execSQL("DROP TABLE IF EXISTS projects")
            createSchema(db)
        } else if (oldVersion < 4) {
            db.execSQL("ALTER TABLE notes ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notes ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE notes SET title=substr(trim(text), 1, 40), updated_at=created_at WHERE title='' OR updated_at=0")
        }
        if (oldVersion in 3..4) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN original_text TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE tasks SET original_text=description WHERE original_text='' OR original_text IS NULL")
        }
        if (oldVersion in 3..5) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN completed_at INTEGER")
            db.execSQL("UPDATE tasks SET completed_at=updated_at WHERE status='done' AND completed_at IS NULL")
        }
        if (oldVersion in 3..6) {
            db.execSQL("ALTER TABLE projects ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE projects SET sort_order=id WHERE sort_order=0")
            db.execSQL("UPDATE notes SET sort_order=-id WHERE sort_order=0")
        }
        if (oldVersion < 8) {
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_keys(kind TEXT NOT NULL, sync_id TEXT PRIMARY KEY, local_id INTEGER NOT NULL)")
        }
        if (oldVersion < 9) {
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_tombstones(kind TEXT NOT NULL, sync_id TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)")
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Defensive repair for databases created by an intermediate 0.3.1 build.
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_keys(kind TEXT NOT NULL, sync_id TEXT PRIMARY KEY, local_id INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_tombstones(kind TEXT NOT NULL, sync_id TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)")
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, summary TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, source TEXT NOT NULL, audio_path TEXT, state TEXT NOT NULL, created_at INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', original_text TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'todo', priority TEXT NOT NULL DEFAULT 'medium', due_date TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, completed_at INTEGER, FOREIGN KEY(project_id) REFERENCES projects(id))")
        db.execSQL("CREATE TABLE sync_keys(kind TEXT NOT NULL, sync_id TEXT PRIMARY KEY, local_id INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE sync_tombstones(kind TEXT NOT NULL, sync_id TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)")
    }

    private fun syncId(kind: String, localId: Long): String {
        val db = writableDatabase
        val existing = db.rawQuery("SELECT sync_id FROM sync_keys WHERE kind=? AND local_id=?", arrayOf(kind, localId.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        if (existing != null) return existing
        val value = "$deviceId:$kind:$localId"
        db.execSQL("INSERT OR IGNORE INTO sync_keys(kind,sync_id,local_id) VALUES(?,?,?)", arrayOf(kind, value, localId))
        return value
    }

    private fun localIdForSync(kind: String, key: String): Long? = readableDatabase.rawQuery("SELECT local_id FROM sync_keys WHERE kind=? AND sync_id=?", arrayOf(kind, key)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun rememberSyncId(kind: String, key: String, localId: Long) { writableDatabase.execSQL("INSERT OR REPLACE INTO sync_keys(kind,sync_id,local_id) VALUES(?,?,?)", arrayOf(kind, key, localId)) }

    private fun markDeleted(kind: String, localId: Long) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO sync_tombstones(kind,sync_id,deleted_at) VALUES(?,?,?)", arrayOf(kind, syncId(kind, localId), System.currentTimeMillis()))
    }

    private fun tombstonesJson(): JSONArray = readableDatabase.rawQuery("SELECT kind,sync_id,deleted_at FROM sync_tombstones", null).use { cursor ->
        JSONArray().also { array -> while (cursor.moveToNext()) array.put(JSONObject().put("kind", cursor.getString(0)).put("sync_id", cursor.getString(1)).put("deleted_at", cursor.getLong(2))) }
    }

    fun syncDeviceId(): String = deviceId

    fun seedDefaults() {
        if (projects().isNotEmpty()) return
        val tutorial = addProject("开始使用 Idea Recorder", "随时记录想法，不需要提前组织语言，再让 AI 帮你整理")
        addTask(tutorial, "点击“＋”记录一个想法", "直接写下脑海中的内容即可，不需要整理格式，也不需要组织完整语言，想到什么写什么。", "medium")
        addTask(tutorial, "选择“AI 整理为待办”", "AI 会理解你的想法，将内容整理成项目和待办事项。确认结果时，可以选择已有项目，也可以创建新项目。", "medium")
        addTask(tutorial, "选择“AI 整理为笔记”", "AI 会根据你的想法生成一份结构清晰的 Markdown 笔记。过程中可以回答几个简单问题，让笔记更符合你的需要。", "medium")
        addTask(tutorial, "在待办页面管理任务", "点击待办前面的圆点，可以切换未完成和进行中。已完成的待办会自动折叠到项目底部。", "medium")
        addTask(tutorial, "设置截止日期", "待办可以设置截止日期。逾期后圆点会变红，点击红色圆点可以直接标记为已完成。", "low")
        addTask(tutorial, "长按项目或笔记", "长按项目或笔记可以进入选择模式，支持批量选择、删除，也可以拖动调整顺序。", "low")
        addTask(tutorial, "切换待办和笔记", "点击底部“待办 / 笔记”横栏可以切换页面，也可以左右滑动切换。", "low")
        addTask(tutorial, "长按删除“开始使用 Idea Recorder”项目", "教程看完后，长按这个项目进入选择模式，点击底部“删除”即可删除教程项目，然后开始记录自己的想法吧～", "low")
    }

    fun projects(): List<Project> = readableDatabase.rawQuery(
        "SELECT id,name,summary,sort_order,created_at,updated_at FROM projects WHERE archived=0 ORDER BY sort_order ASC,id ASC", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(Project(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getLong(4), cursor.getLong(5))) } }

    fun addProject(name: String, summary: String): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.compileStatement("INSERT INTO projects(name,summary,created_at,updated_at,sort_order) VALUES(?,?,?,?,?)").apply {
            bindString(1, name.trim()); bindString(2, summary.trim()); bindLong(3, now); bindLong(4, now); bindLong(5, now)
        }.executeInsert()
        changed()
        return id
    }

    fun updateProjectSummary(id: Long, summary: String) {
        writableDatabase.execSQL("UPDATE projects SET summary=?,updated_at=? WHERE id=?", arrayOf(summary, System.currentTimeMillis(), id)); changed()
    }

    fun deleteProjects(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            ids.forEach { id ->
                tasks(id).forEach { markDeleted("task", it.id) }
                markDeleted("project", id)
                writableDatabase.delete("tasks", "project_id=?", arrayOf(id.toString()))
                writableDatabase.delete("projects", "id=?", arrayOf(id.toString()))
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        changed()
    }

    fun addNote(text: String, source: String = "text", audioPath: String? = null, title: String = ""): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.compileStatement("INSERT INTO notes(text,source,audio_path,state,created_at,title,updated_at,sort_order) VALUES(?,?,?,?,?,?,?,?)").apply {
            bindString(1, text); bindString(2, source); if (audioPath == null) bindNull(3) else bindString(3, audioPath); bindString(4, "ready"); bindLong(5, now); bindString(6, title.trim()); bindLong(7, now); bindLong(8, -now)
        }.executeInsert()
        changed()
        return id
    }

    fun updateNoteState(id: Long, state: String) { writableDatabase.execSQL("UPDATE notes SET state=? WHERE id=?", arrayOf(state, id)); changed() }

    fun updateNote(id: Long, text: String, title: String = "") {
        writableDatabase.execSQL("UPDATE notes SET text=?,title=?,state=?,updated_at=? WHERE id=?", arrayOf(text.trim(), title.trim(), "ready", System.currentTimeMillis(), id)); changed()
    }

    fun deleteNotes(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            ids.forEach { id -> markDeleted("note", id); writableDatabase.delete("notes", "id=?", arrayOf(id.toString())) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        changed()
    }

    fun notesByState(): List<Note> = readableDatabase.rawQuery("SELECT id,text,source,audio_path,state,created_at,title,updated_at,sort_order FROM notes ORDER BY sort_order ASC,id DESC", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Note(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(4), cursor.getLong(5), cursor.getString(6), cursor.getLong(7), cursor.getLong(8), if (cursor.isNull(3)) null else cursor.getString(3))) }
    }

    fun reorderProjects(ids: List<Long>) {
        writableDatabase.beginTransaction()
        try { ids.forEachIndexed { index, id -> writableDatabase.execSQL("UPDATE projects SET sort_order=? WHERE id=?", arrayOf(index, id)) }; writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
        changed()
    }

    fun reorderNotes(ids: List<Long>) {
        writableDatabase.beginTransaction()
        try { ids.forEachIndexed { index, id -> writableDatabase.execSQL("UPDATE notes SET sort_order=? WHERE id=?", arrayOf(index, id)) }; writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
        changed()
    }

    fun tasks(projectId: Long): List<Task> = readableDatabase.rawQuery(
        "SELECT id,project_id,title,description,status,priority,due_date,created_at,original_text,completed_at,updated_at FROM tasks WHERE project_id=?",
        arrayOf(projectId.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(readTask(cursor)) } }

    fun task(id: Long): Task? = readableDatabase.rawQuery(
        "SELECT id,project_id,title,description,status,priority,due_date,created_at,original_text,completed_at,updated_at FROM tasks WHERE id=?", arrayOf(id.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) readTask(cursor) else null }

    private fun readTask(cursor: android.database.Cursor): Task = Task(
        cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5),
        if (cursor.isNull(6)) null else cursor.getString(6), cursor.getLong(7), cursor.getString(8), if (cursor.isNull(9)) null else cursor.getLong(9), cursor.getLong(10),
    )

    fun addTask(projectId: Long, title: String, description: String, priority: String = "medium"): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.compileStatement("INSERT INTO tasks(project_id,title,description,original_text,status,priority,created_at,updated_at,completed_at) VALUES(?,?,?,?,'todo',?,?,?,NULL)").apply {
            bindLong(1, projectId); bindString(2, title.trim()); bindString(3, description.trim()); bindString(4, description.trim()); bindString(5, priority); bindLong(6, now); bindLong(7, now)
        }.executeInsert()
        changed()
        return id
    }

    fun addTask(projectId: Long, title: String, description: String, priority: String, originalText: String): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.compileStatement("INSERT INTO tasks(project_id,title,description,original_text,status,priority,created_at,updated_at,completed_at) VALUES(?,?,?,?,'todo',?,?,?,NULL)").apply {
            bindLong(1, projectId); bindString(2, title.trim()); bindString(3, description.trim()); bindString(4, originalText.trim()); bindString(5, priority); bindLong(6, now); bindLong(7, now)
        }.executeInsert()
        changed()
        return id
    }

    fun updateTaskContent(id: Long, originalText: String, organizedText: String) {
        writableDatabase.execSQL("UPDATE tasks SET original_text=?,description=?,updated_at=? WHERE id=?", arrayOf(originalText.trim(), organizedText.trim(), System.currentTimeMillis(), id)); changed()
    }

    fun updateTaskTitle(id: Long, title: String) {
        writableDatabase.execSQL("UPDATE tasks SET title=?,updated_at=? WHERE id=?", arrayOf(title.trim(), System.currentTimeMillis(), id)); changed()
    }

    fun moveTask(id: Long, projectId: Long) {
        writableDatabase.execSQL("UPDATE tasks SET project_id=?,updated_at=? WHERE id=?", arrayOf(projectId, System.currentTimeMillis(), id)); changed()
    }

    fun updateTask(id: Long, title: String, description: String, priority: String, dueDate: String?, status: String) {
        val now = System.currentTimeMillis()
        val completedAt = if (status == "done") task(id)?.completedAt ?: now else null
        writableDatabase.execSQL("UPDATE tasks SET title=?,description=?,priority=?,due_date=?,status=?,updated_at=?,completed_at=? WHERE id=?", arrayOf(title.trim(), description.trim(), priority, dueDate, status, now, completedAt, id)); changed()
    }

    fun updateTaskStatus(id: Long, status: String) {
        val now = System.currentTimeMillis()
        val completedAt = if (status == "done") now else null
        writableDatabase.execSQL("UPDATE tasks SET status=?,updated_at=?,completed_at=? WHERE id=?", arrayOf(status, now, completedAt, id)); changed()
    }

    fun deleteTask(id: Long) { markDeleted("task", id); writableDatabase.delete("tasks", "id=?", arrayOf(id.toString())); changed() }

    fun deleteTasks(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            ids.forEach { id -> markDeleted("task", id); writableDatabase.delete("tasks", "id=?", arrayOf(id.toString())) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        changed()
    }

    /** Insert all reviewed AI tasks in one transaction so no later item is lost if the UI refreshes. */
    fun addTasks(projectId: Long, drafts: List<TaskDraft>): List<Long> {
        if (drafts.isEmpty()) return emptyList()
        val db = writableDatabase
        val ids = mutableListOf<Long>()
        db.beginTransaction()
        try {
            drafts.forEach { draft ->
                val now = System.currentTimeMillis()
                val statement = db.compileStatement("INSERT INTO tasks(project_id,title,description,original_text,status,priority,due_date,created_at,updated_at,completed_at) VALUES(?,?,?,?,?,?,?,?,?,NULL)")
                statement.bindLong(1, projectId)
                statement.bindString(2, draft.title.trim())
                statement.bindString(3, draft.description.trim())
                statement.bindString(4, draft.originalText.trim().ifBlank { draft.description.trim() })
                statement.bindString(5, "todo")
                statement.bindString(6, "medium")
                if (draft.dueDate.isNullOrBlank()) statement.bindNull(7) else statement.bindString(7, draft.dueDate.trim())
                statement.bindLong(8, now)
                statement.bindLong(9, now)
                ids.add(statement.executeInsert())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
        return ids
    }

    fun exportSelectionJson(projectIds: Collection<Long>, taskIds: Collection<Long>): String {
        val selectedProjects = projects().filter { it.id in projectIds }
        val selectedTaskIds = taskIds.toSet()
        val root = JSONObject().put("schema_version", 8).put("device_id", deviceId).put("exported_at", System.currentTimeMillis())
        val projectsJson = JSONArray()
        selectedProjects.forEach { project ->
            projectsJson.put(projectToJson(project, tasks(project.id)))
        }
        // Add task-only selections grouped under their existing project, without duplicating projects above.
        val selectedProjectIdSet = projectIds.toSet()
        val selectedTaskRows = projects().flatMap { project -> tasks(project.id).filter { it.id in selectedTaskIds && project.id !in selectedProjectIdSet }.map { project to it } }
        selectedTaskRows.groupBy { it.first.id }.forEach { (_, rows) ->
            val project = rows.first().first
            projectsJson.put(projectToJson(project, rows.map { it.second }))
        }
        root.put("projects", projectsJson).put("notes", JSONArray()).put("deleted", tombstonesJson())
        return root.toString(2)
    }

    /** A compact, deterministic prompt format intended to be pasted into a coding AI. */
    fun exportSelectionVibePrompt(projectIds: Collection<Long>, taskIds: Collection<Long>): String {
        val selectedProjectIds = projectIds.toSet()
        val selectedTaskIds = taskIds.toSet()
        val selectedProjects = projects().filter { it.id in selectedProjectIds }
        val taskOnlyProjects = projects().filter { project ->
            project.id !in selectedProjectIds && tasks(project.id).any { it.id in selectedTaskIds }
        }
        val allProjects = (selectedProjects + taskOnlyProjects).distinctBy { it.id }
        return buildString {
            allProjects.forEachIndexed { index, project ->
                if (index > 0) append("\n")
                append("项目：").append(project.name).append("\n\n")
                val projectTasks = if (project.id in selectedProjectIds) tasks(project.id) else tasks(project.id).filter { it.id in selectedTaskIds }
                projectTasks.forEachIndexed { taskIndex, task ->
                    val title = task.title.replace(Regex("\\s+"), " ").trim()
                    val detail = task.description.ifBlank { task.originalText }.ifBlank { task.title }
                        .replace(Regex("\\s+"), " ").trim().trimEnd('；', ';', '。')
                    append(taskIndex + 1).append(". ").append(title).append("：").append(detail).append("；\n")
                }
            }
        }.trim()
    }

    private fun projectToJson(project: Project, projectTasks: List<Task>): JSONObject {
        val tasksJson = JSONArray()
        projectTasks.forEach { task ->
            tasksJson.put(JSONObject().put("id", task.id).put("sync_id", syncId("task", task.id)).put("title", task.title).put("description", task.description)
                .put("original_text", task.originalText).put("status", task.status).put("priority", task.priority)
                .put("due_date", task.dueDate).put("created_at", task.createdAt).put("updated_at", task.updatedAt)
                .put("completed_at", task.completedAt))
        }
        return JSONObject().put("id", project.id).put("sync_id", syncId("project", project.id)).put("name", project.name).put("summary", project.summary)
            .put("sort_order", project.sortOrder).put("created_at", project.createdAt).put("updated_at", project.updatedAt)
            .put("tasks", tasksJson)
    }

    fun exportJson(): String {
        val root = JSONObject().put("schema_version", 8).put("device_id", deviceId).put("exported_at", System.currentTimeMillis())
        val projectsJson = JSONArray()
        projects().forEach { project ->
            val tasksJson = JSONArray()
            tasks(project.id).forEach { task ->
                tasksJson.put(JSONObject().put("id", task.id).put("sync_id", syncId("task", task.id)).put("title", task.title).put("description", task.description)
                    .put("original_text", task.originalText).put("status", task.status).put("priority", task.priority)
                    .put("due_date", task.dueDate).put("created_at", task.createdAt).put("updated_at", task.updatedAt)
                    .put("completed_at", task.completedAt))
            }
            projectsJson.put(JSONObject().put("id", project.id).put("sync_id", syncId("project", project.id)).put("name", project.name).put("summary", project.summary)
                .put("sort_order", project.sortOrder).put("created_at", project.createdAt).put("updated_at", project.updatedAt)
                .put("tasks", tasksJson))
        }
        root.put("projects", projectsJson)
        root.put("notes", JSONArray().also { array -> notesByState().forEach { note ->
            array.put(JSONObject().put("id", note.id).put("sync_id", syncId("note", note.id)).put("text", note.text).put("title", note.title).put("source", note.source)
                .put("audio_path", note.audioPath).put("state", note.state).put("created_at", note.createdAt)
                .put("updated_at", note.updatedAt).put("sort_order", note.sortOrder))
        } }).put("deleted", tombstonesJson())
        return root.toString(2)
    }

    fun importJson(payload: String, notifyChange: Boolean = true): ImportResult {
        val root = JSONObject(payload)
        val projectArray = root.optJSONArray("projects") ?: JSONArray()
        val noteArray = root.optJSONArray("notes") ?: JSONArray()
        var projectCount = 0
        var taskCount = 0
        var noteCount = 0
        val db = writableDatabase
        val orderBase = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (i in 0 until projectArray.length()) {
                val sourceProject = projectArray.optJSONObject(i) ?: continue
                val name = sourceProject.optString("name").trim()
                if (name.isBlank()) continue
                val createdAt = sourceProject.optLong("created_at", orderBase + i)
                val updatedAt = sourceProject.optLong("updated_at", createdAt)
                val sourceSyncId = sourceProject.optString("sync_id").trim()
                val knownProjectId = sourceSyncId.takeIf { it.isNotBlank() }?.let { localIdForSync("project", it) }
                val projectId = if (knownProjectId != null) {
                    val localUpdated = db.rawQuery("SELECT updated_at FROM projects WHERE id=?", arrayOf(knownProjectId.toString())).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    if (updatedAt > localUpdated) db.execSQL("UPDATE projects SET name=?,summary=?,updated_at=?,sort_order=? WHERE id=?", arrayOf(name, sourceProject.optString("summary").trim(), updatedAt, sourceProject.optLong("sort_order", orderBase + i), knownProjectId))
                    knownProjectId
                } else {
                    val inserted = db.compileStatement("INSERT INTO projects(name,summary,archived,created_at,updated_at,sort_order) VALUES(?,?,0,?,?,?)").apply {
                        bindString(1, name); bindString(2, sourceProject.optString("summary").trim()); bindLong(3, createdAt); bindLong(4, updatedAt); bindLong(5, orderBase + i)
                    }.executeInsert()
                    if (sourceSyncId.isNotBlank()) rememberSyncId("project", sourceSyncId, inserted)
                    projectCount++
                    inserted
                }
                val taskArray = sourceProject.optJSONArray("tasks") ?: JSONArray()
                for (j in 0 until taskArray.length()) {
                    val sourceTask = taskArray.optJSONObject(j) ?: continue
                    val title = sourceTask.optString("title").trim()
                    if (title.isBlank()) continue
                    val description = sourceTask.optString("description").trim()
                    val original = sourceTask.optString("original_text").trim().ifBlank { description }
                    val status = sourceTask.optString("status", "todo").trim().ifBlank { "todo" }
                    val priority = sourceTask.optString("priority", "medium").trim().ifBlank { "medium" }
                    val created = sourceTask.optLong("created_at", orderBase + j)
                    val updated = sourceTask.optLong("updated_at", created)
                    val due = sourceTask.optString("due_date").trim()
                    val completedAt = if (sourceTask.has("completed_at") && !sourceTask.isNull("completed_at")) sourceTask.optLong("completed_at") else 0L
                    val sourceSyncId = sourceTask.optString("sync_id").trim()
                    val knownTaskId = sourceSyncId.takeIf { it.isNotBlank() }?.let { localIdForSync("task", it) }
                    if (knownTaskId != null) {
                        val localUpdated = db.rawQuery("SELECT updated_at FROM tasks WHERE id=?", arrayOf(knownTaskId.toString())).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                        if (updated > localUpdated) db.execSQL("UPDATE tasks SET project_id=?,title=?,description=?,original_text=?,status=?,priority=?,due_date=?,updated_at=?,completed_at=? WHERE id=?", arrayOf(projectId, title, description, original, status, priority, if (due.isBlank() || due == "null") null else due, updated, if (completedAt == 0L) null else completedAt, knownTaskId))
                    } else {
                        val statement = db.compileStatement("INSERT INTO tasks(project_id,title,description,original_text,status,priority,due_date,created_at,updated_at,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?)")
                        statement.bindLong(1, projectId); statement.bindString(2, title); statement.bindString(3, description); statement.bindString(4, original); statement.bindString(5, status); statement.bindString(6, priority)
                        if (due.isBlank() || due == "null") statement.bindNull(7) else statement.bindString(7, due)
                        statement.bindLong(8, created); statement.bindLong(9, updated)
                        if (completedAt == 0L) statement.bindNull(10) else statement.bindLong(10, completedAt)
                        val inserted = statement.executeInsert()
                        if (sourceSyncId.isNotBlank()) rememberSyncId("task", sourceSyncId, inserted)
                        taskCount++
                    }
                }
            }
            for (i in 0 until noteArray.length()) {
                val sourceNote = noteArray.optJSONObject(i) ?: continue
                val text = sourceNote.optString("text")
                val title = sourceNote.optString("title").trim()
                if (text.isBlank() && title.isBlank()) continue
                val created = sourceNote.optLong("created_at", orderBase + i)
                val updated = sourceNote.optLong("updated_at", created)
                val noteSort = orderBase + i
                val audioPath = sourceNote.optString("audio_path").trim()
                val sourceSyncId = sourceNote.optString("sync_id").trim()
                val knownNoteId = sourceSyncId.takeIf { it.isNotBlank() }?.let { localIdForSync("note", it) }
                if (knownNoteId != null) {
                    val localUpdated = db.rawQuery("SELECT updated_at FROM notes WHERE id=?", arrayOf(knownNoteId.toString())).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    if (updated > localUpdated) db.execSQL("UPDATE notes SET text=?,source=?,audio_path=?,state=?,title=?,updated_at=?,sort_order=? WHERE id=?", arrayOf(text, sourceNote.optString("source", "text").ifBlank { "text" }, if (audioPath.isBlank() || audioPath == "null") null else audioPath, sourceNote.optString("state", "ready").ifBlank { "ready" }, title, updated, noteSort, knownNoteId))
                } else {
                    val statement = db.compileStatement("INSERT INTO notes(text,source,audio_path,state,created_at,title,updated_at,sort_order) VALUES(?,?,?,?,?,?,?,?)")
                    statement.bindString(1, text); statement.bindString(2, sourceNote.optString("source", "text").ifBlank { "text" })
                    if (audioPath.isBlank() || audioPath == "null") statement.bindNull(3) else statement.bindString(3, audioPath)
                    statement.bindString(4, sourceNote.optString("state", "ready").ifBlank { "ready" }); statement.bindLong(5, created); statement.bindString(6, title); statement.bindLong(7, updated); statement.bindLong(8, noteSort)
                    val inserted = statement.executeInsert()
                    if (sourceSyncId.isNotBlank()) rememberSyncId("note", sourceSyncId, inserted)
                    noteCount++
                }
            }
            val deletedArray = root.optJSONArray("deleted") ?: JSONArray()
            for (i in 0 until deletedArray.length()) {
                val deleted = deletedArray.optJSONObject(i) ?: continue
                val kind = deleted.optString("kind").trim()
                val key = deleted.optString("sync_id").trim()
                if (kind.isBlank() || key.isBlank()) continue
                val deletedAt = deleted.optLong("deleted_at", System.currentTimeMillis())
                db.execSQL("INSERT OR REPLACE INTO sync_tombstones(kind,sync_id,deleted_at) VALUES(?,?,?)", arrayOf(kind, key, deletedAt))
                val localId = localIdForSync(kind, key) ?: continue
                when (kind) {
                    "project" -> { db.delete("tasks", "project_id=?", arrayOf(localId.toString())); db.delete("projects", "id=?", arrayOf(localId.toString())) }
                    "task" -> db.delete("tasks", "id=?", arrayOf(localId.toString()))
                    "note" -> db.delete("notes", "id=?", arrayOf(localId.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (notifyChange) changed()
        return ImportResult(projectCount, taskCount, noteCount)
    }
}
