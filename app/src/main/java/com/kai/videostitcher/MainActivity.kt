package com.kai.videostitcher

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
        // "删除原视频"按钮：拼接成功前灰、成功后红
        private val COLOR_DELETE_OFF = Color.parseColor("#5A5A6E")
        private val COLOR_DELETE_ON = Color.parseColor("#FF5A6E")
        private val successTextColor = Color.parseColor("#7BEFB4")
        private val errorTextColor = Color.parseColor("#FFB3BC")
        private val infoTextColor = Color.parseColor("#C9BEFF")
        private val stoppedTextColor = Color.parseColor("#A3A3B8")
    }

    private class CardViews(
        val progressBar: ProgressBar,
        val statusText: TextView,
        val infoText: TextView,
        val fileDurationViews: List<TextView>,
        val btnDeleteSources: Button
    )

    private val groups = mutableListOf<Group>()
    private val cardViews = HashMap<Group, CardViews>()
    private val outputs: MutableList<Uri> = Collections.synchronizedList(mutableListOf())
    private var merging = false
    /** 当前这轮拼接的协程；「停止拼接」对它 cancel，取消链路会清理半成品 */
    private var mergeJob: Job? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
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

        btnNewGroup.setOnClickListener {
            if (!merging) pickTree.launch(null)
        }
        findViewById<Button>(R.id.btnAlbumPick).setOnClickListener {
            // "从相册选"走系统照片选择器（与其它应用的体验一致）；
            // 极少数没有系统选择器的设备退回内置相册选择器兜底
            if (!merging) launchSystemVideoPicker()
        }
        btnStart.setOnClickListener { if (merging) stopMerging() else onStartClicked() }
        btnOpenOutput.setOnClickListener { openLastOutput() }
        findViewById<Button>(R.id.btnOutputSettings).setOnClickListener { showOutputSettingsDialog() }
        refreshOutputSettingsLabel()

        groups.addAll(Store.load(this))
        render()
        initBoxParser(applicationContext)
        ensureMediaPermission()
        // 旧版本保存的分组没有编码信息，启动时补探一次（已有编码的条目会跳过）
        groups.forEach { fillDurationsAsync(it) }
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
            for (item in group.items) {
                if (item.durationMs <= 0) item.durationMs = probeDuration(this@MainActivity, item.uri)
                if (item.codec.isEmpty()) item.codec = probeCodecSummary(this@MainActivity, item.uri)
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
            btnDeleteSources.setTextColor(if (active) COLOR_DELETE_ON else COLOR_DELETE_OFF)
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
            tvStatusGroup.setTextColor(successTextColor)
        }

        fun refreshInfo() {
            val total = group.items.sumOf { it.durationMs }
            tvInfo.text = "${group.items.size} 个视频" + if (total > 0) " · 总时长 ${formatDuration(total)}" else ""
        }
        refreshInfo()

        val thumbViews = HashMap<String, ImageView>()
        val fileDurationViews = mutableListOf<TextView>()
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
            row.findViewById<Button>(R.id.btnRemove).setOnClickListener {
                if (merging) return@setOnClickListener
                group.items.removeAt(index)
                group.mergedOk = false
                if (group.items.isEmpty()) groups.remove(group)
                render()
            }
            // 拖动"≡"手柄调整顺序（替代旧 ↑↓ 按钮）
            row.findViewById<ImageView>(R.id.ivDragHandle).setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (merging || dragSort != null) return@setOnTouchListener false
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        dragSort = DragSort(group, llFiles, index).apply {
                            grabOffset = ev.rawY - (contentTop() + index * rowH)
                            update(ev.rawY)
                        }
                        scrollGroups.postDelayed(dragScrollTick, 24)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        dragSort?.update(ev.rawY)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        scrollGroups.removeCallbacks(dragScrollTick)
                        dragSort?.finish(commit = ev.actionMasked == MotionEvent.ACTION_UP)
                        true
                    }
                    else -> false
                }
            }
            llFiles.addView(row)
        }

        // 异步加载缺失的封面缩略图，加载完成后仅刷新对应 ImageView
        lifecycleScope.launch(Dispatchers.IO) {
            for (item in group.items) {
                val key = item.uri.toString()
                if (Thumbs.get(key) != null) continue
                val bmp = Thumbs.load(applicationContext, item.uri) ?: continue
                withContext(Dispatchers.Main) {
                    thumbViews[key]?.takeIf { it.isAttachedToWindow && it.tag == key }
                        ?.setImageBitmap(bmp)
                }
            }
        }

        cardViews[group] = CardViews(pbGroup, tvStatusGroup, tvInfo, fileDurationViews, btnDeleteSources)
        return card
    }

    // ---- 组内视频拖拽排序 ----
    private var dragSort: DragSort? = null
    private val dragScrollTick = object : Runnable {
        override fun run() {
            val st = dragSort ?: return
            st.autoScrollStep()
            scrollGroups.postDelayed(this, 24)
        }
    }

    /** 一次拖拽手势的状态：行平移贴指 + 与相邻行交叉即换位 + 贴边自动滚动 */
    private inner class DragSort(
        private val group: Group,
        private val llFiles: LinearLayout,
        private val startIndex: Int
    ) {
        private val rows = (0 until llFiles.childCount).map { llFiles.getChildAt(it) }
        val dragView = rows[startIndex]
        val rowH = dragView.height.coerceAtLeast(1)
        private val loc = IntArray(2)
        var grabOffset = 0f
        var lastRawY = 0f
        private var targetIndex = startIndex

        init {
            dragView.elevation = 12f
            dragView.alpha = 0.95f
        }

        fun contentTop(): Float {
            llFiles.getLocationOnScreen(loc)
            return loc[1].toFloat()
        }

        fun update(rawY: Float) {
            lastRawY = rawY
            // 行平移量 = 手指内容坐标 - 握点偏移 - 行原位（钳制在列表范围内）
            val ty = (rawY - contentTop() - grabOffset - startIndex * rowH)
                .coerceIn(0f, ((rows.size - 1) * rowH).toFloat())
            dragView.translationY = ty
            val tgt = (ty / rowH).toInt().coerceIn(0, rows.size - 1)
            if (tgt != targetIndex) {
                targetIndex = tgt
                shiftOthers()
            }
        }

        /** 被跨过的行让位（视觉平移一行高），松手前不重绑视图，触摸流不中断 */
        private fun shiftOthers() {
            rows.forEachIndexed { i, v ->
                if (v === dragView) return@forEachIndexed
                v.translationY = when {
                    startIndex < targetIndex && i in (startIndex + 1)..targetIndex -> -rowH.toFloat()
                    startIndex > targetIndex && i in targetIndex until startIndex -> rowH.toFloat()
                    else -> 0f
                }
            }
        }

        /** 指尖贴近屏幕上下边缘时自动滚动列表（滚动会改变内容坐标，重新贴指） */
        fun autoScrollStep() {
            val screenH = resources.displayMetrics.heightPixels
            val dy = when {
                lastRawY < 140f -> -30
                lastRawY > screenH - 140f -> 30
                else -> 0
            }
            if (dy != 0) {
                scrollGroups.smoothScrollBy(0, dy)
                update(lastRawY)
            }
        }

        /** 松手落位：数据真正移动 + 状态失效 + 存盘 + 重绑；未换位只复位视觉 */
        fun finish(commit: Boolean) {
            val tgt = targetIndex
            rows.forEach {
                it.translationY = 0f
                it.elevation = 0f
                it.alpha = 1f
            }
            dragSort = null
            if (!commit || tgt == startIndex) return
            val item = group.items.removeAt(startIndex)
            group.items.add(tgt, item)
            // 顺序变了：已拼接状态失效（红色删源按钮退回灰色）+ 落盘 + 重绑序号
            group.mergedOk = false
            Store.save(this@MainActivity, groups)
            render()
        }
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
        if (merging) return
        merging = true
        setUiEnabled(false)
        // 拼接期间主按钮变成"停止拼接"，保持可点
        btnStart.isEnabled = true
        updateMergeButton()
        pbOverall.isVisible = true
        btnOpenOutput.isVisible = false
        outputs.clear()
        // 只拼还没拼成功的分组：mergedOk 的组保留成品原样不动
        val targets = groups.filter { it.items.isNotEmpty() && !it.mergedOk }
        val succeeded = mutableListOf<Group>()
        mergeJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 多个分组按列表顺序逐组拼接：一次只跑一组，状态一目了然，
                // 单组也能吃满解码/转码资源，多组并行互相抢 CPU 反而更慢
                for ((idx, group) in targets.withIndex()) {
                    try {
                        val out = mergeGroup(group)
                        outputs.add(out)
                        succeeded.add(group)
                        // 顺序拼接完成一组点亮一组：立即标记成功并落盘，
                        // 进程被杀（崩溃/低内存）也不会把成功状态丢掉
                        withContext(NonCancellable + Dispatchers.Main) {
                            group.mergedOk = true
                            Store.save(this@MainActivity, groups)
                            refreshDeleteSourceButtons()
                            if (idx < targets.size - 1) {
                                tvStatus.text = "拼接中…已完成 ${idx + 1}/${targets.size} 组"
                            }
                        }
                    } catch (c: CancellationException) {
                        // 取消不是失败：向上传播，别把取消误标成"✗ 失败"
                        throw c
                    } catch (t: Throwable) {
                        setGroupState(group, "✗ 失败：${t.message ?: t.javaClass.simpleName}", -1)
                        if (idx < targets.size - 1) {
                            withContext(Dispatchers.Main) {
                                tvStatus.text = "拼接中…已完成 ${idx}/${targets.size} 组"
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) { finishMergingUi(targets, succeeded) }
            } catch (c: CancellationException) {
                // 用户按了"停止拼接"（或页面销毁）：清点已完成的成品，
                // 未完成分组的半成品文件已在各引擎的取消链路里删掉
                withContext(NonCancellable + Dispatchers.Main) { onMergeStopped(targets, succeeded) }
                throw c
            }
        }
    }

    /** 输出设置摘要标签：原尺寸 · 自动 / 720p · H.265 … */
    private fun refreshOutputSettingsLabel() {
        val s = loadTranscodeSettings(this)
        val res = when (s.shortEdge) { 1080 -> "1080p"; 720 -> "720p"; else -> "原尺寸" }
        val codec = when (s.codecMode) {
            "h265" -> "H.265"; "av1" -> "AV1"; "h264" -> "H.264"; else -> "自动跟随源"
        }
        findViewById<Button>(R.id.btnOutputSettings).text = "输出设置：$res · $codec（仅转码组生效）"
    }

    /** 输出设置对话框：分辨率压缩 + 编码格式。只影响需要转码的分组 */
    private fun showOutputSettingsDialog() {
        val cur = loadTranscodeSettings(this)
        val resNames = arrayOf("保持原尺寸（默认）", "压缩到 1080p", "压缩到 720p（最小体积）")
        val resValues = intArrayOf(0, 1080, 720)
        val av1Ok = av1EncodeAvailable()
        val codecNames = arrayOf(
            "自动（跟随源视频编码，混合时少数服从多数）",
            "H.264（推荐：兼容性最好）",
            "H.265（体积约省 30-50%，转码更慢，老设备可能不支持）",
            if (av1Ok) "AV1（体积最小，播放兼容性较弱）"
            else "AV1（本机编码组件不支持，选了也会回退 H.264）"
        )
        val codecValues = arrayOf("auto", "h264", "h265", "av1")
        val checkedRes = resValues.indexOf(cur.shortEdge).coerceAtLeast(0)
        val checkedCodec = codecValues.indexOf(cur.codecMode).coerceAtLeast(0)
        var selRes = cur.shortEdge
        var selCodec = cur.codecMode

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        fun sectionLabel(text: String, topPad: Int = 0) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#A3A3B8"))
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
        container.addView(TextView(this).apply {
            text = "仅对参数不一致、需要转码的分组生效；无损拼接的分组永远保持原画质不变。" +
                "自动模式下编码不统一的分组按数量最多者输出（平票取 H.264）。"
            textSize = 12f
            setTextColor(Color.parseColor("#A3A3B8"))
            setPadding(0, 24, 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("输出设置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                saveTranscodeSettings(this, TranscodeSettings(selRes, selCodec))
                refreshOutputSettingsLabel()
                toast("输出设置已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 用户点击"停止拼接"：先弹窗确认防误触，确认后取消整轮拼接，半成品由取消链路自动清理 */
    private fun stopMerging() {
        if (!merging) return
        AlertDialog.Builder(this)
            .setTitle("停止拼接")
            .setMessage("确定要停止拼接吗？\n\n未完成的半成品会被清理；已完成的成品保留在相册。")
            .setPositiveButton("停止拼接") { _, _ ->
                tvStatus.text = "正在停止并清理未完成的成品…"
                mergeJob?.cancel()
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

    /** 一轮拼接正常收尾（全部跑完，含失败组） */
    private fun finishMergingUi(targets: List<Group>, succeeded: List<Group>) {
        merging = false
        mergeJob = null
        setUiEnabled(true)
        pbOverall.isVisible = false
        updateMergeButton()
        val ok = outputs.size
        tvStatus.text = if (ok == targets.size)
            "全部完成！$ok 个成品已保存到 相册 → Movies/VideoStitcher"
        else
            "完成 $ok/${targets.size}，失败的分组下方有提示。"
        if (ok > 0) btnOpenOutput.isVisible = true
        // 只有确认成功的组才点亮红色"删除原视频"，失败的组保持灰色。
        // 立即落盘：进程被杀（崩溃/低内存）也不会把成功状态丢掉
        succeeded.forEach { it.mergedOk = true }
        Store.save(this, groups)
        refreshDeleteSourceButtons()
    }

    /** 用户停止后的收尾：已完成的成品保留，未完成的分组标"已停止" */
    private fun onMergeStopped(targets: List<Group>, succeeded: List<Group>) {
        if (!merging) return
        merging = false
        mergeJob = null
        setUiEnabled(true)
        pbOverall.isVisible = false
        updateMergeButton()
        val ok = succeeded.size
        tvStatus.text = if (ok > 0)
            "已停止拼接：$ok 组已完成（成品保留在相册），未完成的半成品已清理"
        else
            "已停止拼接，未完成的半成品已清理"
        if (ok > 0) btnOpenOutput.isVisible = true
        succeeded.forEach { it.mergedOk = true }
        Store.save(this, groups)
        refreshDeleteSourceButtons()
        val okSet = succeeded.toSet()
        for (g in targets) {
            if (g in okSet) continue
            val cv = cardViews[g] ?: continue
            val cur = cv.statusText.text?.toString() ?: ""
            if (cur.startsWith("✓") || cur.startsWith("✗")) continue
            setGroupState(g, "已停止", -1)
        }
    }

    private fun refreshDeleteSourceButtons() {
        groups.forEach { g ->
            cardViews[g]?.let { cv ->
                val active = g.mergedOk && !merging
                cv.btnDeleteSources.isEnabled = active
                cv.btnDeleteSources.setTextColor(if (active) COLOR_DELETE_ON else COLOR_DELETE_OFF)
            }
        }
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

    private suspend fun mergeGroup(group: Group): Uri {
        setGroupState(group, "检查视频可读性…", null)
        // 授权失效/文件被移动的视频提前发现，给出可操作的提示而不是神秘报错
        val unreadable = group.items.count { item ->
            runCatching { contentResolver.openFileDescriptor(item.uri, "r")!!.close() }.isFailure
        }
        if (unreadable > 0) {
            throw IllegalStateException("有 $unreadable 个视频无法读取（可能已被移动、删除或授权失效），请把它们从分组中删除后重新添加")
        }
        checkDiskSpace(group)
        setGroupState(group, "分析视频参数…", null)
        // 逐项并行探测：几十条的分组串行要探几十秒，并行只花最长那一条的时间
        var infos = coroutineScope {
            group.items.map { item ->
                async(Dispatchers.IO) {
                    runCatching { probeVideo(this@MainActivity, item.uri) }.getOrElse { t ->
                        android.util.Log.e(TAG, "probe failed: ${item.uri}", t)
                        TrackInfo(null, 0, 0, 0, 0f, null, 0, 0, false)
                    }
                }
            }.awaitAll()
        }
        // 带外来轨的输入（Android 13+ 录屏的 mett 元数据轨、字幕/时间码轨）所有引擎
        // 都读不了：先 -c copy 无损剥离再进引擎链；个别剥离失败的原样保留不更糟
        var workItems = group.items
        val cleanedFiles = mutableListOf<File>()
        try {
            if (infos.any { it.extraTracks > 0 }) {
                setGroupState(group, "清理附加数据轨…", null)
                val work = mutableListOf<VideoItem>()
                group.items.forEachIndexed { i, item ->
                    if (infos[i].extraTracks > 0) {
                        val tmp = File(cacheDir, uniqueTempName("剥轨", ".mp4"))
                        if (stripForeignTracks(this@MainActivity, item.uri, tmp)) {
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
                if (work != group.items.toList()) {
                    workItems = work
                    infos = infos.mapIndexed { i, info ->
                        if (workItems[i].uri != group.items[i].uri)
                            runCatching { probeVideo(this@MainActivity, workItems[i].uri) }.getOrElse { info }
                        else info
                    }
                }
            }
            val first = infos.first()
            val paramsUniform = infos.all { it.matches(first) }
            // 导入时的时长探测可能失败过（存了 0）：拼前逐项补探——只补缺失项，
            // 否则总时长被低估、自检的截断阈值会被放松
            workItems.forEach { item ->
                if (item.durationMs <= 0) item.durationMs = probeDuration(this@MainActivity, item.uri)
            }
            val expectedDurationMs = workItems.sumOf { it.durationMs }
            val mp4Family = workItems.all { isLosslessCapableName(it.name) }
            // mp4parser 解析不了 AV1 的 av01 采样条目（实测抛异常），无损路径必须排除 AV1
            val hasAv1 = infos.any { it.videoMime == "video/av01" }
            val probeFailed = infos.count { it.videoMime == null }
            return runMergeEngines(group, workItems, infos, expectedDurationMs, paramsUniform, mp4Family, hasAv1, probeFailed)
        } finally {
            cleanedFiles.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * 三级引擎链：无损拼接 → 无损转封装 → ffmpeg 转码 → 中止。
     * 成功返回输出 Uri。中途取消（旋转屏幕/退页面/后台被杀）会删掉已创建的
     * 半截输出条目再向上传播取消——否则相册里会留下一个解不动的残缺 MP4。
     */
    private suspend fun runMergeEngines(
        group: Group,
        items: List<VideoItem>,
        infos: List<TrackInfo>,
        expectedDurationMs: Long,
        paramsUniform: Boolean,
        mp4Family: Boolean,
        hasAv1: Boolean,
        probeFailed: Int
    ): Uri {
        var outUri = createOutputUri(this@MainActivity, sanitizeFileName("${group.name}_合并.mp4"))
        try {
            if (paramsUniform && mp4Family && !hasAv1) {
                setGroupState(group, "无损拼接中…（不重新编码，秒级完成）", null)
                try {
                    contentResolver.openFileDescriptor(outUri, "rw")!!.use { pfd ->
                        concatLossless(this@MainActivity, items, pfd)
                    }
                    // 成品必须自检通过：拼接"成功"不等于能播放，不过就降级下一档
                    verifyOutputUsable(this@MainActivity, outUri, expectedDurationMs, segmentCheckPoints(items))
                        ?.let { reason -> throw IllegalStateException("自检未通过：$reason") }
                    setGroupState(group, "✓ 完成（无损拼接，画质无损失）", 100)
                    return outUri
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "lossless failed, trying alternatives", t)
                    runCatching { contentResolver.delete(outUri, null, null) }
                    // 重新创建输出条目，否则后续拼接无处可写
                    outUri = createOutputUri(this@MainActivity, sanitizeFileName("${group.name}_合并.mp4"))
                    setGroupState(group, "无损模式失败（${t.message ?: "格式不兼容"}），尝试其它方式…", null)
                }
            }
            // 无损转封装只处理无旋转元数据的组：转封装走 media3 的序列管线，
            // 对旋转元数据的携带在真机上不可靠（带旋转的组请用 MP4/MOV 原件走
            // 上面的无损拼接，旋转矩阵在容器层原样保留）
            if (paramsUniform && isMp4MuxCompatible(infos) && infos.all { it.rotation == 0 }) {
                // 参数一致且编码能直封进 MP4（任意容器，含 MKV/WebM/TS/AV1）：
                // 直接拷贝压缩流换壳，不解码不重编码
                try {
                    transmuxConcat(this@MainActivity, items, outUri) { p ->
                        setGroupState(group, "无损转封装中 $p%…", p)
                    }
                    verifyOutputUsable(this@MainActivity, outUri, expectedDurationMs, segmentCheckPoints(items))
                        ?.let { reason -> throw IllegalStateException("自检未通过：$reason") }
                    setGroupState(group, "✓ 完成（无损转封装，无重编码）", 100)
                    return outUri
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "transmux failed", t)
                    setGroupState(group, "转封装失败（${t.message ?: "格式不兼容"}），尝试其它无损方式…", null)
                }
            }
            // 第二级半（1.5.4 新增）：视频参数完全一致（含 csd 解码配置字节逐字节一致）
            // 但音频不一致的组——视频流一个字节不动、只重编音频（秒级）。录屏混相机、
            // 44.1k 混 48k 这类常见组从分钟级转码降到秒级，且视频零画质损失
            if (mp4Family && probeFailed == 0 &&
                infos.all { it.csd != null && it.matchesVideoOnly(infos.first()) }
            ) {
                setGroupState(group, "视频无损拼接中（仅音频重编，秒级）…", null)
                try {
                    videoCopyConcatAudio(this@MainActivity, items, infos, outUri) { msg ->
                        setGroupState(group, msg, null)
                    }
                    setGroupState(group, "✓ 完成（视频无损 + 音频重编，画质无损失）", 100)
                    return outUri
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "video-copy concat failed", t)
                    setGroupState(group, "视频无损+音频重编失败（${t.message ?: "格式不兼容"}），尝试转码…", null)
                }
            }
            // 第三级（v1.5 新增）：参数不一致或前两级处理不了 → ffmpeg 逐段独立转码成
            // 统一参数再拼。x264 软编全机型行为一致，绕开真机硬件会话差异的坑；
            // 拼前有 SPS/PPS 闸门、拼后有成品自检，最坏情况是中止，不产出坏文件
            if (probeFailed == 0) {
                if (!ffmpegAvailable()) {
                    runCatching { contentResolver.delete(outUri, null, null) }
                    throw IllegalStateException(
                        "这组视频参数不一致，而本机 CPU 架构不支持转码引擎（需要 64 位 ARM 或 x86_64 设备）。" +
                            "请把它们自行转码成参数一致的普通 MP4 后再导入"
                    )
                }
                // 真正落到转码级才检查内置分区（转码分段中间文件都在 cacheDir）：
                // 运行期从无损降级下来的组也躲不过这一关
                checkInternalDiskSpace(group)
                setGroupState(group, "自动转码拼接中…（较慢，约为视频时长）", null)
                try {
                    val codec = transcodeConcat(this@MainActivity, items, infos, outUri) { msg ->
                        setGroupState(group, msg, null)
                    }
                    val codecName = when (codec) { "h265" -> "H.265"; "av1" -> "AV1"; else -> "H.264" }
                    setGroupState(group, "✓ 完成（转码拼接，已统一为 $codecName+AAC）", 100)
                    return outUri
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "transcode failed", t)
                    runCatching { contentResolver.delete(outUri, null, null) }
                    // 引擎级失败如实上报：failureDetail 带上错误码提示、内层原因
                    // 和组内检测（文档承诺过的格式），不再光秃秃一个异常消息
                    throw IllegalStateException(
                        "转码拼接失败：${failureDetail(t, infos)}", t
                    )
                }
            }
            // 只有探测不动的输入才会走到这里（如网页下载/聊天转发的非常规封装）
            runCatching { contentResolver.delete(outUri, null, null) }
            throw IllegalStateException(inconsistentAdvice(infos))
        } catch (c: CancellationException) {
            runCatching { contentResolver.delete(outUri, null, null) }
            throw c
        }
    }

    /**
     * 磁盘空间预检（成品输出卷）：成品整文件写出，转封装还要先写临时件再整块
     * 拷贝（峰值约 2×）；素材体积只是下限参考（crf 转码输出可能比源更大）。
     * 不足时开工前就报清楚，而不是半路 IOException 被误归成"格式不兼容"。
     */
    private fun checkDiskSpace(group: Group) {
        val dir = getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory() ?: return
        val available = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        var needBytes = 0L
        for (item in group.items) {
            runCatching {
                contentResolver.openFileDescriptor(item.uri, "r")?.use { needBytes += it.statSize }
            }
        }
        needBytes = needBytes * 2 + 200L * 1024 * 1024
        if (available < needBytes) {
            throw IllegalStateException(
                "存储空间不足：本组约需 ${needBytes / (1024 * 1024)}MB，设备可用 ${available / (1024 * 1024)}MB，请清理空间后重试"
            )
        }
    }

    /**
     * 转码分区的预检：分段中间件（合计约等于成品大小）+ 拼接临时件（再一份）+
     * 重编放大的余量，峰值约素材的 2.6 倍；写在 cacheDir（内置 data 分区），
     * 和共享存储不是一个卷，可用空间常差好几倍，必须单独查。
     */
    private fun checkInternalDiskSpace(group: Group) {
        val available = runCatching { StatFs(cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        var needBytes = 0L
        for (item in group.items) {
            runCatching {
                contentResolver.openFileDescriptor(item.uri, "r")?.use { needBytes += it.statSize }
            }
        }
        needBytes = needBytes * 26 / 10 + 200L * 1024 * 1024
        if (available < needBytes) {
            throw IllegalStateException(
                "存储空间不足（应用数据分区）：本组转码约需 ${needBytes / (1024 * 1024)}MB，" +
                    "该分区可用 ${available / (1024 * 1024)}MB，请清理空间后重试"
            )
        }
    }

    private fun setGroupState(group: Group, text: String, progress: Int?) {
        // 拼接已结束时忽略迟到的进度回调：取消 ffmpeg 后其统计回调可能再触发一次，
        // 把"已停止"覆盖回"自动转码中…"。收尾态（✓ / ✗ / 已停止）总是允许写入。
        if (!merging && !text.startsWith("✓") && !text.startsWith("✗") && !text.startsWith("已停止")) return
        runOnUiThread {
            val cv = cardViews[group] ?: return@runOnUiThread
            cv.statusText.isVisible = true
            cv.statusText.text = text
            // 状态胶囊按语义换色：✓ 成功→绿，✗ 失败→红，已停止→灰，其余运行态→品牌紫
            when {
                text.startsWith("✓") -> {
                    cv.statusText.setBackgroundResource(R.drawable.pill_success)
                    cv.statusText.setTextColor(successTextColor)
                }
                text.startsWith("✗") -> {
                    cv.statusText.setBackgroundResource(R.drawable.pill_error)
                    cv.statusText.setTextColor(errorTextColor)
                }
                text.startsWith("已停止") -> {
                    cv.statusText.setBackgroundResource(R.drawable.pill_neutral)
                    cv.statusText.setTextColor(stoppedTextColor)
                }
                else -> {
                    cv.statusText.setBackgroundResource(R.drawable.pill_info)
                    cv.statusText.setTextColor(infoTextColor)
                }
            }
            if (progress == null) {
                cv.progressBar.isVisible = true
                cv.progressBar.isIndeterminate = true
            } else if (progress < 0) {
                cv.progressBar.isVisible = false
            } else {
                cv.progressBar.isVisible = true
                cv.progressBar.isIndeterminate = false
                cv.progressBar.progress = progress
            }
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        btnNewGroup.isEnabled = enabled
        btnStart.isEnabled = enabled
        fun walk(v: View) {
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            v.isEnabled = enabled
        }
        walk(containerGroups)
        containerGroups.alpha = if (enabled) 1f else 0.55f
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
