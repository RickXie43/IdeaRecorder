package com.idearecorder.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.text.Spannable
import android.text.style.CharacterStyle
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private var compilingMarkdown = false
    private var pendingEnterDirection = 0
    private var noteEditorBack: (() -> Unit)? = null
    private var projectSelectionMode = false
    private val selectedProjectIds = linkedSetOf<Long>()
    private val selectedTaskIds = linkedSetOf<Long>()
    private var notebookSelectionMode = false
    private val selectedNoteIds = linkedSetOf<Long>()
    private var projectScrollView: ScrollView? = null
    private var projectScrollY = 0
    private val expandedCompletedProjectIds = linkedSetOf<Long>()
    private val swipeInterpolator = PathInterpolator(0.22f, 0.8f, 0.2f, 1f)

    /** Two-page, full-screen pager with direct finger tracking and native-style snapping. */
    private inner class FullScreenPager(
        context: android.content.Context,
        initialPage: Int,
        private val onPageChanged: (Int) -> Unit,
    ) : android.view.ViewGroup(context) {
        private val scroller = android.widget.OverScroller(context)
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private val pageFlingVelocity = maxOf(android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6, dp(600))
        private var velocityTracker: android.view.VelocityTracker? = null
        private var downX = 0f
        private var downY = 0f
        private var downScrollX = 0
        private var dragging = false
        private var laidOut = false
        private var currentPage = initialPage

        fun switchToPage(page: Int) {
            if (page != currentPage) settleToPage(page)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(width, height)
            val childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            val childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            for (index in 0 until childCount) getChildAt(index).measure(childWidth, childHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val pageWidth = right - left
            val pageHeight = bottom - top
            for (index in 0 until childCount) {
                getChildAt(index).layout(index * pageWidth, 0, (index + 1) * pageWidth, pageHeight)
            }
            if (!laidOut || changed) {
                scrollTo(currentPage * pageWidth, 0)
                laidOut = true
            }
        }

        override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    scroller.abortAnimation()
                    downX = event.x
                    downY = event.y
                    downScrollX = scrollX
                    dragging = false
                    resetVelocityTracker(event)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (!dragging) recycleVelocityTracker()
            }
            return false
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (velocityTracker == null) resetVelocityTracker(event) else velocityTracker?.addMovement(event)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    scroller.abortAnimation()
                    downX = event.x
                    downY = event.y
                    downScrollX = scrollX
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        dragging = kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f
                    }
                    if (dragging) {
                        val raw = downScrollX + (downX - event.x)
                        val maximum = ((childCount - 1).coerceAtLeast(0) * width).toFloat()
                        val resisted = when {
                            raw < 0f -> raw * 0.24f
                            raw > maximum -> maximum + (raw - maximum) * 0.24f
                            else -> raw
                        }
                        scrollTo(resisted.toInt(), 0)
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    val distance = event.x - downX
                    val nearestPage = ((scrollX + width / 2f) / width.coerceAtLeast(1)).toInt()
                    val target = if (kotlin.math.abs(velocityX) >= pageFlingVelocity && kotlin.math.abs(distance) > dp(24)) {
                        if (velocityX < 0) currentPage + 1 else currentPage - 1
                    } else nearestPage
                    settleToPage(target)
                    recycleVelocityTracker()
                    dragging = false
                    return true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    settleToPage(currentPage)
                    recycleVelocityTracker()
                    dragging = false
                    return true
                }
            }
            return true
        }

        private fun settleToPage(requestedPage: Int) {
            val targetPage = requestedPage.coerceIn(0, (childCount - 1).coerceAtLeast(0))
            val targetX = targetPage * width
            val distance = targetX - scrollX
            val duration = (170 + kotlin.math.abs(distance) * 110 / width.coerceAtLeast(1)).coerceIn(170, 280)
            scroller.startScroll(scrollX, 0, distance, 0, duration)
            postInvalidateOnAnimation()
            if (targetPage != currentPage) {
                currentPage = targetPage
                onPageChanged(currentPage)
            }
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX, scroller.currY)
                postInvalidateOnAnimation()
            }
        }

        private fun resetVelocityTracker(event: android.view.MotionEvent) {
            recycleVelocityTracker()
            velocityTracker = android.view.VelocityTracker.obtain().also { it.addMovement(event) }
        }

        private fun recycleVelocityTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }
    }

    private inner class SwipeFrame(
        context: android.content.Context,
        private val onLeft: (() -> Unit)?,
        private val onRight: (() -> Unit)?,
        private val edgeOnlyRight: Boolean = false,
    ) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var settled = false
        private var canCommit = false

        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragging = false
                    settled = false
                    canCommit = false
                    return super.dispatchTouchEvent(event)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - downX
                    val deltaY = event.y - downY
                    if (!dragging && kotlin.math.abs(deltaX) > dp(8) && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.2f) {
                        val movingRight = deltaX > 0
                        val directionAllowed = if (movingRight) onRight != null && (!edgeOnlyRight || downX < dp(56)) else onLeft != null
                        canCommit = directionAllowed
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging && !settled) {
                        val limit = dp(if (canCommit) 44 else 18).toFloat()
                        val resistance = if (canCommit) 0.14f else 0.05f
                        translationX = (deltaX * resistance).coerceIn(-limit, limit)
                        alpha = 1f - (kotlin.math.abs(translationX) / limit) * 0.025f
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val delta = event.x - downX
                    if (dragging && !settled) {
                        settled = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        val valid = canCommit && kotlin.math.abs(delta) > dp(72)
                        if (valid) {
                            val direction = if (delta < 0) -1 else 1
                            pendingEnterDirection = direction
                            if (direction < 0) onLeft?.invoke() else onRight?.invoke()
                        } else {
                            animate().translationX(0f).alpha(1f).setDuration(140).setInterpolator(swipeInterpolator).start()
                        }
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging && !settled) animate().translationX(0f).alpha(1f).setDuration(140).setInterpolator(swipeInterpolator).start()
                    dragging = false
                }
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private lateinit var store: LocalStore
    private lateinit var secretStore: SecretStore
    private lateinit var recorder: Recorder
    private lateinit var transcriber: OfflineTranscriber
    private lateinit var webDavSync: WebDavSync
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val ideaDraftKey = "record_idea_draft"
    private var pendingExportJson: String? = null
    private val exportFileRequestCode = 2101
    private val importFileRequestCode = 2102
    private var currentScreen = "projects"
    private var expandedProjectId: Long? = null
    private data class DragPayload(val type: String, val id: Long)

    private val blue = Color.rgb(47, 98, 242)
    private val ink = Color.rgb(20, 23, 28)
    private val muted = Color.rgb(119, 123, 132)
    private val page = Color.rgb(248, 249, 252)
    private val line = Color.rgb(229, 231, 236)
    private val green = Color.rgb(35, 190, 102)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        store = LocalStore(this)
        store.seedDefaults()
        secretStore = SecretStore(this)
        webDavSync = WebDavSync(
            protocol = { prefs.getString("webdav_protocol", "https") ?: "https" },
            address = { prefs.getString("webdav_address", "").orEmpty() },
            username = { prefs.getString("webdav_username", "").orEmpty() },
            password = { secretStore.read("webdav_password").orEmpty() },
            autoSync = { prefs.getBoolean("webdav_auto_sync", false) },
            payload = { JSONObject(store.exportJson()).put("api_key", secretStore.read().orEmpty()).toString(2) },
            mergePayload = { remote ->
                val remoteJson = JSONObject(remote)
                store.importJson(remoteJson.toString(), notifyChange = false)
                val importedKey = remoteJson.optString("api_key").trim()
                if (importedKey.isNotBlank()) secretStore.write(importedKey)
            },
        )
        store.onDataChanged = { webDavSync.schedule() }
        recorder = Recorder(this)
        transcriber = OfflineTranscriber(this)
        showProjects()
    }

    override fun onDestroy() {
        if (::webDavSync.isInitialized) webDavSync.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (currentScreen) {
            "projects" -> if (projectSelectionMode) exitProjectSelection() else AlertDialog.Builder(this).setTitle("退出 Idea Recorder？").setMessage("确定要退出应用吗？").setNegativeButton("取消", null).setPositiveButton("退出") { _, _ -> finish() }.show()
            "notebook" -> if (notebookSelectionMode) exitNotebookSelection() else { pendingEnterDirection = 1; showProjects() }
            "note_editor" -> { pendingEnterDirection = 1; noteEditorBack?.invoke() ?: showNotebook() }
            "settings", "task" -> { pendingEnterDirection = 1; showProjects() }
            else -> showProjects()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == exportFileRequestCode && resultCode == Activity.RESULT_OK) {
            val payload = pendingExportJson
            pendingExportJson = null
            val uri = data?.data
            if (payload != null && uri != null) runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法打开保存位置")
            }.onSuccess { Toast.makeText(this, "数据已保存", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, "保存失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show() }
        } else if (requestCode == importFileRequestCode && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                val payload = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("无法读取文件")
                val result = store.importJson(payload)
                val importedKey = JSONObject(payload).optString("api_key").trim()
                if (importedKey.isNotBlank()) secretStore.write(importedKey)
                result
            }.onSuccess { result ->
                Toast.makeText(this, "已导入 ${result.projects} 个项目、${result.tasks} 条待办、${result.notes} 条笔记", Toast.LENGTH_LONG).show()
                showSettings()
            }.onFailure { Toast.makeText(this, "导入失败：${it.message ?: "文件格式不正确"}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun pad(view: View, l: Int, t: Int, r: Int, b: Int) = view.setPadding(dp(l), dp(t), dp(r), dp(b))
    private fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun label(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(0f, 1.15f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun rounded(color: Int, radius: Int = 20, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), line) }
    }

    private fun blueOutline(radius: Int = 14): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), blue)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(Color.WHITE, 20); elevation = dp(1).toFloat(); pad(this, 20, 18, 20, 18)
    }

    private fun outlineButton(value: String, action: () -> Unit): TextView = label(value, 16f, blue, true).apply {
        gravity = Gravity.CENTER; background = blueOutline(14); pad(this, 14, 10, 14, 10); setOnClickListener { action() }
    }

    private fun primaryButton(value: String, action: () -> Unit): TextView = label(value, 18f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; background = rounded(blue, 14); pad(this, 14, 13, 14, 13); setOnClickListener { action() }
    }

    private fun topBar(title: String, back: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(88); pad(this, 0, 16, 0, 0); setBackgroundColor(Color.WHITE)
        if (back) addView(label("‹", 42f, ink).apply { gravity = Gravity.CENTER; setOnClickListener { showProjects() } }, lp(dp(52), -1))
        addView(label(title, 21f, ink, true).apply { gravity = if (back) Gravity.CENTER else Gravity.LEFT or Gravity.CENTER_VERTICAL }, lp(0, -1, 1f))
    }

    private fun screen(title: String, back: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(page); addView(topBar(title, back), lp())
    }

    private fun scroll(content: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true; addView(content); setBackgroundColor(page)
    }

    private fun playEnterAnimation(view: View) {
        val direction = pendingEnterDirection
        pendingEnterDirection = 0
        if (direction == 0) return
        view.post {
            view.translationX = if (direction < 0) dp(28).toFloat() else -dp(28).toFloat()
            view.alpha = 0.97f
            view.animate().translationX(0f).alpha(1f).setDuration(190).setInterpolator(swipeInterpolator).start()
        }
    }

    private fun sectionTitle(value: String): TextView = label(value, 17f, muted, false).apply { pad(this, 24, 18, 18, 8) }

    private fun dot(color: Int): View = View(this).apply { background = GradientDrawable().apply { setColor(color); shape = GradientDrawable.OVAL }; layoutParams = lp(dp(18), dp(18)) }

    private fun projectColor(index: Int): Int = listOf(Color.rgb(45, 99, 244), Color.rgb(106, 53, 218), Color.rgb(255, 137, 14), Color.rgb(40, 174, 112))[index % 4]

    private fun taskDueMillis(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank() || raw == "null") return null
        listOf("yyyy年MM月dd日", "yyyy年M月d日", "yyyy-MM-dd", "yyyy/M/d", "yyyy.M.d").forEach { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.CHINA).apply { isLenient = false }.parse(raw)?.time }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun isTaskOverdue(task: Task): Boolean {
        if (task.status == "done") return false
        val due = taskDueMillis(task.dueDate) ?: return false
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return due < today
    }

    private fun displayTaskDate(value: String?): String {
        val parsed = taskDueMillis(value) ?: return value?.trim().orEmpty()
        return SimpleDateFormat("yyyy.M.d", Locale.CHINA).format(Date(parsed))
    }

    private fun compareTasks(left: Task, right: Task): Int {
        val leftDone = left.status == "done"
        val rightDone = right.status == "done"
        if (leftDone != rightDone) return if (leftDone) 1 else -1
        if (leftDone) return (right.completedAt ?: right.createdAt).compareTo(left.completedAt ?: left.createdAt)
        val leftDue = taskDueMillis(left.dueDate)
        val rightDue = taskDueMillis(right.dueDate)
        if (leftDue == null && rightDue != null) return 1
        if (leftDue != null && rightDue == null) return -1
        if (leftDue != null && rightDue != null && leftDue != rightDue) return leftDue.compareTo(rightDue)
        return left.id.compareTo(right.id)
    }

    private fun showProjects(expanded: Long? = expandedProjectId) {
        expandedProjectId = expanded
        showMainPager(0, expanded)
    }

    private fun showNotebook() = showMainPager(1)

    private fun showMainPager(initialPage: Int, expanded: Long? = null) {
        pendingEnterDirection = 0
        noteEditorBack = null
        currentScreen = if (initialPage == 0) "projects" else "notebook"
        var navigation: LinearLayout? = null
        val pager = FullScreenPager(this, initialPage) { pageIndex ->
            currentScreen = if (pageIndex == 0) "projects" else "notebook"
            navigation?.let { updateBottomNavigation(it, pageIndex) }
        }.apply { setBackgroundColor(page) }
        pager.addView(buildProjectsPage(expanded))
        pager.addView(buildNotebookPage())
        val shell = FrameLayout(this).apply { setBackgroundColor(page) }
        shell.addView(pager, FrameLayout.LayoutParams(-1, -1))
        if (!projectSelectionMode && !notebookSelectionMode) {
            val fab = label("＋", 34f, Color.WHITE, false).apply {
                gravity = Gravity.CENTER
                background = rounded(blue, 50)
                elevation = dp(8).toFloat()
                contentDescription = "记录想法"
                setOnClickListener { newTextNote() }
            }
            shell.addView(fab, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.RIGHT or Gravity.BOTTOM).also {
                it.setMargins(0, 0, dp(28), dp(96))
            })
            val nav = bottomNavigation(initialPage) { target ->
                if (target == 0) showProjects(expandedProjectId) else showNotebook()
            }
            navigation = nav
            shell.addView(nav, FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM))
        }
        setContentView(shell)
    }

    private fun bottomNavigation(selectedPage: Int, onSelected: (Int) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = rounded(Color.WHITE, 22)
        elevation = dp(8).toFloat()
        pad(this, 10, 8, 10, 8)
        listOf("待办", "笔记").forEachIndexed { index, title ->
            addView(label(title, 15f, if (index == selectedPage) blue else muted, index == selectedPage).apply {
                gravity = Gravity.CENTER
                background = if (index == selectedPage) rounded(Color.rgb(238, 243, 255), 16) else null
                setOnClickListener { onSelected(index) }
            }, lp(0, -1, 1f).also { it.setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    private fun updateBottomNavigation(navigation: LinearLayout, selectedPage: Int) {
        for (index in 0 until navigation.childCount) {
            val item = navigation.getChildAt(index) as? TextView ?: continue
            val selected = index == selectedPage
            item.setTextColor(if (selected) blue else muted)
            item.typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            item.background = if (selected) rounded(Color.rgb(238, 243, 255), 16) else null
        }
    }

    private fun buildProjectsPage(expanded: Long? = null): View {
        val currentProjects = store.projects()
        selectedProjectIds.retainAll(currentProjects.map { it.id }.toSet())
        selectedTaskIds.retainAll(currentProjects.flatMap { store.tasks(it.id) }.map { it.id }.toSet())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(page, 34)
            elevation = dp(2).toFloat()
            clipToOutline = true
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(this, 24, 30, 20, 16) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(0, -2, 1f) }
        titles.addView(label(if (projectSelectionMode) "选择项目和待办" else "Idea Recorder", 30f, ink, true).apply { isSingleLine = true })
        if (projectSelectionMode) titles.addView(label("已选择 ${selectionCount()} 项 · 可混合选择", 13f, muted).apply { pad(this, 0, 3, 0, 0) })
        head.addView(titles)
        if (!projectSelectionMode) {
            head.addView(label("⚙", 25f, ink).apply { gravity = Gravity.CENTER; background = rounded(Color.WHITE, 12, 1); pad(this, 11, 7, 11, 7); contentDescription = "设置"; setOnClickListener { showSettings() } })
        } else {
            head.addView(label("取消", 15f, blue, true).apply { gravity = Gravity.CENTER; pad(this, 10, 10, 4, 10); setOnClickListener { exitProjectSelection() } })
        }
        root.addView(head)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad(this, 30, 0, 30, 100) }
        store.projects().forEachIndexed { index, project -> body.addView(projectCard(project, index, expanded == project.id)) }
        body.addView(label("＋ 添加项目", 14f, blue, true).apply {
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            pad(this, 8, 8, 8, 8)
            setOnClickListener { newProject() }
        }, lp().also { it.setMargins(0, dp(2), 0, dp(4)) })
        body.addView(label("☷   点击项目查看待办", 16f, muted).apply { gravity = Gravity.CENTER; pad(this, 12, 24, 12, 14) }, lp())
        projectScrollView = scroll(body).apply {
            setBackgroundColor(Color.TRANSPARENT)
            if (projectSelectionMode && projectScrollY > 0) post { scrollTo(0, projectScrollY) }
        }
        root.addView(projectScrollView, lp(-1, 0, 1f))
        if (projectSelectionMode) root.addView(selectionBar(selectionCount(), "项目/待办", { deleteSelectedSelection() }, { exportSelectedSelection() }), lp(-1, dp(76)))
        val frame = FrameLayout(this).apply { setBackgroundColor(page) }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1).also {
            it.setMargins(dp(16), dp(34), dp(16), dp(88))
        })
        return frame
    }

    private fun buildNotebookPage(): View {
        selectedNoteIds.retainAll(store.notesByState().map { it.id }.toSet())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(page, 34); elevation = dp(2).toFloat(); clipToOutline = true
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(this, 24, 30, 20, 16) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(0, -2, 1f) }
        titles.addView(label(if (notebookSelectionMode) "选择笔记" else "笔记本", 30f, ink, true))
        titles.addView(label(if (notebookSelectionMode) "已选择 ${selectedNoteIds.size} 项 · 长按拖动排序" else "记录灵感与片段", 13f, muted).apply { pad(this, 0, 4, 0, 0) })
        head.addView(titles)
        if (!notebookSelectionMode) {
            head.addView(label("＋", 28f, blue, true).apply {
                contentDescription = "创建新笔记"
                setOnClickListener { newBlankNote() }
                pad(this, 10, 6, 10, 6)
            })
        } else {
            head.addView(label("取消", 15f, blue, true).apply { gravity = Gravity.CENTER; pad(this, 10, 10, 4, 10); setOnClickListener { exitNotebookSelection() } })
        }
        root.addView(head)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad(this, 30, 0, 30, 100) }
        val notes = store.notesByState()
        if (notes.isEmpty()) {
            body.addView(label("还没有笔记\n左滑项目页进入这里，记录一个想法吧", 15f, muted).apply { gravity = Gravity.CENTER; pad(this, 20, 70, 20, 20) }, lp())
        } else notes.forEach { note ->
            val noteCard = card().apply { pad(this, 16, 14, 16, 14); background = selectionBackground(selectedNoteIds.contains(note.id)); elevation = 1f }
            noteCard.tag = note.id
            noteCard.addView(label(noteDisplayTitle(note), 16f, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp())
            noteCard.addView(label(markdownPreview(note.text), 14f, ink).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1.14f); pad(this, 0, 6, 0, 0) }, lp())
            noteCard.addView(label(formatDate(note.updatedAt), 12f, muted).apply { pad(this, 0, 10, 0, 0) }, lp())
            noteCard.setOnClickListener { if (notebookSelectionMode) toggleNoteSelection(note.id) else showNoteEditor(note) }
            fun startNoteDrag(): Boolean {
                if (!notebookSelectionMode) return false
                return noteCard.startDragAndDrop(android.content.ClipData.newPlainText("note", note.id.toString()), View.DragShadowBuilder(noteCard), note.id, 0)
            }
            noteCard.setOnLongClickListener { if (notebookSelectionMode) startNoteDrag() else { enterNotebookSelection(note.id); true } }
            noteCard.setOnDragListener { view, event ->
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> event.localState is Long
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> { view.scaleX = 0.98f; view.scaleY = 0.98f; true }
                    android.view.DragEvent.ACTION_DRAG_EXITED -> { view.scaleX = 1f; view.scaleY = 1f; true }
                    android.view.DragEvent.ACTION_DROP -> {
                        view.scaleX = 1f; view.scaleY = 1f
                        val fromId = event.localState as? Long
                        if (fromId != null && fromId != note.id) {
                            val ids = store.notesByState().map { it.id }.toMutableList()
                            val fromIndex = ids.indexOf(fromId); val toIndex = ids.indexOf(note.id)
                            if (fromIndex >= 0 && toIndex >= 0) { val moved = ids.removeAt(fromIndex); ids.add(toIndex, moved); store.reorderNotes(ids); showNotebook() }
                        }
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> { view.scaleX = 1f; view.scaleY = 1f; true }
                    else -> true
                }
            }
            body.addView(noteCard, lp().also { it.setMargins(0, 0, 0, dp(12)) })
        }
        body.addView(label("右滑返回项目", 13f, muted).apply { gravity = Gravity.CENTER; pad(this, 12, 22, 12, 14) }, lp())
        root.addView(scroll(body).apply { setBackgroundColor(Color.TRANSPARENT) }, lp(-1, 0, 1f))
        if (notebookSelectionMode) root.addView(selectionBar(selectedNoteIds.size, "笔记", { deleteSelectedNotes() }), lp(-1, dp(76)))
        val frame = FrameLayout(this).apply { setBackgroundColor(page) }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1).also { it.setMargins(dp(16), dp(34), dp(16), dp(88)) })
        return frame
    }

    private fun showNoteEditor(note: Note?, deleteWhenBlank: Boolean = false) {
        currentScreen = "note_editor"
        val root = screen("", true).apply { setBackgroundColor(Color.WHITE) }
        val top = root.getChildAt(0) as LinearLayout
        val initialTitle = if (deleteWhenBlank) "" else note?.let { noteDisplayTitle(it) }.orEmpty()
        val titleInput = EditText(this).apply {
            setText(initialTitle); textSize = 31f; setTextColor(ink); setSingleLine(false); maxLines = 2; typeface = Typeface.create("sans-serif", Typeface.BOLD)
            hint = "标题"; setHintTextColor(muted); background = null; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false; ellipsize = null
        }
        val input = EditText(this).apply {
            hint = "直接输入 Markdown…"; textSize = 16.5f; setTextColor(ink); setHintTextColor(muted)
            gravity = Gravity.TOP; minLines = 16; background = null; setSingleLine(false); setText(note?.text.orEmpty()); includeFontPadding = true; setLineSpacing(dp(5).toFloat(), 1.15f)
        }
        val autoCompile = Runnable { if (!compilingMarkdown && !isFinishing) applyMarkdownStyle(input) }
        input.post { applyMarkdownStyle(input) }
        input.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN) input.postDelayed(autoCompile, 40)
            false
        }
        val meta = label(noteMeta(note?.updatedAt ?: System.currentTimeMillis(), input.text.toString()), 12f, muted).apply { pad(this, 0, 0, 0, 18) }
        (top.getChildAt(0) as? TextView)?.setOnClickListener { saveNoteEditor(note, titleInput.text.toString(), input.text.toString(), deleteWhenBlank) }
        var restoringHistory = false
        val history = mutableListOf(input.text.toString())
        var historyIndex = 0
        fun restoreHistory(target: Int) {
            if (target !in history.indices) return
            historyIndex = target
            restoringHistory = true
            input.setText(history[historyIndex])
            input.setSelection(input.length())
            restoringHistory = false
        }
        top.addView(label("↶", 25f, blue, true).apply {
            gravity = Gravity.CENTER; contentDescription = "撤销"; pad(this, 6, 4, 6, 4)
            setOnClickListener { if (historyIndex > 0) restoreHistory(historyIndex - 1) }
        })
        top.addView(label("↷", 25f, blue, true).apply {
            gravity = Gravity.CENTER; contentDescription = "重做"; pad(this, 6, 4, 6, 4)
            setOnClickListener { if (historyIndex + 1 < history.size) restoreHistory(historyIndex + 1) }
        })
        top.addView(label("完成", 14f, blue, true).apply {
            gravity = Gravity.CENTER; pad(this, 12, 8, 12, 8)
            setOnClickListener { saveNoteEditor(note, titleInput.text.toString(), input.text.toString(), deleteWhenBlank) }
        })
        top.addView(label("编译", 14f, blue, true).apply {
            gravity = Gravity.CENTER; pad(this, 8, 8, 8, 8)
            setOnClickListener { compileMarkdown(input) }
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); pad(this, 22, 18, 22, 28) }
        titleInput.layoutParams = lp().apply { bottomMargin = dp(8) }
        body.addView(titleInput)
        body.addView(meta, lp())
        body.addView(input, lp())
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                meta.text = noteMeta(System.currentTimeMillis(), s?.toString().orEmpty())
                if (!restoringHistory && !compilingMarkdown) {
                    while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
                    val value = s?.toString().orEmpty()
                    if (history.lastOrNull() != value) { history.add(value); historyIndex = history.lastIndex }
                    input.removeCallbacks(autoCompile)
                    input.postDelayed(autoCompile, 140)
                }
            }
        })
        if (note != null) {
            body.addView(outlineButton("AI整理到项目") {
                val text = input.text.toString()
                if (text.isBlank()) Toast.makeText(this, "笔记内容为空", Toast.LENGTH_SHORT).show()
                else organize(note.id, text)
            }.apply { textSize = 13f; pad(this, 12, 7, 12, 7) }, lp().also { it.setMargins(0, dp(18), 0, 0) })
        }
        root.addView(scroll(body).apply { setBackgroundColor(Color.WHITE) }, lp(-1, 0, 1f))
        noteEditorBack = { saveNoteEditor(note, titleInput.text.toString(), input.text.toString(), deleteWhenBlank) }
        val frame = SwipeFrame(this, null, { noteEditorBack?.invoke() ?: showNotebook() }, edgeOnlyRight = true).apply { setBackgroundColor(Color.WHITE) }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        playEnterAnimation(frame)
    }

    private fun saveNoteEditor(note: Note?, title: String, text: String, deleteWhenBlank: Boolean = false) {
        if (text.isBlank() && title.isBlank() && deleteWhenBlank && note != null) {
            store.deleteNotes(listOf(note.id))
        } else if (text.isNotBlank() || title.isNotBlank()) {
            if (note == null) store.addNote(text, title = title) else store.updateNote(note.id, text, title)
        }
        showNotebook()
    }

    private fun newBlankNote() {
        val id = store.addNote("")
        store.notesByState().firstOrNull { it.id == id }?.let { showNoteEditor(it, deleteWhenBlank = true) } ?: showNotebook()
    }

    private fun noteDisplayTitle(note: Note): String = note.title.trim().lineSequence().firstOrNull().orEmpty().replace(Regex("^#{1,6}\\s*"), "").takeIf { it.isNotBlank() }
        ?: note.text.lineSequence().map { it.trim().replace(Regex("^#{1,6}\\s*"), "") }.firstOrNull { it.isNotBlank() }?.take(40)
        ?: "未命名笔记"

    private fun noteMeta(timestamp: Long, text: String): String {
        val date = Date(timestamp)
        val cal = Calendar.getInstance().apply { time = date }
        val hour = cal.get(Calendar.HOUR)
        val displayHour = if (hour == 0) 12 else hour
        val period = if (cal.get(Calendar.AM_PM) == Calendar.AM) "上午" else "晚上"
        val datePart = SimpleDateFormat("M月d日", Locale.CHINA).format(date)
        val count = text.count { !it.isWhitespace() }
        return "最后修改时间：$datePart $period${displayHour}:${String.format(Locale.CHINA, "%02d", cal.get(Calendar.MINUTE))} | ${count}字"
    }

    private fun applyMarkdownStyle(editor: EditText) {
        val editable = editor.text ?: return
        val selection = editor.selectionStart.coerceAtLeast(0)
        editable.getSpans(0, editable.length, CharacterStyle::class.java).forEach { editable.removeSpan(it) }
        val source = editable.toString()
        val sizes = floatArrayOf(1.58f, 1.4f, 1.27f, 1.15f, 1.06f, 1.0f)
        Regex("(?m)^\\s*(#{1,6})\\s*(.+)$").findAll(source).forEach { match ->
            val level = match.groupValues[1].length
            val markerEnd = match.range.first + match.groupValues[0].indexOf(match.groupValues[2])
            val start = markerEnd; val end = match.range.last + 1
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.first, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(RelativeSizeSpan(sizes[level - 1]), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(ink), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("\\*\\*(.+?)\\*\\*").findAll(source).forEach { match ->
            val start = match.range.first + 2; val end = match.range.last - 1
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.first, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), end + 1, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)").findAll(source).forEach { match ->
            val start = match.range.first + 1; val end = match.range.last
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.first, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), end, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("\\[([^]]+)]\\(([^)]+)\\)").findAll(source).forEach { match ->
            val labelStart = match.range.first + 1; val labelEnd = labelStart + match.groupValues[1].length
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.first, labelStart, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(blue), labelStart, labelEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(android.text.style.UnderlineSpan(), labelStart, labelEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), labelEnd, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("__([^_\\n]+)__").findAll(source).forEach { match ->
            val start = match.range.first + 2; val end = match.range.last - 1
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.first, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), end + 1, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("~~([^~\\n]+)~~").findAll(source).forEach { match ->
            val start = match.range.first + 2; val end = match.range.last - 1
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.first, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(android.text.style.StrikethroughSpan(), start, end + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), end + 1, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("\\[[^]\\n]*]").findAll(source).forEach { match ->
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.first, match.range.first + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.last, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("(?m)^\\s*[-*]\\s+\\[[ xX]\\]\\s+").findAll(source).forEach { match ->
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.first, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("(?m)^\\s*[-*]\\s+(?!\\[[ xX]\\]\\s+)").findAll(source).forEach { match ->
            editable.setSpan(ForegroundColorSpan(blue), match.range.first, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("`([^`]+)`").findAll(source).forEach { match ->
            editable.setSpan(ForegroundColorSpan(blue), match.range.first + 1, match.range.last, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.first, match.range.first + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), match.range.last, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        editor.setSelection(selection.coerceAtMost(editable.length))
    }

    private fun compileMarkdown(editor: EditText) {
        if (!compilingMarkdown) applyMarkdownStyle(editor)
    }

    private fun markdownPreview(source: String): String {
        var inCodeBlock = false
        val visibleLines = source.lines().mapNotNull { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCodeBlock = !inCodeBlock
                return@mapNotNull null
            }
            if (inCodeBlock || trimmed.isBlank()) return@mapNotNull if (trimmed.isBlank()) "" else null
            trimmed
        }
        return visibleLines.joinToString("\n") { line ->
            line.replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^[-*]\\s+\\[[ xX]\\]\\s*"), "☐ ")
                .replace(Regex("^[-*+]\\s+"), "• ")
                .replace(Regex("^\\d+[.)]\\s+"), "• ")
                .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "$1")
                .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
                .replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)"), "$1")
                .replace(Regex("`([^`]+)`"), "$1")
                .replace(Regex("__([^_]+)__"), "$1")
                .replace(Regex("~~([^~]+)~~"), "$1")
                .replace(Regex("\\[([^]\\n]*)]"), "$1")
        }
    }

    private fun selectionBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(if (selected) Color.rgb(240, 245, 255) else Color.WHITE)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(if (selected) 2 else 1), if (selected) blue else line)
    }

    private fun selectionCount(): Int = selectedProjectIds.size + selectedTaskIds.size

    private fun selectionBar(count: Int, itemName: String, onDelete: () -> Unit, onExport: (() -> Unit)? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        pad(this, 22, 10, 22, 12)
        addView(label("已选择 $count 个$itemName", 14f, muted, true), lp(0, -2, 1f))
        if (onExport != null) addView(label("导出", 15f, blue, true).apply {
            gravity = Gravity.CENTER
            background = blueOutline(12)
            pad(this, 12, 10, 12, 10)
            setOnClickListener { onExport() }
        }, lp(dp(76), dp(46)).also { it.setMargins(0, 0, dp(8), 0) })
        addView(label("删除", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(225, 74, 74), 12)
            pad(this, 20, 10, 20, 10)
            setOnClickListener { onDelete() }
        }, lp(dp(88), dp(46)))
    }

    private fun enterProjectSelection(id: Long) {
        rememberProjectScroll()
        projectSelectionMode = true
        selectedProjectIds.add(id)
        expandedProjectId = id
        showProjects(id)
    }

    private fun toggleProjectSelection(id: Long) {
        if (!projectSelectionMode) return
        rememberProjectScroll()
        if (!selectedProjectIds.add(id)) selectedProjectIds.remove(id)
        if (selectionCount() == 0) projectSelectionMode = false
        showProjects()
    }

    private fun exitProjectSelection() {
        rememberProjectScroll()
        projectSelectionMode = false
        selectedProjectIds.clear()
        selectedTaskIds.clear()
        showProjects()
    }

    private fun toggleTaskSelection(id: Long) {
        if (!projectSelectionMode) return
        rememberProjectScroll()
        if (!selectedTaskIds.add(id)) selectedTaskIds.remove(id)
        if (selectionCount() == 0) projectSelectionMode = false
        showProjects(expandedProjectId)
    }

    private fun enterTaskSelection(projectId: Long, taskId: Long) {
        rememberProjectScroll()
        projectSelectionMode = true
        selectedTaskIds.add(taskId)
        expandedProjectId = projectId
        showProjects(projectId)
    }

    private fun rememberProjectScroll() {
        projectScrollY = projectScrollView?.scrollY ?: projectScrollY
    }

    private fun deleteSelectedSelection() {
        if (selectionCount() == 0) return
        val projectCount = selectedProjectIds.size
        val taskCount = selectedTaskIds.size
        val summary = buildString {
            if (projectCount > 0) append("项目 $projectCount 个")
            if (projectCount > 0 && taskCount > 0) append("，")
            if (taskCount > 0) append("待办 $taskCount 个")
        }
        AlertDialog.Builder(this).setTitle("删除所选内容？")
            .setMessage("将删除 $summary，且无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                store.deleteTasks(selectedTaskIds.toList())
                store.deleteProjects(selectedProjectIds.toList())
                projectSelectionMode = false
                selectedProjectIds.clear()
                selectedTaskIds.clear()
                showProjects()
            }.show()
    }

    private fun enterNotebookSelection(id: Long) {
        notebookSelectionMode = true
        selectedNoteIds.add(id)
        showNotebook()
    }

    private fun toggleNoteSelection(id: Long) {
        if (!notebookSelectionMode) return
        if (!selectedNoteIds.add(id)) selectedNoteIds.remove(id)
        if (selectedNoteIds.isEmpty()) notebookSelectionMode = false
        showNotebook()
    }

    private fun exitNotebookSelection() {
        notebookSelectionMode = false
        selectedNoteIds.clear()
        showNotebook()
    }

    private fun deleteSelectedNotes() {
        if (selectedNoteIds.isEmpty()) return
        val count = selectedNoteIds.size
        AlertDialog.Builder(this).setTitle("删除笔记？")
            .setMessage("将删除这 $count 条笔记，且无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                store.deleteNotes(selectedNoteIds.toList())
                notebookSelectionMode = false
                selectedNoteIds.clear()
                showNotebook()
            }.show()
    }

    private fun exportSelectedSelection() {
        if (selectionCount() == 0) return
        val payload = JSONObject(store.exportSelectionJson(selectedProjectIds, selectedTaskIds)).toString(2)
        val vibePrompt = store.exportSelectionVibePrompt(selectedProjectIds, selectedTaskIds)
        AlertDialog.Builder(this)
            .setTitle("导出所选内容")
            .setItems(arrayOf("复制到剪贴板", "复制 Vibe Coding 指令", "分享文件", "保存到本地")) { dialog, which ->
                when (which) {
                    0 -> copyToClipboard("Idea Recorder 导出数据", payload)
                    1 -> copyToClipboard("Idea Recorder Vibe Coding 指令", vibePrompt)
                    2 -> shareExportFile(payload)
                    3 -> saveExportFile(payload)
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(this, "Vibe Coding 指令已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun projectCard(project: Project, index: Int, expanded: Boolean): View {
        val selected = selectedProjectIds.contains(project.id)
        val card = card().apply {
            pad(this, 13, 10, 13, if (expanded) 8 else 10)
            background = selectionBackground(selected)
            // A very light, uniform shadow keeps all four edges visually balanced.
            elevation = 1f
            minimumHeight = dp(92)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; minimumHeight = dp(76) }
        header.addView(dot(projectColor(index)).apply { layoutParams = lp(dp(14), dp(14)).also { it.setMargins(0, dp(4), 0, 0) } })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(0, -2, 1f); pad(this, 12, 0, 8, 0) }
        copy.addView(label(project.name, 17f, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        copy.addView(label(project.summary.ifBlank { "暂无项目描述" }, 12f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; pad(this, 0, 3, 0, 0) })
        val projectTasks = store.tasks(project.id).map { task ->
            if (task.status == "doing" && isTaskOverdue(task)) {
                store.updateTaskStatus(task.id, "todo")
                store.task(task.id) ?: task
            } else task
        }
        val taskSummary = "${projectTasks.count { it.status == "todo" }} 条未完成 | ${projectTasks.count { it.status == "doing" }} 条进行中"
        copy.addView(label(taskSummary, 12f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; pad(this, 0, 4, 0, 0) })
        header.addView(copy)
        header.addView(label("›", 30f, muted).apply {
            gravity = Gravity.CENTER
            rotation = if (expanded) 90f else 0f
            layoutParams = lp(dp(32), dp(44)).also { it.setMargins(0, dp(2), 0, 0) }
            setOnClickListener { showProjects(if (expanded) null else project.id) }
        })
        fun startProjectDrag(): Boolean {
            if (!projectSelectionMode) return false
            return card.startDragAndDrop(android.content.ClipData.newPlainText("project", project.id.toString()), View.DragShadowBuilder(card), DragPayload("project", project.id), 0)
        }
        header.setOnClickListener { if (projectSelectionMode) toggleProjectSelection(project.id) else showProjects(if (expanded) null else project.id) }
        header.setOnLongClickListener { if (projectSelectionMode) startProjectDrag() else { enterProjectSelection(project.id); true } }
        card.setOnLongClickListener { if (projectSelectionMode) startProjectDrag() else { enterProjectSelection(project.id); true } }
        card.setOnDragListener { view, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> {
                    val payload = event.localState as? DragPayload
                    payload?.type == "project" || payload?.type == "task"
                }
                android.view.DragEvent.ACTION_DRAG_ENTERED -> { view.scaleX = 0.98f; view.scaleY = 0.98f; true }
                android.view.DragEvent.ACTION_DRAG_EXITED -> { view.scaleX = 1f; view.scaleY = 1f; true }
                android.view.DragEvent.ACTION_DROP -> {
                    view.scaleX = 1f; view.scaleY = 1f
                    val payload = event.localState as? DragPayload
                    if (payload?.type == "project" && payload.id != project.id) {
                        val ids = store.projects().map { it.id }.toMutableList()
                        val fromIndex = ids.indexOf(payload.id); val toIndex = ids.indexOf(project.id)
                        if (fromIndex >= 0 && toIndex >= 0) { val moved = ids.removeAt(fromIndex); ids.add(toIndex, moved); store.reorderProjects(ids); showProjects(expandedProjectId) }
                    } else if (payload?.type == "task" && store.task(payload.id)?.projectId != project.id) {
                        store.moveTask(payload.id, project.id)
                        showProjects(project.id)
                    }
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> { view.scaleX = 1f; view.scaleY = 1f; true }
                else -> true
            }
        }
        card.addView(header, lp())
        if (expanded) {
            card.addView(View(this).apply { setBackgroundColor(line) }, lp(-1, dp(1)))
            fun addTaskRow(task: Task) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = when {
                        selectedTaskIds.contains(task.id) -> selectionBackground(true)
                        task.status == "done" -> rounded(Color.rgb(246, 247, 249), 14, 1)
                        else -> rounded(Color.WHITE, 14, 1)
                    }
                    pad(this, 10, 7, 10, 7); minimumHeight = dp(46)
                }
                fun startTaskDrag(): Boolean {
                    if (!projectSelectionMode) return false
                    return row.startDragAndDrop(
                        android.content.ClipData.newPlainText("task", task.id.toString()),
                        View.DragShadowBuilder(row),
                        DragPayload("task", task.id),
                        0,
                    )
                }
                row.setOnClickListener { if (projectSelectionMode) toggleTaskSelection(task.id) else showTaskDetail(task) }
                row.setOnLongClickListener {
                    if (projectSelectionMode) {
                        startTaskDrag()
                    } else {
                        enterTaskSelection(project.id, task.id)
                        true
                    }
                }
                val completed = task.status == "done"
                val inProgress = task.status == "doing"
                val overdue = isTaskOverdue(task)
                val check = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2), when {
                            overdue -> Color.rgb(231, 76, 60)
                            inProgress -> green
                            else -> Color.rgb(160, 164, 172)
                        })
                    }
                    contentDescription = when {
                        completed -> "恢复未完成"
                        overdue -> "标记已完成"
                        inProgress -> "标记已完成"
                        else -> "标记完成中"
                    }
                    setOnClickListener {
                        if (projectSelectionMode) {
                            toggleTaskSelection(task.id)
                            return@setOnClickListener
                        }
                        val next = when {
                            completed -> "todo"
                            overdue -> "done"
                            inProgress -> "done"
                            else -> "doing"
                        }
                        store.updateTaskStatus(task.id, next)
                        // Refresh immediately so changing status never moves or bounces the tapped row.
                        showProjects(project.id)
                    }
                }
                val checkHit = FrameLayout(this).apply {
                    layoutParams = lp(dp(44), dp(44)).also { it.setMargins(-dp(8), 0, dp(4), 0) }
                    addView(check, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                    setOnClickListener { check.performClick() }
                }
                row.addView(checkHit)
                val taskCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(0, -2, 1f) }
                taskCopy.addView(label(task.title, 14f, if (completed) muted else ink).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    if (completed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                }, lp())
                val taskMeta = when {
                    completed -> task.completedAt?.let { SimpleDateFormat("yyyy.M.d", Locale.CHINA).format(Date(it)) + " 完成" }
                    task.dueDate.isNullOrBlank() -> null
                    overdue -> "${displayTaskDate(task.dueDate)} 逾期"
                    else -> "${displayTaskDate(task.dueDate)} 截止"
                }
                if (!taskMeta.isNullOrBlank()) taskCopy.addView(label(taskMeta, 12f, if (overdue) Color.rgb(231, 76, 60) else muted).apply { pad(this, 0, 2, 0, 0) }, lp())
                row.addView(taskCopy)
                card.addView(row, lp().also { it.setMargins(0, dp(6), 0, dp(2)) })
            }
            val activeTasks = projectTasks.filter { it.status != "done" }.sortedWith(::compareTasks)
            val completedTasks = projectTasks.filter { it.status == "done" }.sortedWith(::compareTasks)
            activeTasks.forEach(::addTaskRow)
            if (completedTasks.isNotEmpty()) {
                val latest = completedTasks.first()
                val expandedCompleted = expandedCompletedProjectIds.contains(project.id)
                val stack = FrameLayout(this).apply {
                    setPadding(0, dp(5), 0, 0)
                    setOnClickListener {
                        if (expandedCompleted) expandedCompletedProjectIds.remove(project.id) else expandedCompletedProjectIds.add(project.id)
                        showProjects(project.id)
                    }
                }
                stack.addView(View(this).apply { background = rounded(Color.rgb(246, 247, 249), 14, 1) }, FrameLayout.LayoutParams(-1, dp(48)).also { it.topMargin = dp(2) })
                stack.addView(View(this).apply { background = rounded(Color.rgb(246, 247, 249), 14, 1) }, FrameLayout.LayoutParams(-1, dp(48)).also { it.topMargin = dp(4) })
                val stackFront = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(Color.rgb(246, 247, 249), 14, 1); pad(this, 10, 7, 10, 7); minimumHeight = dp(48)
                }
                val restore = View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), Color.rgb(160, 164, 172)) }
                    contentDescription = "恢复最近完成的待办"
                    setOnClickListener { store.updateTaskStatus(latest.id, "todo"); showProjects(project.id) }
                }
                val restoreHit = FrameLayout(this).apply {
                    layoutParams = lp(dp(44), dp(44)).also { it.setMargins(-dp(8), 0, dp(4), 0) }
                    addView(restore, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                    setOnClickListener { restore.performClick() }
                }
                stackFront.addView(restoreHit)
                val stackText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(0, -2, 1f) }
                stackText.addView(label("已完成 ${completedTasks.size} 项", 14f, muted, true), lp())
                stackText.addView(label("最近完成：${latest.title}", 12f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; pad(this, 0, 2, 0, 0) }, lp())
                stackFront.addView(stackText)
                stack.addView(stackFront, FrameLayout.LayoutParams(-1, -2))
                card.addView(stack, lp().also { it.setMargins(0, dp(6), 0, dp(2)) })
                if (expandedCompleted) completedTasks.forEach(::addTaskRow)
            }
            card.addView(label("＋ 添加待办", 14f, blue, true).apply { gravity = Gravity.CENTER_VERTICAL; pad(this, 8, 10, 12, 4); setOnClickListener { addTask(project) } }, lp())
        }
        return card.apply {
            // Keep the inter-project gap close to one fifth of the compact card height.
            // Gap is approximately one eighth of the compact project card height.
            layoutParams = lp().also { it.setMargins(0, 0, 0, dp(8)) }
            alpha = 1f; translationY = if (expanded) dp(5).toFloat() else 0f
            post { animate().translationY(0f).setInterpolator(DecelerateInterpolator()).setDuration(220).start() }
        }
    }

    private fun newProject() {
        val box = formBox("添加项目")
        val name = formField("项目名称")
        val summary = formField("项目描述（可选）")
        box.addView(name); box.addView(summary, lp().also { it.setMargins(0, dp(12), 0, 0) })
        showFormDialog(box, "保存") {
            if (name.text.isNotBlank()) { store.addProject(name.text.toString(), summary.text.toString()); showProjects() }
        }
    }

    private fun addTask(project: Project) {
        val box = formBox("添加待办")
        box.addView(label(project.name, 13f, muted, true).apply { pad(this, 0, 0, 0, 12) })
        val input = formField("待办标题")
        box.addView(input)
        showFormDialog(box, "保存") {
            if (input.text.isNotBlank()) { store.addTask(project.id, input.text.toString(), ""); showProjects(project.id) }
        }
    }

    private fun formBox(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(Color.WHITE, 24); pad(this, 22, 22, 22, 16)
        addView(label(title, 21f, ink, true).apply { pad(this, 0, 0, 0, 18) })
    }

    private fun formField(hintText: String): EditText = EditText(this).apply {
        hint = hintText; textSize = 15f; setTextColor(ink); setHintTextColor(muted); isSingleLine = hintText != "项目描述（可选）"
        background = rounded(page, 12, 1); pad(this, 14, 11, 14, 11)
    }

    private fun showFormDialog(content: LinearLayout, positive: String, onPositive: () -> Unit) {
        val dialog = Dialog(this)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT; pad(this, 0, 16, 0, 0) }
        actions.addView(label("取消", 15f, muted, true).apply { gravity = Gravity.CENTER; pad(this, 14, 10, 14, 10); setOnClickListener { dialog.dismiss() } })
        actions.addView(label(positive, 15f, blue, true).apply { gravity = Gravity.CENTER; pad(this, 14, 10, 10, 10); setOnClickListener { onPositive(); dialog.dismiss() } })
        content.addView(actions)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun newTextNote() {
        val dialog = Dialog(this)
        val box = formBox("记录想法")
        val draft = prefs.getString(ideaDraftKey, "").orEmpty()
        if (draft.isNotBlank()) box.addView(label("已恢复上次未完成的想法", 12f, blue, true).apply { pad(this, 0, 0, 0, 8) })
        val input = formField("输入想法…").apply { minLines = 5; gravity = Gravity.TOP; isSingleLine = false; setText(draft); setSelection(length()) }
        val inputScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        // Keep the action buttons outside the scrolling area so long ideas never push them off-screen.
        box.addView(inputScroll, lp(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad(this, 0, 16, 0, 0) }
        var explicitAction = false
        fun clearDraft() { prefs.edit().remove(ideaDraftKey).apply() }
        fun saveAsNote() {
            val text = input.text.toString().trim()
            if (text.isBlank()) {
                Toast.makeText(this, "请先输入想法", Toast.LENGTH_SHORT).show()
                return
            }
            store.addNote(text)
            clearDraft()
            dialog.dismiss()
            showNotebook()
        }
        fun saveAndOrganize() { if (input.text.isNotBlank()) { val text = input.text.toString(); clearDraft(); dialog.dismiss(); organize(0L, text) } else dialog.dismiss() }
        fun saveAsPlanNote() { if (input.text.isNotBlank()) { val text = input.text.toString(); dialog.dismiss(); beginPlanNote(text) } else dialog.dismiss() }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { prefs.edit().putString(ideaDraftKey, s?.toString().orEmpty()).apply() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        actions.addView(primaryButton("保存到新笔记") { explicitAction = true; saveAsNote() }, lp().also { it.setMargins(0, 0, 0, dp(10)) })
        actions.addView(outlineButton("AI整理为待办") { explicitAction = true; saveAndOrganize() }, lp().also { it.setMargins(0, 0, 0, dp(4)) })
        actions.addView(outlineButton("AI整理为笔记") { explicitAction = true; saveAsPlanNote() }, lp().also { it.setMargins(0, 0, 0, dp(4)) })
        actions.addView(label("取消", 15f, muted, true).apply { gravity = Gravity.CENTER; pad(this, 12, 12, 12, 8); setOnClickListener { explicitAction = true; dialog.dismiss() } }, lp())
        box.addView(actions)
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { if (!explicitAction) prefs.edit().putString(ideaDraftKey, input.text.toString()).apply() }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), (resources.displayMetrics.heightPixels * 0.82f).toInt())
    }

    private fun beginPlanNote(sourceText: String) {
        val key = secretStore.read().orEmpty()
        if (key.isBlank()) { Toast.makeText(this, "请先在设置中填写 DeepSeek API Key", Toast.LENGTH_LONG).show(); return }
        val progress = ProgressDialog(this).apply { setMessage("正在思考需要确认的问题…"); setCancelable(false); show() }
        executor.execute {
            runCatching { DeepSeekClient(key, prefs.getString("model", "deepseek-v4-flash")!!).noteQuestions(sourceText) }
                .onSuccess { questions -> runOnUiThread { progress.dismiss(); showPlanQuestions(sourceText, questions) } }
                .onFailure { error -> runOnUiThread { progress.dismiss(); Toast.makeText(this, "问题生成失败：${error.message ?: "请稍后重试"}", Toast.LENGTH_LONG).show() } }
        }
    }

    private fun showPlanQuestions(sourceText: String, result: JSONObject) {
        val questions = result.optJSONArray("questions")
        if (questions == null || questions.length() !in 3..5) {
            Toast.makeText(this, "AI 返回的问题数量不符合要求，请重试", Toast.LENGTH_LONG).show()
            return
        }
        val dialog = Dialog(this)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(Color.WHITE, 24); pad(this, 22, 22, 22, 16) }
        outer.addView(label("完善这份计划", 21f, ink, true), lp())
        outer.addView(label("回答几个问题，AI 会据此生成更贴合你的 Markdown 笔记。", 13f, muted).apply { pad(this, 0, 6, 0, 14) }, lp())
        val questionBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val answerViews = mutableListOf<Pair<LinearLayout, EditText>>()
        for (index in 0 until questions.length()) {
            val item = questions.optJSONObject(index) ?: continue
            val question = item.optString("question").trim()
            val options = item.optJSONArray("options") ?: org.json.JSONArray()
            questionBody.addView(label("${index + 1}/${questions.length()}", 12f, blue, true).apply { pad(this, 0, 8, 0, 3) }, lp())
            questionBody.addView(label(question, 16f, ink, true).apply { pad(this, 0, 0, 0, 6) }, lp())
            questionBody.addView(label("可多选", 12f, muted).apply { pad(this, 0, 0, 0, 3) }, lp())
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (optionIndex in 0 until minOf(3, options.length())) {
                val option = options.optString(optionIndex).trim()
                if (option.isBlank()) continue
                group.addView(CheckBox(this).apply { text = option; textSize = 14f; setTextColor(ink); buttonTintList = ColorStateList.valueOf(blue); pad(this, 0, 3, 0, 3) }, lp())
            }
            questionBody.addView(group, lp())
            val custom = formField("自定义回答（可选）").apply { textSize = 14f; isSingleLine = false; minLines = 1; maxLines = 3; pad(this, 12, 8, 12, 8) }
            questionBody.addView(custom, lp().also { it.setMargins(0, dp(2), 0, dp(4)) })
            answerViews.add(group to custom)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(questionBody); setBackgroundColor(Color.TRANSPARENT) }
        outer.addView(scroll, lp(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT; pad(this, 0, 14, 0, 0) }
        actions.addView(label("取消", 15f, muted, true).apply { gravity = Gravity.CENTER; pad(this, 14, 10, 14, 10); setOnClickListener { dialog.dismiss() } })
        actions.addView(label("生成笔记", 15f, blue, true).apply {
            gravity = Gravity.CENTER; pad(this, 14, 10, 8, 10)
            setOnClickListener {
                val answers = org.json.JSONArray()
                answerViews.forEachIndexed { answerIndex, (group, custom) ->
                    val selected = (0 until group.childCount).mapNotNull { group.getChildAt(it) as? CheckBox }
                        .filter { it.isChecked }
                        .joinToString("、") { it.text.toString() }
                    val customText = custom.text.toString().trim()
                    val answer = listOf(selected, customText).filter { it.isNotBlank() }.joinToString("；")
                    answers.put(JSONObject().put("question", questions.optJSONObject(answerIndex)?.optString("question").orEmpty()).put("answer", answer))
                }
                dialog.dismiss()
                generatePlanNote(sourceText, answers.toString())
            }
        })
        outer.addView(actions, lp())
        dialog.setContentView(outer)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), (resources.displayMetrics.heightPixels * 0.82f).toInt())
    }

    private fun generatePlanNote(sourceText: String, answers: String) {
        val key = secretStore.read().orEmpty()
        if (key.isBlank()) { Toast.makeText(this, "请先在设置中填写 DeepSeek API Key", Toast.LENGTH_LONG).show(); return }
        val progress = ProgressDialog(this).apply { setMessage("正在联网深度搜索并整理笔记…"); setCancelable(false); show() }
        executor.execute {
            runCatching { DeepSeekClient(key, prefs.getString("model", "deepseek-v4-flash")!!).generateMarkdownNote(sourceText, answers) }
                .onSuccess { result ->
                    runOnUiThread {
                        progress.dismiss()
                        val title = result.optString("title").trim().ifBlank { "想法计划" }
                        val markdown = result.optString("markdown").trim()
                        val lines = markdown.lines().toMutableList()
                        if (lines.firstOrNull()?.trim()?.matches(Regex("^#{1,6}\\s*${Regex.escape(title)}\\s*$", RegexOption.IGNORE_CASE)) == true) lines.removeAt(0)
                        val cleanedMarkdown = lines.joinToString("\n").trim()
                        if (cleanedMarkdown.isBlank()) Toast.makeText(this, "AI 没有生成有效笔记，请重试", Toast.LENGTH_LONG).show()
                        else { store.addNote(cleanedMarkdown, title = title); prefs.edit().remove(ideaDraftKey).apply(); Toast.makeText(this, "计划笔记已创建", Toast.LENGTH_SHORT).show(); showNotebook() }
                    }
                }
                .onFailure { error -> runOnUiThread { progress.dismiss(); Toast.makeText(this, "笔记生成失败：${error.message ?: "请稍后重试"}", Toast.LENGTH_LONG).show() } }
        }
    }

    private fun showTaskDetail(original: Task) {
        currentScreen = "task"
        val task = store.task(original.id) ?: original
        val projectName = store.projects().firstOrNull { it.id == task.projectId }?.name ?: "日常"
        // Deliberately leave the title area blank: this page is identified by the project name.
        val root = screen("", true)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad(this, 18, 10, 18, 28) }
        val card = card().apply { pad(this, 22, 20, 22, 20) }
        card.addView(label(projectName, 18f, muted, true).apply { pad(this, 0, 0, 0, 8) }, lp())
        val titleInput = EditText(this).apply {
            setText(task.title)
            textSize = 24f
            setTextColor(ink)
            setHintTextColor(muted)
            hint = "待办标题"
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = null
            gravity = Gravity.TOP
            setSingleLine(false)
            maxLines = 4
            ellipsize = null
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1.1f)
        }
        card.addView(titleInput, lp())
        card.addView(detailRow("生成时间", formatDate(task.createdAt)), lp())
        val due = label(task.dueDate ?: "未设置", 15f, if (task.dueDate == null) muted else ink)
        val dueRow = detailRow("截止时间", "")
        dueRow.addView(due, lp(0, -2, 1f))
        dueRow.setOnClickListener {
            pickDate(due) { selected -> store.updateTask(task.id, titleInput.text.toString(), task.description, task.priority, selected, task.status) }
        }
        card.addView(dueRow, lp())
        card.addView(label("整理描述", 15f, ink, true).apply { pad(this, 0, 18, 0, 7) }, lp())
        val organizedInput = detailEditor(task.description, "暂无整理描述")
        card.addView(organizedInput, lp())
        card.addView(label("原文", 15f, ink, true).apply { pad(this, 0, 18, 0, 7) }, lp())
        val originalInput = detailEditor(task.originalText, "暂无原文记录")
        card.addView(originalInput, lp())
        card.addView(label("备注", 15f, ink, true).apply { pad(this, 0, 18, 0, 7) }, lp())
        val remarkInput = detailEditor("", "暂无备注")
        card.addView(remarkInput, lp())
        card.addView(primaryButton("保存修改") {
            val title = titleInput.text.toString().trim()
            if (title.isBlank()) {
                Toast.makeText(this, "待办标题不能为空", Toast.LENGTH_SHORT).show()
            } else {
                store.updateTaskTitle(task.id, title)
                store.updateTaskContent(task.id, originalInput.text.toString(), organizedInput.text.toString())
                showProjects(task.projectId)
            }
        }.apply { pad(this, 12, 11, 12, 11); textSize = 16f }, lp().also { it.setMargins(0, dp(20), 0, 0) })
        body.addView(card, lp())
        root.addView(scroll(body), lp(-1, 0, 1f))
        setContentView(root)
    }

    private fun detailRow(left: String, right: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(58); pad(this, 0, 12, 0, 10); setBackgroundColor(Color.TRANSPARENT)
        // Keep field labels on one line on narrow phones (especially “截止时间”).
        addView(label(left, 16f, ink, true).apply {
            isSingleLine = true
            includeFontPadding = false
        }, lp(0, -2, 0.42f))
        if (right.isNotEmpty()) addView(label(right, 14f, if (right == "未设置") muted else ink).apply {
                gravity = Gravity.RIGHT
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }, lp(0, -2, 0.58f))
    }

    private fun detailEditor(value: String, hintText: String): EditText = EditText(this).apply {
        setText(value); hint = hintText; textSize = 15f; setTextColor(ink); setHintTextColor(muted); gravity = Gravity.TOP
        minLines = 2; setSingleLine(false); background = rounded(page, 12, 1); pad(this, 12, 10, 12, 10)
    }

    private fun formatDate(value: Long): String = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(value))

    private fun pickDate(target: TextView, onSelected: ((String) -> Unit)? = null) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            val value = String.format(Locale.CHINA, "%04d年%02d月%02d日", year, month + 1, day)
            target.text = value
            onSelected?.invoke(value)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showSettings() {
        currentScreen = "settings"
        val root = screen("设置", true)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad(this, 18, 0, 18, 28) }
        body.addView(sectionTitle("AI 整理"), lp())
        val aiCard = card().apply { pad(this, 0, 0, 0, 0) }
        val modelRow = settingRow("模型", prefs.getString("model", "deepseek-v4-flash")!!.replace("deepseek-v4-", "DeepSeek V4 ").replace("flash", "Flash").replace("pro", "Pro"), true) { chooseModel() }
        aiCard.addView(modelRow, lp()); aiCard.addView(separator())
        aiCard.addView(switchRow("复杂任务自动使用 Pro", prefs.getBoolean("auto_pro", true), "auto_pro"), lp()); aiCard.addView(separator())
        aiCard.addView(switchRow("关闭深度思考", prefs.getBoolean("disable_thinking", true), "disable_thinking"), lp())
        body.addView(aiCard, lp())
        body.addView(sectionTitle("API 连接"), lp())
        val apiCard = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(this, 18, 18, 18, 18); setOnClickListener { editApiKey() } }
        val apiText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(0, -2, 1f) }
        apiText.addView(label("DeepSeek API Key", 16f, ink)); apiText.addView(label(maskedKey(), 14f, muted).apply { pad(this, 0, 6, 0, 0) }); apiCard.addView(apiText)
        apiCard.addView(outlineButton("测试连接") { testConnection() }, lp(dp(112), -2)); body.addView(apiCard, lp())
        body.addView(label("Key 仅保存于本机", 13f, muted).apply { pad(this, 18, 6, 18, 0) }, lp())
        body.addView(sectionTitle("云端同步"), lp())
        val cloudCard = card().apply { pad(this, 0, 0, 0, 0) }
        cloudCard.addView(settingRow("服务器地址", webDavAddressLabel(), true) { editWebDavServer() }, lp())
        cloudCard.addView(separator())
        cloudCard.addView(settingRow("账号", prefs.getString("webdav_username", "").orEmpty().ifBlank { "未设置" }, true) { editWebDavCredentials() }, lp())
        cloudCard.addView(separator())
        cloudCard.addView(switchRow("自动同步", prefs.getBoolean("webdav_auto_sync", false), "webdav_auto_sync") { webDavSync.schedule() }, lp())
        cloudCard.addView(separator())
        cloudCard.addView(outlineButton("测试连接并立即上传") { testWebDavConnection() }.apply { textSize = 14f; pad(this, 12, 9, 12, 9) }, lp().also { it.setMargins(dp(18), dp(14), dp(18), dp(14) ) })
        body.addView(cloudCard, lp())
        body.addView(label("自动同步开启后会双向合并项目、待办和笔记；不会覆盖本机数据。", 13f, muted).apply { pad(this, 18, 6, 18, 0) }, lp())
        body.addView(sectionTitle("本地数据"), lp())
        val dataCard = card().apply { pad(this, 0, 0, 0, 0) }
        dataCard.addView(settingRow("导出数据", "", true) { exportData() }, lp()); dataCard.addView(separator())
        dataCard.addView(settingRow("导入数据", "", true) { importData() }, lp()); body.addView(dataCard, lp())
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            pad(this, 0, 24, 0, 0)
            addView(label("Idea Recorder 0.3.1", 13f, muted).apply { gravity = Gravity.CENTER })
            addView(label("Designed by Rick", 11f, muted).apply { gravity = Gravity.CENTER; pad(this, 0, 4, 0, 0) })
        }, lp())
        root.addView(scroll(body), lp(-1, 0, 1f)); setContentView(root)
    }

    private fun separator(): View = View(this).apply { setBackgroundColor(line); layoutParams = lp(-1, dp(1)) }

    private fun settingRow(title: String, value: String, chevron: Boolean, action: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(58); pad(this, 18, 0, 16, 0); setOnClickListener { action() }
        addView(label(title, 16f, ink), lp(0, -2, 1f)); if (value.isNotBlank()) addView(label(value, 15f, muted).apply { gravity = Gravity.RIGHT }, lp(0, -2, 1f)); if (chevron) addView(label("›", 27f, muted).apply { gravity = Gravity.CENTER }, lp(dp(24), -1))
    }

    private fun switchRow(title: String, checked: Boolean, key: String, onChanged: (() -> Unit)? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(58); pad(this, 18, 0, 16, 0)
        addView(label(title, 16f, ink), lp(0, -2, 1f)); val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()); val toggle = Switch(this@MainActivity).apply { isChecked = checked; thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.WHITE)); trackTintList = ColorStateList(states, intArrayOf(green, Color.rgb(214, 218, 227))); setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean(key, value).apply(); onChanged?.invoke() } }; addView(toggle, lp(dp(54), -2))
    }

    private fun chooseModel() { val current = prefs.getString("model", "deepseek-v4-flash"); val values = arrayOf("DeepSeek V4 Flash", "DeepSeek V4 Pro"); AlertDialog.Builder(this).setTitle("选择模型").setSingleChoiceItems(values, if (current == "deepseek-v4-pro") 1 else 0) { dialog, which -> prefs.edit().putString("model", if (which == 1) "deepseek-v4-pro" else "deepseek-v4-flash").apply(); dialog.dismiss(); showSettings() }.show() }

    private fun webDavAddressLabel(): String {
        val address = prefs.getString("webdav_address", "").orEmpty().trim()
        if (address.isBlank()) return "未设置"
        return "${prefs.getString("webdav_protocol", "https")?.uppercase(Locale.CHINA)}://$address/IdeaRecorder"
    }

    private fun editWebDavServer() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad(this, 4, 0, 4, 0) }
        val protocol = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("https://", "http://")) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    super.getView(position, convertView, parent).apply {
                        (this as? TextView)?.apply { textSize = 15f; isSingleLine = true; includeFontPadding = false }
                    }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    super.getDropDownView(position, convertView, parent).apply {
                        (this as? TextView)?.textSize = 15f
                    }
            }
            setSelection(if (prefs.getString("webdav_protocol", "https") == "http") 1 else 0)
        }
        val address = formField("域名:端口/文件夹路径").apply {
            setText(prefs.getString("webdav_address", "").orEmpty()
                .removePrefix("http://").removePrefix("https://"))
            textSize = 15f
            hint = "地址:端口/文件夹路径"
            isSingleLine = true
        }
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(protocol, lp(dp(108), -2))
            addView(address, lp(0, -2, 1f).also { it.setMargins(dp(4), 0, 0, 0) })
        }
        form.addView(addressRow, lp())
        AlertDialog.Builder(this).setTitle("WebDAV 服务器").setView(form)
            .setPositiveButton("保存") { _, _ ->
                val value = address.text.toString().trim()
                    .removePrefix("http://").removePrefix("https://")
                    .removeSuffix("/")
                if (value.isBlank()) prefs.edit().remove("webdav_address").apply() else prefs.edit()
                    .putString("webdav_address", value)
                    .putString("webdav_protocol", if (protocol.selectedItemPosition == 1) "http" else "https")
                    .apply()
                webDavSync.schedule()
                showSettings()
            }.setNegativeButton("取消", null).show()
    }

    private fun editWebDavCredentials() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad(this, 4, 0, 4, 0) }
        val user = formField("用户名").apply { setText(prefs.getString("webdav_username", "").orEmpty()); isSingleLine = true }
        val password = formField("密码").apply { setText(secretStore.read("webdav_password").orEmpty()); isSingleLine = true; inputType = 129 }
        form.addView(user, lp())
        form.addView(password, lp().also { it.setMargins(0, dp(12), 0, 0) })
        AlertDialog.Builder(this).setTitle("WebDAV 账号").setView(form)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit().putString("webdav_username", user.text.toString().trim()).apply()
                val value = password.text.toString()
                if (value.isBlank()) secretStore.clear("webdav_password") else secretStore.write("webdav_password", value)
                webDavSync.schedule()
                showSettings()
            }.setNegativeButton("取消", null).show()
    }

    private fun testWebDavConnection() {
        if (prefs.getString("webdav_address", "").orEmpty().isBlank()) {
            Toast.makeText(this, "请先填写 WebDAV 服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        val progress = ProgressDialog(this).apply { setMessage("正在连接并上传数据…"); setCancelable(false); show() }
        executor.execute {
            val result = webDavSync.testConnection()
            runOnUiThread {
                progress.dismiss()
                Toast.makeText(this, result.second, Toast.LENGTH_LONG).show()
                if (result.first) showSettings()
            }
        }
    }

    private fun chooseRetention() { val values = arrayOf("7 天", "30 天", "90 天", "永久保留"); val current = prefs.getString("retention", "30 天"); AlertDialog.Builder(this).setTitle("录音保留时间").setSingleChoiceItems(values, values.indexOf(current)) { dialog, which -> prefs.edit().putString("retention", values[which]).apply(); dialog.dismiss(); showSettings() }.show() }

    private fun maskedKey(): String { val key = secretStore.read().orEmpty(); return if (key.length > 6) "${key.take(3)}••••••••••••${key.takeLast(3)}" else "未设置" }

    private fun editApiKey() { val input = EditText(this).apply { hint = "sk-…"; inputType = 129; setText(secretStore.read().orEmpty()) }; AlertDialog.Builder(this).setTitle("DeepSeek API Key").setMessage("Key 仅保存于本机安全存储。点击保存后可直接测试连接。").setView(input).setPositiveButton("保存") { _, _ -> if (input.text.isNotBlank()) secretStore.write(input.text.toString().trim()) else secretStore.clear(); showSettings() }.setNegativeButton("取消", null).show() }

    private fun showOfflineModel() { AlertDialog.Builder(this).setTitle("离线语音模型").setMessage(if (transcriber.status() == "ready") "中文离线模型已安装，录音转写无需网络。" else "模型尚未准备好，将在第一次转写时从 App 内置资源安装。 ").setPositiveButton("知道了", null).show() }

    private fun exportData() {
        val payload = JSONObject(store.exportJson()).put("api_key", secretStore.read().orEmpty()).toString(2)
        showExportOptions(payload, "导出本地数据", "备份包含项目、待办、笔记和当前 API Key，请妥善保管导出的文件。")
    }

    private fun showExportOptions(payload: String, title: String, message: String = "可复制文本、分享文件，或保存到手机及其他支持文件的应用。") {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("复制到剪贴板") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Idea Recorder 导出数据", payload))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("分享文件") { _, _ -> shareExportFile(payload) }
            .setNegativeButton("保存到本地") { _, _ -> saveExportFile(payload) }
            .show()
    }

    private fun saveExportFile(payload: String) {
        pendingExportJson = payload
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "idea-recorder-backup-${System.currentTimeMillis()}.json")
        }, exportFileRequestCode)
    }

    private fun shareExportFile(payload: String) {
        runCatching {
            val file = File(cacheDir, "idea-recorder-backup-${System.currentTimeMillis()}.json")
            file.writeText(payload, Charsets.UTF_8)
            val uri = Uri.parse("content://$packageName.export/${file.name}")
            Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享备份文件")
        }.onSuccess { startActivity(it) }
            .onFailure { Toast.makeText(this, "分享失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show() }
    }

    private fun importData() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }, importFileRequestCode)
    }

    private fun testConnection() { val key = secretStore.read().orEmpty(); if (key.isBlank()) { editApiKey(); return }; val progress = ProgressDialog(this).apply { setMessage("测试连接中…"); setCancelable(false); show() }; executor.execute { runCatching { DeepSeekClient(key, prefs.getString("model", "deepseek-v4-flash")!!).testConnection() }.onSuccess { result -> runOnUiThread { progress.dismiss(); Toast.makeText(this, if (result.first in 200..299) "DeepSeek 连接成功" else "连接返回 HTTP ${result.first}", Toast.LENGTH_LONG).show() } }.onFailure { runOnUiThread { progress.dismiss(); Toast.makeText(this, "连接失败：${it.message}", Toast.LENGTH_LONG).show() } } } }

    private fun organize(noteId: Long, text: String) { val key = secretStore.read().orEmpty(); if (key.isBlank()) { Toast.makeText(this, "请在设置中填写 API Key 后再整理", Toast.LENGTH_LONG).show(); return }; val progress = ProgressDialog(this).apply { setMessage("正在整理想法…"); setCancelable(false); show() }; executor.execute { runCatching { DeepSeekClient(key, prefs.getString("model", "deepseek-v4-flash")!!).organize(text, store.projects(), preferMarkdownItems = noteId > 0) }.onSuccess { raw -> runOnUiThread { progress.dismiss(); showReview(raw, text, compactTasks = noteId > 0) } }.onFailure { runOnUiThread { progress.dismiss(); Toast.makeText(this, "整理失败，可稍后重试", Toast.LENGTH_LONG).show() } } } }

    private data class ReviewChoice(val spinner: Spinner, val newName: EditText, val newSummary: EditText)

    private fun showReview(raw: JSONObject, sourceText: String = "", compactTasks: Boolean = false) {
        val groups = raw.optJSONArray("groups") ?: org.json.JSONArray()
        val projects = store.projects()
        if (groups.length() == 0) { Toast.makeText(this, "AI 没有识别出可整理的内容", Toast.LENGTH_LONG).show(); return }
        val dialog = Dialog(this)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(Color.WHITE, 24); pad(this, 22, 22, 22, 16) }
        outer.addView(label("确认整理结果", 21f, ink, true), lp())
        outer.addView(label(raw.optString("summary", "请确认每组内容要放入哪个项目"), 13f, muted).apply { pad(this, 0, 6, 0, 14) }, lp())
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val choices = mutableListOf<ReviewChoice>()
        val expandedTaskKeys = mutableSetOf<String>()
        val projectNames = projects.map { it.name } + "＋ 新建项目"
        for (index in 0 until groups.length()) {
            val group = groups.optJSONObject(index) ?: continue
            val requestedId = if (group.has("project_id") && !group.isNull("project_id")) group.optLong("project_id", -1L) else -1L
            val existingIndex = projects.indexOfFirst { it.id == requestedId }
            val suggestedName = group.optString("suggested_project_name").trim().ifBlank { group.optString("project_name").trim() }
            body.addView(View(this).apply { setBackgroundColor(line) }, lp(-1, dp(1)).also { it.setMargins(0, if (index == 0) 0 else dp(14), 0, dp(10)) })
            val projectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            projectRow.addView(label("项目${index + 1}", 18f, ink, true), lp(dp(76), -2))
            val reason = group.optString("reason").trim()
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, projectNames)
                setSelection(if (existingIndex >= 0) existingIndex else projects.size)
            }
            projectRow.addView(spinner, lp(0, -2, 0.72f))
            body.addView(projectRow, lp())
            val newName = formField("新项目名称").apply { setText(suggestedName); textSize = 14f }
            val newSummary = formField("新项目描述（可选）").apply { setText(group.optString("suggested_project_summary").trim()); textSize = 14f; isSingleLine = false }
            body.addView(newName, lp().also { it.setMargins(0, dp(6), 0, 0) })
            // The confirmation page only exposes the project name; keep the AI summary for storage.
            newSummary.visibility = View.GONE
            val instruction = group.optString("instruction").trim()
            val explanationSource = instruction.ifBlank { reason }
            val aiExplanation = explanationSource.replace(Regex("\\s+"), " ").trim().let { value ->
                if (value.isBlank()) "" else {
                    val end = Regex("[。！？.!?]").find(value)?.range?.last
                    (if (end != null) value.substring(0, end + 1) else "$value。")
                }
            }
            if (aiExplanation.isNotBlank()) body.addView(label("AI说明：$aiExplanation", 13f, muted).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; pad(this, 0, 10, 0, 8) }, lp())
            val tasks = group.optJSONArray("tasks")
            if (tasks != null && tasks.length() > 0) {
                body.addView(label("待办详情", 15f, ink, true).apply { pad(this, 0, 4, 0, 5) }, lp())
                for (j in 0 until tasks.length()) {
                    val task = tasks.optJSONObject(j) ?: continue
                    val taskTitle = task.optString("title").trim().ifBlank { task.optString("idea").trim() }
                    val taskDetails = StringBuilder()
                    task.optString("due_date").trim().takeIf { it.isNotBlank() && it != "null" }?.let { taskDetails.append("截止时间：").append(it) }
                    task.optString("description").trim().takeIf { it.isNotBlank() }?.let { if (taskDetails.isNotEmpty()) taskDetails.append("\n"); taskDetails.append("整理描述：").append(it) }
                    task.optString("original_text").trim().takeIf { it.isNotBlank() }?.let { if (taskDetails.isNotEmpty()) taskDetails.append("\n"); taskDetails.append("原文依据：").append(it) }
                    val taskKey = "$index:$j"
                    if (compactTasks) {
                        body.addView(label("□ $taskTitle${if (taskDetails.isNotBlank()) "\n$taskDetails" else ""}", 13f, muted).apply {
                            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; isClickable = true
                            setOnClickListener {
                                if (!expandedTaskKeys.add(taskKey)) expandedTaskKeys.remove(taskKey)
                                maxLines = if (expandedTaskKeys.contains(taskKey)) Int.MAX_VALUE else 1
                                ellipsize = if (expandedTaskKeys.contains(taskKey)) null else TextUtils.TruncateAt.END
                            }
                            setLineSpacing(dp(2).toFloat(), 1.12f); pad(this, 0, 0, 0, 9)
                        }, lp())
                    } else {
                        val taskBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad(this, 0, 0, 0, 9) }
                        taskBlock.addView(label("□ $taskTitle", 13f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp())
                        if (taskDetails.isNotBlank()) taskBlock.addView(label(taskDetails.toString(), 13f, muted).apply { setLineSpacing(dp(2).toFloat(), 1.12f) }, lp())
                        body.addView(taskBlock, lp())
                    }
                }
            }
            fun updateNewProjectFields() { val visible = spinner.selectedItemPosition == projects.size; newName.visibility = if (visible) View.VISIBLE else View.GONE; newSummary.visibility = if (visible) View.VISIBLE else View.GONE }
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateNewProjectFields()
            }
            choices.add(ReviewChoice(spinner, newName, newSummary))
            updateNewProjectFields()
        }
        outer.addView(ScrollView(this).apply { isFillViewport = true; addView(body); setBackgroundColor(Color.TRANSPARENT) }, lp(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT; pad(this, 0, 14, 0, 0) }
        actions.addView(label("关闭", 15f, muted, true).apply { gravity = Gravity.CENTER; pad(this, 14, 10, 14, 10); setOnClickListener { dialog.dismiss() } })
        actions.addView(label("确认并保存", 15f, blue, true).apply {
            gravity = Gravity.CENTER; pad(this, 14, 10, 8, 10)
            setOnClickListener { val target = saveOrganizedTasks(groups, sourceText, choices); dialog.dismiss(); Toast.makeText(this@MainActivity, "已保存到项目", Toast.LENGTH_SHORT).show(); showProjects(target) }
        })
        outer.addView(actions, lp())
        dialog.setContentView(outer)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), (resources.displayMetrics.heightPixels * 0.82f).toInt())
    }

    /** Persist the reviewed model output after the user confirms each project's destination. */
    private fun saveOrganizedTasks(groups: org.json.JSONArray, sourceText: String, choices: List<ReviewChoice> = emptyList()): Long? {
        val projects = store.projects()
        var firstTarget: Long? = null
        for (i in 0 until groups.length()) {
            val group = groups.optJSONObject(i) ?: continue
            val choice = choices.getOrNull(i)
            val selectedIndex = choice?.spinner?.selectedItemPosition ?: -1
            val target = if (selectedIndex in projects.indices) projects[selectedIndex] else {
                val requestedId = if (group.has("project_id") && !group.isNull("project_id")) group.optLong("project_id", -1L) else -1L
                projects.firstOrNull { it.id == requestedId } ?: projects.firstOrNull { it.name == group.optString("project_name").trim() } ?: run {
                    val name = choice?.newName?.text?.toString()?.trim().orEmpty().ifBlank { group.optString("suggested_project_name").trim().ifBlank { "新项目" } }
                    val summary = choice?.newSummary?.text?.toString()?.trim().orEmpty().ifBlank { group.optString("suggested_project_summary").trim() }
                    val newId = store.addProject(name, summary)
                    Project(newId, name, summary)
                }
            }
            val tasks = group.optJSONArray("tasks")
            val ideas = group.optJSONArray("ideas")
            val drafts = mutableListOf<TaskDraft>()
            fun appendDraft(raw: Any?, ideaFormat: Boolean = false) {
                val item = raw as? JSONObject
                val title = if (item != null) {
                    val primary = if (ideaFormat) item.optString("idea").trim() else item.optString("title").trim()
                    primary.ifBlank { if (ideaFormat) item.optString("title").trim() else item.optString("idea").trim() }
                } else raw?.toString()?.trim().orEmpty()
                if (title.isBlank() || title == "null") return
                val description = item?.optString("description").orEmpty().trim()
                val original = item?.optString("original_text").orEmpty().trim().ifBlank { sourceText }
                val due = item?.optString("due_date").orEmpty().trim().takeIf { it.isNotBlank() && it != "null" }
                drafts.add(TaskDraft(title, description, original, due))
            }
            if (tasks != null && tasks.length() > 0) {
                for (j in 0 until tasks.length()) appendDraft(tasks.opt(j))
            } else if (ideas != null) {
                for (j in 0 until ideas.length()) appendDraft(ideas.opt(j), ideaFormat = true)
            }
            // Persist the complete list atomically. A malformed later model item no longer aborts earlier/later items.
            val saved = store.addTasks(target.id, drafts)
            if (saved.isNotEmpty() && firstTarget == null) firstTarget = target.id
        }
        return firstTarget
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return }
        runCatching { recorder.start() }.onSuccess { file -> AlertDialog.Builder(this).setTitle("正在录音").setMessage("完成后将在手机本地转写。 ").setNegativeButton("取消") { _, _ -> recorder.cancel() }.setPositiveButton("完成") { _, _ -> recorder.stop(); executor.execute { runCatching { val text = transcriber.transcribe(file.absolutePath); val id = store.addNote(text, "audio", file.absolutePath); runOnUiThread { if (text.isBlank()) Toast.makeText(this, "录音已保存，但没有识别到文字", Toast.LENGTH_LONG).show() else organize(id, text) } } } }.show() }.onFailure { Toast.makeText(this, "无法开始录音：${it.message}", Toast.LENGTH_LONG).show() }
    }
}
