package com.kai.videostitcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import androidx.documentfile.provider.DocumentFile
import android.app.RecoverableSecurityException
import android.provider.DocumentsContract
import android.provider.MediaStore

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoStitcher"
        private const val UI_PREFS = "ui"
        private const val KEY_NIGHT_MODE = "night_mode"
    }

    private class CardViews(
        val progressBar: ProgressBar,
        val statusText: TextView,
        val infoText: TextView,
        val fileDurationViews: List<TextView>,
        val fileStateViews: List<TextView>,
        /** 每行 (↑, ↓, ✕)。可用性必须按行位置单独算，不能"整卡统一置为可用" */
        val rowButtons: List<Triple<Button, Button, Button>>,
        val nameEditor: EditText,
        val deleteGroup: Button,
        val addVideos: TextView,
        val btnDeleteSources: Button,
        val group: Group
    ) {
        /** 上一次绘制的阶段：只在阶段变化时换胶囊配色，避免每帧重置 drawable */
        var lastStage: MergeStage? = null
        /**
         * 卡片内控件的可用性统一入口。
         * 旧实现 setUiEnabled 递归把 containerGroups 里所有子 View 置 true，
         * 覆盖了首行 ↑ / 末行 ↓ 的初始禁用状态；点一下就
         * Collections.swap(items, 0, -1) → IndexOutOfBoundsException。
         */
        fun applyEnabled(merging: Boolean, ctx: Context) {
            val editable = !merging
            nameEditor.isEnabled = editable
            deleteGroup.isEnabled = editable
            addVideos.isEnabled = editable
            val canDeleteSources = editable && group.mergedOk
            btnDeleteSources.isEnabled = canDeleteSources
            btnDeleteSources.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (canDeleteSources) R.color.danger else R.color.text_disabled
                )
            )
            rowButtons.forEachIndexed { i, (up, down, remove) ->
                up.isEnabled = editable && i > 0
                down.isEnabled = editable && i < group.items.size - 1
                remove.isEnabled = editable
            }
        }
    }

    // 分组与成品都在全局会话（MergeSession）里：页面销毁/重建（退后台、划任务、
    // 昼夜切换）只是换窗口看同一场拼接，拼接本体在全局协程 + 前台服务里继续
    private val groups get() = MergeSession.groups
    private val cardViews = HashMap<Group, CardViews>()
    private val outputs get() = MergeSession.outputs
    private var merging = false
    /** 分组卡片圆形"+"选中的目标组：非空时选择器返回的视频追加进该组，空则新建分组 */
    private var pendingAddTarget: Group? = null

    // ---- 删除源视频（拼接成功后可选）----
    private var deleteGroups: List<Group> = emptyList()
    private val deleteQueue = ArrayDeque<Uri>()
    private var deleteOk = 0
    private var deleteFail = 0
    private var deleteBatchCount = 0

    private val batchDeleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            // API 30+ 的一次系统确认覆盖整批媒体条目（进相册回收站，可恢复）
            if (res.resultCode == RESULT_OK) deleteOk += deleteBatchCount else deleteFail += deleteBatchCount
            finishDeletion()
        }
    private val fileDeleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            // API 29 的逐条授权流程
            if (res.resultCode == RESULT_OK) deleteOk++ else deleteFail++
            deleteQueue.removeFirstOrNull()
            processDeleteQueue()
        }

    private lateinit var containerGroups: LinearLayout
    private lateinit var scrollGroups: ScrollView
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var pbOverall: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnOpenOutput: Button
    private lateinit var btnNewGroup: Button
    private lateinit var btnAlbumPick: Button
    private lateinit var btnOutputSettings: Button
    private lateinit var btnNightMode: TextView

    // 「从文件夹导入」：系统文件夹选择器，所选文件夹（不含子文件夹）里的视频
    // 整体导入为一个新分组
    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importFromFolder(uri)
        }
    // 系统照片选择器（Android 13+ 标准组件）：选视频走它，与其它应用的体验一致。
    // 多选上限必须 ≤ getPickImagesMaxLimit()（通常 100），超了 launch 时直接抛异常
    private val systemPickerMaxItems =
        if (Build.VERSION.SDK_INT >= 33) MediaStore.getPickImagesMaxLimit() else 100
    private val pickVisualVideos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(systemPickerMaxItems)) { uris ->
            if (!uris.isNullOrEmpty()) addGroupFromPicker(uris)
        }
    // 老系统的系统相册多选（ACTION_PICK，调起厂商相册的选择界面）
    private val pickFromSystemGallery =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                val data = res.data ?: return@registerForActivityResult
                val uris = mutableListOf<Uri>()
                data.clipData?.let { cd ->
                    for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
                }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                if (uris.isNotEmpty()) addGroupFromPicker(uris)
            }
        }
    private val albumPick =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                @Suppress("DEPRECATION")
                val uris = res.data?.getParcelableArrayListExtra<Uri>(AlbumPickerActivity.EXTRA_URIS)
                if (uris.isNullOrEmpty()) return@registerForActivityResult
                addGroupFromPicker(uris)
            }
        }
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                // 不阻塞使用：选视频走系统文件选择器本身无需该权限
                Toast.makeText(
                    this,
                    "未授予视频读取权限也可以正常使用；授予后兼容性更好",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    // 通知权限（Android 13+）：拼接的前台服务通知需要它才可见；拒绝也不影响拼接本身
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** 首次使用时向系统正式申请视频读取权限 */
    private fun ensureMediaPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            runCatching { storagePermission.launch(perm) }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedNightMode()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        containerGroups = findViewById(R.id.containerGroups)
        scrollGroups = findViewById(R.id.scrollGroups)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvStatus)
        pbOverall = findViewById(R.id.pbOverall)
        btnStart = findViewById(R.id.btnStart)
        btnOpenOutput = findViewById(R.id.btnOpenOutput)
        btnNewGroup = findViewById(R.id.btnNewGroup)
        btnAlbumPick = findViewById(R.id.btnAlbumPick)
        btnOutputSettings = findViewById(R.id.btnOutputSettings)
        btnNightMode = findViewById(R.id.btnNightMode)

        btnNewGroup.setOnClickListener {
            if (!merging) pickTree.launch(null)
        }
        btnAlbumPick.setOnClickListener {
            // "从相册选"走系统照片选择器（与其它应用的体验一致）；
            // 极少数没有系统选择器的设备退回内置相册选择器兜底
            if (!merging) launchSystemVideoPicker()
        }
        btnStart.setOnClickListener { if (merging) stopMerging() else onStartClicked() }
        btnOpenOutput.setOnClickListener { openLastOutput() }
        btnOutputSettings.setOnClickListener { if (!merging) showOutputSettingsDialog() }
        refreshOutputSettingsLabel()

        // 标题栏的昼夜切换：显示当前模式的图标，点击切到另一种模式
        // （拼接中不响应：切换会重建页面，会打断正在进行的拼接）
        btnNightMode.apply {
            updateNightGlyph(this)
            setOnClickListener {
                if (merging) return@setOnClickListener
                val toNight = !isNightMode()
                getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_NIGHT_MODE, toNight).apply()
                AppCompatDelegate.setDefaultNightMode(
                    if (toNight) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }

        // 分组在全局会话里：只有进程重启后的第一次进入才从存储加载。
        // 拼接进行中重建页面时若重新加载，会换掉正在拼接的 Group 实例，
        // 进度和"已完成"标记就全部对不上了
        if (!MergeSession.loaded) {
            MergeSession.loaded = true
            groups.addAll(Store.load(this))
        }
        render()
        // 接上全局会话的状态推送：新页面/重建页面立即恢复进行中的拼接画面
        MergeSession.addListener(sessionListener)
        renderSessionUi()
        initBoxParser(applicationContext)
        // 上次进程被杀留下的中间件与"仍在写入"的相册条目：启动时清一次，
        // 否则缓存目录会越积越大、相册里还会留着 0 字节的视频。
        // 拼接进行中绝不能清：页面因配置变化重建时，这些正是正在用的分段与输出条目
        if (!MergeSession.merging) {
            lifecycleScope.launch(Dispatchers.IO) {
                cleanupStaleTempFiles(applicationContext)
                cleanupPendingOutputs(applicationContext)
            }
        }
        ensureMediaPermission()
        ensureNotificationPermission()
        // 旧版本保存的分组没有编码信息，启动时补探一次（已有编码的条目会跳过）
        groups.forEach { fillDurationsAsync(it) }
    }

    override fun onDestroy() {
        MergeSession.removeListener(sessionListener)
        super.onDestroy()
    }

    /** 全局拼接状态一变（进度/完成/停止），把它画到当前页面上。
     *  MergeSession 已保证监听者都在主线程回调，这里不必再 runOnUiThread 转一道 */
    private val sessionListener: () -> Unit = { renderSessionUi() }

    /** 把会话状态同步到界面：开始/结束的整体控件翻转 + 各分组状态胶囊 */
    private fun renderSessionUi() {
        if (merging != MergeSession.merging) {
            merging = MergeSession.merging
            setUiEnabled(!merging)
            btnStart.isEnabled = true
            updateMergeButton()
            pbOverall.isVisible = merging
            btnOpenOutput.isVisible = !merging && outputs.isNotEmpty()
            if (!merging) {
                tvStatus.text = MergeSession.finalText.ifEmpty { "准备就绪" }
                refreshDeleteSourceButtons()
            }
        }
        for ((group, state) in MergeSession.states) {
            applyProgress(group, state)
        }
        if (merging) {
            val overall = MergeSession.overall
            val pct = overall.percent
            // 总进度改为确定态：进度条按整轮加权百分比走，不再是无意义的来回滑动
            pbOverall.isIndeterminate = pct == null
            if (pct != null) pbOverall.progress = pct
            val cur = MergeSession.currentGroup?.let { MergeSession.states[it] }
            tvStatus.text = buildString {
                append(overall.text)
                if (pct != null) append(" · 整体 ").append(pct).append('%')
                if (cur != null && cur.stage != MergeStage.DONE) {
                    append(" · ").append(StatusText.title(cur))
                }
            }
        }
    }

    private fun isNightMode(): Boolean =
        AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES

    /** 应用持久化的昼夜模式；默认黑夜（历史版本的观感） */
    private fun applySavedNightMode() {
        val night = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_NIGHT_MODE, true)
        AppCompatDelegate.setDefaultNightMode(
            if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun updateNightGlyph(btn: TextView) {
        // ☼/☾ 用单色字形（U+2600 ☀ 会被部分系统渲染成彩色 emoji，破坏黑白风格）
        btn.text = if (isNightMode()) "☾" else "☼"
        btn.contentDescription = if (isNightMode()) "当前黑夜模式，点击切换到白天" else "当前白天模式，点击切换到黑夜"
    }

    override fun onPause() {
        super.onPause()
        Store.save(this, groups)
    }

    /**
     * "从相册选"走系统照片选择器，与其它应用的体验一致：
     * Android 13+ 用系统照片选择器（多选上限取系统限制），老系统调厂商相册的
     * 多选（ACTION_PICK + EXTRA_ALLOW_MULTIPLE）。极少数没有系统选择器的设备
     * 退回内置相册选择器兜底（保留 OriginOS 阉割版 SAF 场景的可用性）。
     * target 非空 = 分组卡片圆形"+"触发，选完的视频追加进该组。
     */
    private fun launchSystemVideoPicker(target: Group? = null) {
        pendingAddTarget = target
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                pickVisualVideos.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            } else {
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                pickFromSystemGallery.launch(intent)
            }
        } catch (t: Throwable) {
            runCatching { albumPick.launch(Intent(this, AlbumPickerActivity::class.java)) }
                .onFailure { toast("无法打开相册选择器") }
        }
    }

    private fun addGroupFromPicker(uris: List<Uri>) {
        val target = pendingAddTarget
        pendingAddTarget = null
        val fresh = uris.filter { u -> groups.none { g -> g.items.any { it.uri == u } } }
        if (fresh.isEmpty()) {
            toast("所选视频都已经在分组里了")
            return
        }
        if (fresh.size < uris.size) toast("已跳过 ${uris.size - fresh.size} 个重复视频")
        if (target != null && groups.contains(target)) {
            // 分组卡片右下角圆形"+"：往该组追加视频。内容变了，已拼接状态失效
            // （红色"删除原视频"退回灰色），重新拼接成功后才会再点亮
            fresh.forEach {
                runCatching {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            fresh.forEachIndexed { i, u ->
                target.items.add(VideoItem(u, queryDisplayName(this, u, "视频${i + 1}.mp4"), 0))
            }
            target.mergedOk = false
            render()
            fillDurationsAsync(target)
        } else {
            addNewGroup(fresh)
        }
    }

    private fun addNewGroup(uris: List<Uri>) {
        uris.forEach {
            runCatching {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val items = uris.mapIndexed { i, u ->
            VideoItem(u, queryDisplayName(this, u, "视频${i + 1}.mp4"), 0)
        }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        addNewGroupItems(items, null)
    }

    private fun addNewGroupItems(items: List<VideoItem>, name: String?) {
        val group = Group(name ?: "组${groups.size + 1}", items.toMutableList())
        groups.add(group)
        render()
        fillDurationsAsync(group)
    }

    /**
     * 「从文件夹导入」：列出所选文件夹（不含子文件夹）里的视频，按文件名自然
     * 排序（01、02、10）整体导入为一个新分组，分组名默认用文件夹名。
     * 目录授权做持久化：应用重启后分组里的视频仍然可读可拼。
     */
    private fun importFromFolder(treeUri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val items = runCatching {
                val root = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                    ?: error("无法访问所选文件夹")
                root.listFiles()
                    .filter {
                        it.isFile &&
                            (it.type?.startsWith("video/") == true || isVideoName(it.name ?: ""))
                    }
                    .map { VideoItem(it.uri, it.name ?: "视频.mp4", 0) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            }.getOrElse { t ->
                android.util.Log.e(TAG, "folder import failed", t)
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (items.isEmpty()) {
                    toast("这个文件夹里没有可导入的视频")
                    return@withContext
                }
                val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                val folderName = docId?.substringAfter(':')
                    ?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                addNewGroupItems(items, folderName)
                toast("已导入「${folderName ?: "所选文件夹"}」的 ${items.size} 个视频")
            }
        }
    }

    private fun fillDurationsAsync(group: Group) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 必须先快照：探测是耗时操作，期间用户可能删除/追加条目。直接遍历活列表
            // 会让迭代器抛 ConcurrentModificationException，而 launch 里没人接这个
            // 异常 → AndroidExceptionPreHandler 在主线程重抛 → 应用直接退出。
            // 快照里的条目即使已被移除，写回时长也只是写到孤立对象上，无害。
            val snapshot = group.items.toList()
            for (item in snapshot) {
                if (item.durationMs > 0 && item.codec.isNotEmpty()) continue
                // 一次 probeVideo 同时拿到时长与编码描述：旧实现先 probeDuration
                // 再 probeCodecSummary（内部又 probeVideo 一次），同一条素材开三遍
                val info = runCatching { probeVideo(this@MainActivity, item.uri) }.getOrNull()
                if (info != null) {
                    if (item.durationMs <= 0 && info.durationMs > 0) item.durationMs = info.durationMs
                    if (item.codec.isEmpty()) item.codec = probeCodecSummary(info)
                }
                if (item.durationMs <= 0) item.durationMs = probeDuration(this@MainActivity, item.uri)
            }
            withContext(Dispatchers.Main) {
                // 只更新受影响的文本，不整树 render()：render 会重建全部卡片，
                // 把正在输入的分组名光标顶回开头，还会把缩略图加载整个重排一遍
                val cv = cardViews[group] ?: return@withContext
                val total = group.items.sumOf { it.durationMs }
                cv.infoText.text =
                    "${group.items.size} 个视频" + if (total > 0) " · 总时长 ${formatDuration(total)}" else ""
                group.items.forEachIndexed { i, item ->
                    cv.fileDurationViews.getOrNull(i)?.text =
                        listOf(formatDuration(item.durationMs), item.codec)
                            .filter { it.isNotEmpty() }.joinToString(" · ")
                }
            }
        }
    }

    private fun render() {
        Store.save(this, groups)
        containerGroups.removeAllViews()
        cardViews.clear()
        tvEmpty.isVisible = groups.isEmpty()
        for (g in groups) containerGroups.addView(buildGroupCard(g))
    }

    private fun buildGroupCard(group: Group): View {
        val card = layoutInflater.inflate(R.layout.view_group, containerGroups, false)
        val etName = card.findViewById<EditText>(R.id.etGroupName)
        val tvInfo = card.findViewById<TextView>(R.id.tvGroupInfo)
        val btnDelete = card.findViewById<Button>(R.id.btnDeleteGroup)
        val llFiles = card.findViewById<LinearLayout>(R.id.llFiles)
        val pbGroup = card.findViewById<ProgressBar>(R.id.pbGroup)
        val tvStatusGroup = card.findViewById<TextView>(R.id.tvGroupStatus)
        val btnDeleteSources = card.findViewById<Button>(R.id.btnDeleteSources)

        // 分组名完全由数据驱动。EditText 默认参与 View 状态保存/恢复，而所有卡片的
        // 输入框共用同一个 id：昼夜切换/旋转屏幕等任何 Activity 重建时，多张卡的
        // 保存状态按 id 互相覆盖，恢复出的文本会把别的分组名写进这一组（实测把
        // 「旅行剪辑」覆盖成「组2」）。关掉后重建只走 setText(group.name)
        etName.isSaveEnabled = false
        etName.setText(group.name)
        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                group.name = s?.toString() ?: ""
            }
        })
        btnDelete.setOnClickListener {
            if (merging) return@setOnClickListener
            groups.remove(group)
            render()
        }

        // 删除原视频：只在该组最近一次拼接成功后可点（红色），否则灰色
        fun refreshDeleteSources() {
            val active = group.mergedOk && !merging
            btnDeleteSources.isEnabled = active
            btnDeleteSources.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (active) R.color.danger else R.color.text_disabled
                )
            )
        }
        refreshDeleteSources()
        btnDeleteSources.setOnClickListener {
            if (merging || !group.mergedOk) return@setOnClickListener
            startDeletion(listOf(group))
        }

        // 右下角圆形"+"：往这个分组补充视频（选择器多选，追加到列表末尾）
        card.findViewById<TextView>(R.id.btnAddVideos).setOnClickListener {
            if (merging) return@setOnClickListener
            launchSystemVideoPicker(target = group)
        }

        // 已拼接成功的组亮出完成态：这类组不会被重复拼接
        if (group.mergedOk) {
            tvStatusGroup.isVisible = true
            tvStatusGroup.text = "✓ 已完成拼接"
            tvStatusGroup.setBackgroundResource(R.drawable.pill_success)
            tvStatusGroup.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_accent_solid))
        }

        fun refreshInfo() {
            val total = group.items.sumOf { it.durationMs }
            tvInfo.text = "${group.items.size} 个视频" + if (total > 0) " · 总时长 ${formatDuration(total)}" else ""
        }
        refreshInfo()

        val thumbViews = HashMap<String, ImageView>()
        val fileDurationViews = mutableListOf<TextView>()
        val fileStateViews = mutableListOf<TextView>()
        val rowButtons = mutableListOf<Triple<Button, Button, Button>>()
        group.items.forEachIndexed { index, item ->
            val row = layoutInflater.inflate(R.layout.view_file, llFiles, false)
            val thumb = row.findViewById<ImageView>(R.id.ivThumb)
            thumb.tag = item.uri.toString()
            Thumbs.get(item.uri)?.let { thumb.setImageBitmap(it) }
            thumbViews[item.uri.toString()] = thumb
            row.findViewById<TextView>(R.id.tvFileName).text = "${index + 1}. ${item.name}"
            row.findViewById<TextView>(R.id.tvFileDuration).text =
                listOf(formatDuration(item.durationMs), item.codec).filter { it.isNotEmpty() }
                    .joinToString(" · ")
            fileDurationViews.add(row.findViewById(R.id.tvFileDuration))
            fileStateViews.add(row.findViewById(R.id.tvFileState))
            val btnRemove = row.findViewById<Button>(R.id.btnRemove)
            btnRemove.setOnClickListener {
                if (merging) return@setOnClickListener
                group.items.removeAt(index)
                group.mergedOk = false
                if (group.items.isEmpty()) groups.remove(group)
                render()
            }
            // 点行直接播放原视频（缩略图/文件名区域；↑↓✕ 按钮各自消费自己的点击）
            row.setOnClickListener {
                if (merging) return@setOnClickListener
                openOriginal(item.uri)
            }
            // ↑↓ 调整顺序（1.9.1 起恢复按钮排序，替代 1.7.0 的长按拖动手柄）
            val up = row.findViewById<Button>(R.id.btnUp)
            val down = row.findViewById<Button>(R.id.btnDown)
            rowButtons.add(Triple(up, down, btnRemove))
            up.isEnabled = index > 0
            down.isEnabled = index < group.items.size - 1
            up.setOnClickListener {
                // 边界判断是必需的第二道锁：按钮的 isEnabled 会在整卡刷新时被重算，
                // 少了这行，首行 ↑ 一旦被误启用就是 swap(items, 0, -1) 崩溃
                if (merging || index <= 0 || index >= group.items.size) return@setOnClickListener
                Collections.swap(group.items, index, index - 1)
                // 顺序变了：已拼接状态失效（红色删源按钮退回灰色）
                group.mergedOk = false
                render()
            }
            down.setOnClickListener {
                if (merging || index < 0 || index >= group.items.size - 1) return@setOnClickListener
                Collections.swap(group.items, index, index + 1)
                group.mergedOk = false
                render()
            }
            llFiles.addView(row)
        }

        // 异步加载缺失的封面缩略图，加载完成后仅刷新对应 ImageView
        lifecycleScope.launch(Dispatchers.IO) {
            // 快照后再遍历：用户在缩略图加载期间删/加视频时，直接遍历活列表会抛
            // ConcurrentModificationException，协程未捕获 → 应用崩溃
            for (item in group.items.toList()) {
                val key = item.uri.toString()
                if (Thumbs.get(key) != null) continue
                val bmp = Thumbs.load(applicationContext, item.uri) ?: continue
                withContext(Dispatchers.Main) {
                    thumbViews[key]?.takeIf { it.isAttachedToWindow && it.tag == key }
                        ?.setImageBitmap(bmp)
                }
            }
        }

        cardViews[group] = CardViews(
            pbGroup, tvStatusGroup, tvInfo, fileDurationViews, fileStateViews, rowButtons,
            etName, btnDelete, card.findViewById(R.id.btnAddVideos), btnDeleteSources, group
        )
        return card
    }

    private fun onStartClicked() {
        if (merging) return
        if (groups.all { it.items.isEmpty() }) {
            toast("请先添加视频")
            return
        }
        // 已拼接成功的分组本轮不会重复拼接；一组都不用拼就直接说明
        if (groups.none { it.items.isNotEmpty() && !it.mergedOk }) {
            toast("所有分组都已拼接完成，不会重复拼接\n往分组补充视频或调整顺序后可重新拼接")
            return
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        beginMerging()
    }

    private fun beginMerging() {
        if (MergeSession.merging) return
        // 只拼还没拼成功的分组：mergedOk 的组保留成品原样不动
        val targets = groups.filter { it.items.isNotEmpty() && !it.mergedOk }
        // 本轮设置快照：拼接期间即使用户改了输出设置，也不影响已经开始的一轮。
        // 旧实现里 partialTranscodeEligible 用旧设置判断、引擎内部又 reload 一次，
        // 中途改分辨率会让"直拷段（原尺寸）"和"转码段（缩放后）"混拼成一个文件
        val roundSettings = loadTranscodeSettings(this)
        MergeSession.begin(targets)
        MergeService.start(applicationContext)
        renderSessionUi()
        // 引擎链只拿 applicationContext：拼接可能比本页面活得久，持有 Activity
        // 会把已销毁的页面拖到拼接结束才释放
        val appCtx = applicationContext
        MergeSession.job = MergeSession.scope.launch {
            val succeeded = mutableListOf<Group>()
            try {
                // 多个分组按列表顺序逐组拼接：一次只跑一组，状态一目了然，
                // 单组也能吃满解码/转码资源，多组并行互相抢 CPU 反而更慢
                for ((idx, group) in targets.withIndex()) {
                    MergeSession.setCurrent(group)
                    try {
                        val out = mergeGroup(appCtx, group, roundSettings)
                        outputs.add(out)
                        succeeded.add(group)
                        // 顺序拼接完成一组点亮一组：立即标记成功并落盘，
                        // 进程被杀（崩溃/低内存）也不会把成功状态丢掉
                        withContext(NonCancellable) {
                            group.mergedOk = true
                            Store.save(appCtx, groups)
                        }
                    } catch (c: CancellationException) {
                        // 取消不是失败：向上传播，别把取消误标成"✗ 失败"
                        throw c
                    } catch (t: Throwable) {
                        publish(
                            group,
                            GroupProgress(
                                MergeStage.FAILED,
                                percent = 0,
                                note = "失败：${t.message ?: t.javaClass.simpleName}"
                            ),
                            force = true
                        )
                    }
                }
                MergeSession.finish(outputs.size, targets.size)
            } catch (c: CancellationException) {
                // 用户按了"停止拼接"：清点已完成的成品，
                // 未完成分组的半成品文件已在各引擎的取消链路里删掉
                withContext(NonCancellable) {
                    MergeSession.stopped(targets, succeeded)
                }
                throw c
            } finally {
                MergeService.stop(appCtx)
            }
        }
    }

    /** 输出设置摘要标签：原尺寸 · 自动 · 软编 / 720p · H.265 · 硬编 … */
    private fun refreshOutputSettingsLabel() {
        val s = loadTranscodeSettings(this)
        val res = when (s.shortEdge) { 1080 -> "1080p"; 720 -> "720p"; else -> "原尺寸" }
        val codec = when (s.codecMode) {
            "h265" -> "H.265"; "av1" -> "AV1"; "h264" -> "H.264"; else -> "自动跟随源"
        }
        val enc = if (s.encoderMode == "hw") "硬编" else "软编"
        btnOutputSettings.text = "输出设置：$res · $codec · $enc（仅转码组生效）"
    }

    /** 输出设置对话框：分辨率压缩 + 编码格式。只影响需要转码的分组 */
    private fun showOutputSettingsDialog() {
        val cur = loadTranscodeSettings(this)
        val resNames = arrayOf("保持原尺寸（默认）", "压缩到 1080p", "压缩到 720p（最小体积）")
        val resValues = intArrayOf(0, 1080, 720)
        // 本机 ffmpeg 构建只带 libaom-av1（无 libsvtav1）：AV1 能编，但慢得多。
        // 旧文案只写"体积最小"，用户选完才发现要等半小时；这里把真实代价写清楚
        val codecNames = arrayOf(
            "自动（跟随源视频编码，混合时按总时长最多者）",
            "H.264（推荐：兼容性最好、转码最快）",
            "H.265（体积约省 30-50%，耗时约为 H.264 的 3-4 倍）",
            "AV1（体积最小；本机为软件编码，耗时约为 H.264 的 15-40 倍）"
        )
        val codecValues = arrayOf("auto", "h264", "h265", "av1")
        val encNames = arrayOf(
            "质量优先（软件编码，全机型行为一致，默认）",
            "速度优先（硬件编码，约快 5-15 倍；画质按码率控制）"
        )
        val encValues = arrayOf("sw", "hw")
        val checkedRes = resValues.indexOf(cur.shortEdge).coerceAtLeast(0)
        val checkedCodec = codecValues.indexOf(cur.codecMode).coerceAtLeast(0)
        val checkedEnc = encValues.indexOf(cur.encoderMode).coerceAtLeast(0)
        var selRes = cur.shortEdge
        var selCodec = cur.codecMode
        var selEnc = cur.encoderMode

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        fun sectionLabel(text: String, topPad: Int = 0) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, topPad, 0, 0)
        }
        fun radioGroup(names: Array<String>, checkedIndex: Int, onPick: (Int) -> Unit) =
            android.widget.RadioGroup(this).apply {
                names.forEachIndexed { i, name ->
                    addView(android.widget.RadioButton(context).apply {
                        text = name
                        textSize = 14f
                        id = i
                    })
                }
                check(checkedIndex)
                setOnCheckedChangeListener { _, checkedId -> onPick(checkedId) }
            }

        container.addView(sectionLabel("输出分辨率（短边限制）"))
        container.addView(radioGroup(resNames, checkedRes) { i -> selRes = resValues[i] })
        container.addView(sectionLabel("输出编码", 24))
        container.addView(radioGroup(codecNames, checkedCodec) { i -> selCodec = codecValues[i] })
        container.addView(sectionLabel("编码引擎（决定转码速度）", 24))
        container.addView(radioGroup(encNames, checkedEnc) { i -> selEnc = encValues[i] })
        container.addView(TextView(this).apply {
            text = "仅对参数不一致、需要转码的分组生效；无损拼接的分组永远保持原画质不变。" +
                "自动模式下编码不统一的分组按总时长最多的编码输出（平票取 H.264），" +
                "且只转码需要改动的段，其余段原样拷贝。\n\n" +
                "硬件编码仅对 H.264/H.265 且短边 ≥1080 的素材生效，按码率控制画质；" +
                "设备不支持、编码失败或成品自检不通过时会自动退回软件编码。"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, 24, 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("输出设置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                saveTranscodeSettings(this, TranscodeSettings(selRes, selCodec, selEnc))
                refreshOutputSettingsLabel()
                toast("输出设置已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 用户点击"停止拼接"：先弹窗确认防误触，确认后取消整轮拼接，半成品由取消链路自动清理 */
    private fun stopMerging() {
        if (!MergeSession.merging) return
        AlertDialog.Builder(this)
            .setTitle("停止拼接")
            .setMessage("确定要停止拼接吗？\n\n未完成的半成品会被清理；已完成的成品保留在相册。")
            .setPositiveButton("停止拼接") { _, _ ->
                tvStatus.text = "正在停止并清理未完成的成品…"
                MergeSession.job?.cancel()
            }
            .setNegativeButton("继续拼接", null)
            .show()
    }

    private fun updateMergeButton() {
        if (merging) {
            btnStart.text = "停止拼接"
            btnStart.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger))
        } else {
            btnStart.text = "开始拼接"
            btnStart.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_primary))
        }
    }

    private fun refreshDeleteSourceButtons() {
        // 卡片内所有控件的可用性都走同一个入口：行位置相关的禁用状态不会被拉平
        for ((_, cv) in cardViews) cv.applyEnabled(merging, this)
    }

    /**
     * 照片选择器返回 content://media/picker/…/media/<id> 形态的 URI（读取没问题），
     * 但 createDeleteRequest 只认带具体 ID 的媒体条目 URI——把 picker URI 还原成
     * 条目 URI；普通相册 URI 原样返回。
     */
    private fun mediaItemUri(u: Uri): Uri {
        val segs = u.pathSegments
        if (segs.size > 2 && segs[0] == "picker") {
            val id = segs.last().toLongOrNull()
            if (id != null) {
                return ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri("external"), id)
            }
        }
        return u
    }

    private fun startDeletion(groupsDone: List<Group>) {
        deleteGroups = groupsDone
        deleteOk = 0
        deleteFail = 0
        val uris = groupsDone.flatMap { g -> g.items.map { it.uri } }.distinct()
        // 文件夹导入/文件选择器进来的是 SAF 文档，持有目录授权即可直接删；
        // 相册媒体条目属于相机/微信等其它应用，必须走系统删除确认
        val docs = uris.filter { it.authority != "media" }
        val media = uris.filter { it.authority == "media" }.map { mediaItemUri(it) }
        for (u in docs) {
            val ok = runCatching { DocumentsContract.deleteDocument(contentResolver, u) }.getOrDefault(false)
            if (ok) deleteOk++ else deleteFail++
        }
        when {
            media.isEmpty() -> finishDeletion()
            Build.VERSION.SDK_INT >= 30 -> {
                // URI 形态异常等极端情况：宁可放弃删除也别崩（分组保留，源视频无损）
                val pi = runCatching { MediaStore.createDeleteRequest(contentResolver, media) }.getOrNull()
                if (pi == null) {
                    deleteFail += media.size
                    finishDeletion()
                } else {
                    deleteBatchCount = media.size
                    batchDeleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }
            }
            else -> {
                deleteQueue.addAll(media)
                processDeleteQueue()
            }
        }
    }

    private fun processDeleteQueue() {
        while (deleteQueue.isNotEmpty()) {
            val u = deleteQueue.first()
            val res = runCatching { contentResolver.delete(u, null, null) }
            if (res.getOrDefault(0) > 0) {
                deleteOk++
                deleteQueue.removeFirstOrNull()
                continue
            }
            val ex = res.exceptionOrNull()
            // API 29 上删除非本应用创建的媒体需要逐条系统授权
            if (Build.VERSION.SDK_INT == 29 && ex is RecoverableSecurityException) {
                fileDeleteLauncher.launch(
                    IntentSenderRequest.Builder(ex.userAction.actionIntent.intentSender).build()
                )
                return
            }
            deleteFail++
            deleteQueue.removeFirstOrNull()
        }
        finishDeletion()
    }

    private fun finishDeletion() {
        if (deleteOk + deleteFail == 0) return
        if (deleteFail == 0) {
            // 全部删除成功才移除分组：部分失败时保留分组，用户还找得到没删掉的源
            groups.removeAll(deleteGroups.toSet())
            render()
        }
        toast(
            "已删除 $deleteOk 个源视频" +
                if (deleteFail > 0) "，$deleteFail 个未能删除，分组已保留" else "，对应分组已移除"
        )
    }

    private suspend fun mergeGroup(appCtx: Context, group: Group, settings: TranscodeSettings): Uri {
        publish(group, GroupProgress(MergeStage.READ_CHECK, percent = 0), force = true)
        // 授权失效/文件被移动的视频提前发现，给出可操作的提示而不是神秘报错
        val unreadable = group.items.count { item ->
            runCatching { appCtx.contentResolver.openFileDescriptor(item.uri, "r")!!.close() }.isFailure
        }
        if (unreadable > 0) {
            throw IllegalStateException("有 $unreadable 个视频无法读取（可能已被移动、删除或授权失效），请把它们从分组中删除后重新添加")
        }
        checkDiskSpace(appCtx, group.items)
        publish(group, GroupProgress(MergeStage.PROBE, percent = 3), force = true)
        // 逐项并行探测：几十条的分组串行要探几十秒，并行只花最长那一条的时间
        var infos = coroutineScope {
            group.items.map { item ->
                async(Dispatchers.IO) {
                    runCatching { probeVideo(appCtx, item.uri) }.getOrElse { t ->
                        android.util.Log.e(TAG, "probe failed: ${item.uri}", t)
                        TrackInfo(null, 0, 0, 0, 0f, null, 0, 0, false)
                    }
                }
            }.awaitAll()
        }
        // 探测顺带带回时长与编码描述：回写到条目，后面不必再为同一素材开一次 extractor
        infos.forEachIndexed { i, info ->
            val item = group.items.getOrNull(i) ?: return@forEachIndexed
            if (item.durationMs <= 0 && info.durationMs > 0) item.durationMs = info.durationMs
            if (item.codec.isEmpty()) item.codec = probeCodecSummary(info)
        }
        // 带外来轨的输入（Android 13+ 录屏的 mett 元数据轨、字幕/时间码轨）所有引擎
        // 都读不了：先 -c copy 无损剥离再进引擎链；个别剥离失败的原样保留不更糟
        var workItems = group.items
        val cleanedFiles = mutableListOf<File>()
        try {
            if (infos.any { it.extraTracks > 0 }) {
                val work = mutableListOf<VideoItem>()
                group.items.forEachIndexed { i, item ->
                    if (infos[i].extraTracks > 0) {
                        publish(
                            group,
                            GroupProgress(
                                MergeStage.STRIP,
                                SegmentProgress(i + 1, group.items.size, item.name, "", "", false, 0),
                                percent = ProgressMath.stagePercent(MergeStage.STRIP, i * 100 / group.items.size.coerceAtLeast(1))
                            ),
                            force = true
                        )
                        val tmp = File(appCtx.cacheDir, uniqueTempName("剥轨", ".mp4"))
                        if (stripForeignTracks(appCtx, item.uri, tmp)) {
                            cleanedFiles.add(tmp)
                            work.add(VideoItem(Uri.fromFile(tmp), item.name, item.durationMs))
                        } else {
                            runCatching { tmp.delete() }
                            work.add(item)
                        }
                    } else {
                        work.add(item)
                    }
                }
                publish(group, GroupProgress(MergeStage.STRIP, percent = 10), force = true)
                if (work != group.items.toList()) {
                    workItems = work
                    infos = infos.mapIndexed { i, info ->
                        if (workItems[i].uri != group.items[i].uri)
                            runCatching { probeVideo(appCtx, workItems[i].uri) }.getOrElse { info }
                        else info
                    }
                }
            }
            val first = infos.first()
            val paramsUniform = infos.all { it.matches(first) }
            // 导入时的时长探测可能失败过（存了 0）：拼前逐项补探——只补缺失项，
            // 否则总时长被低估、自检的截断阈值会被放松
            workItems.forEach { item ->
                if (item.durationMs <= 0) item.durationMs = probeDuration(appCtx, item.uri)
            }
            // 期望时长按视频轨累计（2.0.5：音画对齐后成品的轨时长都收敛到视频轨），
            // 视频轨未知的条目退回条目时长
            val expectedDurationMs = verifyExpectedDurationMs(workItems, infos)
            val mp4Family = workItems.all { isLosslessCapableName(it.name) }
            // mp4parser 容器级追加的硬约束（2.0.0 补齐，两条都是"宁可降一档也不硬拼"）：
            // 1) 只认 avc1/avcC 采样条目：hvc1 克隆会丢 hvcC、写空 stss（成品没画面），
            //    av01 解析直接抛异常；vp09/mp4v/dvh1 也没有可靠的克隆实现。
            //    用白名单，不再"排除 av01/hevc 就算安全"——那会把 VP9/MPEG-4/DV 漏进来
            // 2) 每段 csd（avcC 字节）必须真实存在：matchesVideoOnly 在 csd 缺失时
            //    会按"拿不到字节就不判不一致"放行，等于让 mp4parser 去硬拼两套 SPS/PPS
            val mp4parserSafe = mp4Family &&
                infos.all { it.videoMime == "video/avc" && it.csd != null }
            val probeFailed = infos.count { it.videoMime == null }
            return runMergeEngines(
                appCtx, group, workItems, infos, expectedDurationMs,
                paramsUniform, mp4parserSafe, probeFailed, settings
            )
        } finally {
            cleanedFiles.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * 引擎链：无损拼接 → 无损转封装 → 视频无损+音频重编 → 部分转码（全格式直拷）→ 全量转码。
     * 成功返回输出 Uri。中途取消（旋转屏幕/退页面/被杀）会删掉已创建的
     * 半截输出条目再向上传播取消——否则相册里会留下一个解不动的残缺 MP4。
     * 输出条目全程带 IS_PENDING 标记，写完才真正出现在相册里（见 createOutputUri）。
     */
    private suspend fun runMergeEngines(
        appCtx: Context,
        group: Group,
        items: List<VideoItem>,
        infos: List<TrackInfo>,
        expectedDurationMs: Long,
        paramsUniform: Boolean,
        mp4parserSafe: Boolean,
        probeFailed: Int,
        settings: TranscodeSettings
    ): Uri {
        var outUri = createOutputUri(appCtx, sanitizeFileName("${group.name}_合并.mp4"))
        // 2.0.5：音画偏差闸门——源文件音轨/视频轨时长不齐（相机停机余音、剪辑导出）
        // 时，纯容器级直拼（无损/转封装都不重编音频）按各轨自身时长累加时间轴，
        // 音画错位会逐段累积成口型不同步；偏差总量超阈值就跳过这两级，让后面的
        // "视频无损+音频重编"或分段引擎把每段音轨对齐到视频轨（audioAlignArgs）
        val audioDriftMs = audioDriftTotalMs(infos)
        fun resetOutput(): Uri {
            discardOutput(appCtx, outUri)
            return createOutputUri(appCtx, sanitizeFileName("${group.name}_合并.mp4"))
        }

        fun verifyOrFail(points: List<Long>) {
            publish(group, GroupProgress(MergeStage.VERIFY, percent = 97), force = true)
            verifyOutputUsable(appCtx, outUri, expectedDurationMs, points, expectVideo = true)
                ?.let { reason -> throw IllegalStateException("自检未通过：$reason") }
        }

        try {
            if (paramsUniform && mp4parserSafe && audioDriftMs <= AUDIO_DRIFT_LIMIT_MS) {
                publish(
                    group,
                    GroupProgress(
                        MergeStage.LOSSLESS,
                        percent = ProgressMath.stagePercent(MergeStage.LOSSLESS, 0),
                        note = "不重新编码，画质零损失"
                    ),
                    force = true
                )
                try {
                    appCtx.contentResolver.openFileDescriptor(outUri, "rw")!!.use { pfd ->
                        concatLossless(appCtx, items, pfd) { p ->
                            publish(
                                group,
                                GroupProgress(
                                    MergeStage.LOSSLESS,
                                    percent = ProgressMath.stagePercent(MergeStage.LOSSLESS, p)
                                )
                            )
                        }
                    }
                    // 成品必须自检通过：拼接"成功"不等于能播放，不过就降级下一档
                    verifyOrFail(segmentCheckPoints(items))
                    publishOutput(appCtx, outUri)
                    publish(
                        group,
                        GroupProgress(MergeStage.DONE, percent = 100, note = "完成（无损拼接，画质无损失）"),
                        force = true
                    )
                    return outUri
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "lossless failed, trying alternatives", t)
                    outUri = resetOutput()
                    publish(
                        group,
                        GroupProgress(
                            MergeStage.LOSSLESS, percent = 0,
                            note = "无损模式失败（${t.message ?: "格式不兼容"}），尝试其它方式…"
                        ),
                        force = true
                    )
                }
            }
            // 无损转封装只处理无旋转元数据的组：转封装走 media3 的序列管线，
            // 对旋转元数据的携带在真机上不可靠（带旋转的组请用 MP4/MOV 原件走
            // 上面的无损拼接，旋转矩阵在容器层原样保留）
            if (paramsUniform && isMp4MuxCompatible(infos) && infos.all { it.rotation == 0 } &&
                audioDriftMs <= AUDIO_DRIFT_LIMIT_MS
            ) {
                // 参数一致且编码能直封进 MP4（任意容器，含 MKV/WebM/TS/AV1）：
                // 直接拷贝压缩流换壳，不解码不重编码
                try {
                    transmuxConcat(appCtx, items, outUri) { p -> publish(group, p) }
                    verifyOrFail(segmentCheckPoints(items))
                    publishOutput(appCtx, outUri)
                    publish(
                        group,
                        GroupProgress(MergeStage.DONE, percent = 100, note = "完成（无损转封装，无重编码）"),
                        force = true
                    )
                    return outUri
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "transmux failed", t)
                    outUri = resetOutput()
                    publish(
                        group,
                        GroupProgress(
                            MergeStage.TRANSMUX, percent = 0,
                            note = "转封装失败（${t.message ?: "格式不兼容"}），尝试其它无损方式…"
                        ),
                        force = true
                    )
                }
            }
            // 第二级半（1.5.4 新增）：视频参数完全一致（含 csd 解码配置字节逐字节一致）
            // 但音频不一致的组——视频流一个字节不动、只重编音频（秒级）。录屏混相机、
            // 44.1k 混 48k 这类常见组从分钟级转码降到秒级，且视频零画质损失
            // 这里同样走 mp4parser，编码/csd 约束与上面一致（mp4parserSafe 已包含）
            if (mp4parserSafe && probeFailed == 0 &&
                infos.all { it.matchesVideoOnly(infos.first()) }
            ) {
                try {
                    videoCopyConcatAudio(appCtx, items, infos, outUri) { p -> publish(group, p) }
                    publishOutput(appCtx, outUri)
                    publish(
                        group,
                        GroupProgress(MergeStage.DONE, percent = 100, note = "完成（视频无损 + 音频重编，画质无损失）"),
                        force = true
                    )
                    return outUri
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "video-copy concat failed", t)
                    outUri = resetOutput()
                    publish(
                        group,
                        GroupProgress(
                            MergeStage.AUDIO_REDO, percent = 0,
                            note = "视频无损+音频重编失败（${t.message ?: "格式不兼容"}），尝试转码…"
                        ),
                        force = true
                    )
                }
            }
            // 第 2.9 级：只转码需要改动的段，其余原样拷贝。2.0 起放开到所有
            // 本机可解码的编码（H.264/H.265/AV1/VP9…），不再只认 H.264/H.265
            if (probeFailed == 0 && ffmpegAvailable()) {
                val durations = items.map { it.durationMs }
                val codecId = resolveOutputCodec(infos, durations, settings)
                if (partialTranscodeEligible(infos, durations, settings, codecId)) {
                    checkInternalDiskSpace(appCtx, items)
                    try {
                        val result = partialTranscodeConcat(appCtx, items, infos, outUri, settings) { p ->
                            publish(group, p)
                        }
                        publishOutput(appCtx, outUri)
                        val transcoded = items.size - result.copied
                        val codecName = VideoCodec.ofId(result.codec).display
                        val hw = if (result.hwEncoded > 0) "，其中 ${result.hwEncoded} 段硬件编码" else ""
                        val note = if (result.copied == items.size)
                            "完成（视频无损拷贝拼接，音频已统一 AAC）"
                        else "完成（转 $transcoded 段、直拷 ${result.copied} 段，输出 $codecName$hw）"
                        publish(
                            group,
                            GroupProgress(MergeStage.DONE, percent = 100, note = note),
                            force = true
                        )
                        return outUri
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        android.util.Log.e(TAG, "partial transcode failed", t)
                        outUri = resetOutput()
                        publish(
                            group,
                            GroupProgress(
                                MergeStage.SEG_TRANSCODE, percent = 0,
                                note = "快速拼接失败（${t.message ?: "格式不兼容"}），改用全量转码…"
                            ),
                            force = true
                        )
                    }
                }
            }
            // 第三级：参数不一致或前几级处理不了 → ffmpeg 逐段独立转码成统一参数再拼。
            // 拼前有 SPS/PPS 闸门、拼后有成品自检，最坏情况是中止，不产出坏文件
            if (probeFailed == 0) {
                if (!ffmpegAvailable()) {
                    discardOutput(appCtx, outUri)
                    throw IllegalStateException(
                        "这组视频参数不一致，而本机 CPU 架构不支持转码引擎（需要 64 位 ARM 或 x86_64 设备）。" +
                            "请把它们自行转码成参数一致的普通 MP4 后再导入"
                    )
                }
                // 真正落到转码级才检查内置分区（转码分段中间文件都在 cacheDir）：
                // 运行期从无损降级下来的组也躲不过这一关
                checkInternalDiskSpace(appCtx, items)
                try {
                    val result = transcodeConcat(appCtx, items, infos, outUri, settings) { p ->
                        publish(group, p)
                    }
                    publishOutput(appCtx, outUri)
                    val codecName = VideoCodec.ofId(result.codec).display
                    val hw = if (result.hwEncoded > 0) "，${result.hwEncoded} 段硬件编码" else ""
                    publish(
                        group,
                        GroupProgress(
                            MergeStage.DONE, percent = 100,
                            note = "完成（转码拼接，已统一为 $codecName+AAC$hw）"
                        ),
                        force = true
                    )
                    return outUri
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "transcode failed", t)
                    discardOutput(appCtx, outUri)
                    // 引擎级失败如实上报：failureDetail 带上错误码提示、内层原因
                    // 和组内检测（文档承诺过的格式），不再光秃秃一个异常消息
                    throw IllegalStateException(
                        "转码拼接失败：${failureDetail(t, infos)}", t
                    )
                }
            }
            // 只有探测不动的输入才会走到这里（如网页下载/聊天转发的非常规封装）
            discardOutput(appCtx, outUri)
            throw IllegalStateException(inconsistentAdvice(infos))
        } catch (c: CancellationException) {
            discardOutput(appCtx, outUri)
            throw c
        }
    }

    /** 引擎状态入口：统一走会话层，节流与主线程回调都在那里处理 */
    private fun publish(group: Group, p: GroupProgress, force: Boolean = false) {
        MergeSession.publish(group, p, force)
    }

    /** 把一组状态画到分组卡片上（由 renderSessionUi 在主线程调用） */
    private fun applyProgress(group: Group, p: GroupProgress) {
        val cv = cardViews[group] ?: return
        cv.statusText.isVisible = true
        val text = StatusText.oneLine(p)
        // 只在文案真的变了才 setText：转码期间每次 stats 回调都新建字符串，
        // 无条件 setText 会让 TextView 反复请求布局
        if (cv.statusText.text.toString() != text) cv.statusText.text = text
        if (cv.lastStage != p.stage) {
            cv.lastStage = p.stage
            val (bg, color) = when (p.stage) {
                MergeStage.DONE -> R.drawable.pill_success to R.color.on_accent_solid
                MergeStage.FAILED -> R.drawable.pill_error to R.color.danger
                MergeStage.STOPPED -> R.drawable.pill_neutral to R.color.text_disabled
                else -> R.drawable.pill_info to R.color.text_secondary
            }
            cv.statusText.setBackgroundResource(bg)
            cv.statusText.setTextColor(ContextCompat.getColor(this, color))
        }
        if (p.finished) {
            cv.progressBar.isVisible = false
        } else {
            cv.progressBar.isVisible = true
            cv.progressBar.isIndeterminate = false
            cv.progressBar.progress = p.percent.coerceIn(0, 100)
        }
        applyRowStates(cv, p)
    }

    /** 行内状态：当前段显示"转码中 37% · 1.8×"，已完成段打勾，未处理段留空 */
    private fun applyRowStates(cv: CardViews, p: GroupProgress) {
        val seg = p.seg
        cv.fileStateViews.forEachIndexed { i, tv ->
            val want = when {
                p.stage == MergeStage.DONE -> "✓"
                p.stage == MergeStage.FAILED || p.stage == MergeStage.STOPPED -> ""
                seg == null -> ""
                seg.index == i + 1 -> StatusText.rowText(p) ?: ""
                seg.index > i + 1 -> "✓"
                else -> ""
            }
            if (tv.text.toString() != want) tv.text = want
            tv.isVisible = want.isNotEmpty()
            if (want.isNotEmpty()) {
                tv.setTextColor(
                    ContextCompat.getColor(
                        this,
                        when {
                            want == "✓" -> R.color.text_disabled
                            p.stage == MergeStage.DONE -> R.color.success
                            else -> R.color.brand_primary
                        }
                    )
                )
            }
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        btnNewGroup.isEnabled = enabled
        btnStart.isEnabled = enabled
        btnAlbumPick.isEnabled = enabled
        // 拼接期间不允许改输出设置：本轮设置已快照，中途改会让直拷段与
        // 缩放段按不同参数产出，混拼成分辨率不一致的成品
        btnOutputSettings.isEnabled = enabled
        btnNightMode.isEnabled = enabled
        containerGroups.alpha = if (enabled) 1f else 0.55f
        // 卡片内控件按行位置单独算，绝不递归统一置位——旧实现会覆盖首行 ↑ /
        // 末行 ↓ 的禁用状态，点一下就 swap(items, 0, -1) 崩溃
        for ((_, cv) in cardViews) cv.applyEnabled(!enabled, this)
    }

    /**
     * 点分组里的视频行：跳系统播放器直接看原视频（1.9.2 新增）。
     * 照片选择器的 picker URI 先还原成媒体条目 URI（部分播放器认不出 picker 形态）。
     */
    private fun openOriginal(uri: Uri) {
        val target = mediaItemUri(uri)
        val play = Intent(Intent.ACTION_VIEW)
            .setDataAndType(target, "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(play) }
            .onFailure { toast("没有找到可以播放该视频的应用") }
    }

    private fun openLastOutput() {
        val uri = outputs.lastOrNull() ?: return
        // 优先跳系统相册的视频列表：成品按时间排在最前，能看到拼接结果与上下文，
        // 也方便紧接着决定是否删除源视频
        val gallery = Intent(Intent.ACTION_VIEW)
            .setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(gallery) }.onFailure {
            // 没有应用处理视频目录视图时，退回直接打开最新成品
            val single = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(single) }
                .onFailure { toast("成品保存在 相册 → Movies/VideoStitcher") }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
