package com.kai.videostitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoStitcher"
    }

    private class CardViews(
        val progressBar: ProgressBar,
        val statusText: TextView,
        val infoText: TextView
    )

    private val groups = mutableListOf<Group>()
    private val cardViews = HashMap<Group, CardViews>()
    private val outputs: MutableList<Uri> = Collections.synchronizedList(mutableListOf())
    private var merging = false

    private lateinit var containerGroups: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var pbOverall: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnOpenOutput: Button
    private lateinit var btnNewGroup: Button
    private lateinit var btnImportFolders: Button

    private val pickVideos =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) addNewGroup(uris)
        }
    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importFromFolder(uri)
        }
    private val albumPick =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                @Suppress("DEPRECATION")
                val uris = res.data?.getParcelableArrayListExtra<Uri>(AlbumPickerActivity.EXTRA_URIS)
                if (uris.isNullOrEmpty()) return@registerForActivityResult
                val fresh = uris.filter { u -> groups.none { g -> g.items.any { it.uri == u } } }
                if (fresh.isEmpty()) {
                    toast("所选视频都已经在分组里了")
                    return@registerForActivityResult
                }
                if (fresh.size < uris.size) toast("已跳过 ${uris.size - fresh.size} 个重复视频")
                addNewGroup(fresh)
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
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvStatus)
        pbOverall = findViewById(R.id.pbOverall)
        btnStart = findViewById(R.id.btnStart)
        btnOpenOutput = findViewById(R.id.btnOpenOutput)
        btnNewGroup = findViewById(R.id.btnNewGroup)
        btnImportFolders = findViewById(R.id.btnImportFolders)

        btnNewGroup.setOnClickListener {
            if (!merging) pickVideos.launch(arrayOf("video/*"))
        }
        btnImportFolders.setOnClickListener {
            if (!merging) pickTree.launch(null)
        }
        findViewById<Button>(R.id.btnAlbumPick).setOnClickListener {
            if (!merging) albumPick.launch(Intent(this, AlbumPickerActivity::class.java))
        }
        btnStart.setOnClickListener { onStartClicked() }
        btnOpenOutput.setOnClickListener { openLastOutput() }

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

    private fun addNewGroup(uris: List<Uri>) {
        uris.forEach {
            runCatching {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val items = uris.mapIndexed { i, u ->
            VideoItem(u, queryDisplayName(this, u, "视频${i + 1}.mp4"), 0)
        }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        val group = Group("组${groups.size + 1}", items.toMutableList())
        groups.add(group)
        render()
        fillDurationsAsync(group)
    }

    private fun importFromFolder(treeUri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null) {
            toast("无法读取该文件夹")
            return
        }
        val imported = mutableListOf<Group>()
        for (dir in root.listFiles().filter { it.isDirectory }) {
            val files = dir.listFiles()
                .filter { it.isFile && isVideoName(it.name ?: "") }
                .sortedWith { a, b -> naturalCompare(a.name ?: "", b.name ?: "") }
            if (files.isEmpty()) continue
            imported.add(
                Group(
                    dir.name ?: "分组",
                    files.map { VideoItem(it.uri, it.name ?: "", 0) }.toMutableList()
                )
            )
        }
        if (imported.isEmpty()) {
            toast("这个文件夹下没有找到包含视频的子文件夹")
            return
        }
        groups.addAll(imported)
        render()
        imported.forEach { fillDurationsAsync(it) }
    }

    private fun fillDurationsAsync(group: Group) {
        lifecycleScope.launch(Dispatchers.IO) {
            for (item in group.items) {
                if (item.durationMs <= 0) item.durationMs = probeDuration(this@MainActivity, item.uri)
                if (item.codec.isEmpty()) item.codec = probeCodecSummary(this@MainActivity, item.uri)
            }
            withContext(Dispatchers.Main) {
                if (!merging) render()
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

        fun refreshInfo() {
            val total = group.items.sumOf { it.durationMs }
            tvInfo.text = "${group.items.size} 个视频" + if (total > 0) " · 总时长 ${formatDuration(total)}" else ""
        }
        refreshInfo()

        val thumbViews = HashMap<String, ImageView>()
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
            val up = row.findViewById<Button>(R.id.btnUp)
            val down = row.findViewById<Button>(R.id.btnDown)
            up.isEnabled = index > 0
            down.isEnabled = index < group.items.size - 1
            up.setOnClickListener {
                if (merging) return@setOnClickListener
                Collections.swap(group.items, index, index - 1)
                render()
            }
            down.setOnClickListener {
                if (merging) return@setOnClickListener
                Collections.swap(group.items, index, index + 1)
                render()
            }
            row.findViewById<Button>(R.id.btnRemove).setOnClickListener {
                if (merging) return@setOnClickListener
                group.items.removeAt(index)
                if (group.items.isEmpty()) groups.remove(group)
                render()
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

        cardViews[group] = CardViews(pbGroup, tvStatusGroup, tvInfo)
        return card
    }

    private fun onStartClicked() {
        if (merging) return
        if (groups.all { it.items.isEmpty() }) {
            toast("请先添加视频")
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
        merging = true
        setUiEnabled(false)
        pbOverall.isVisible = true
        btnOpenOutput.isVisible = false
        outputs.clear()
        val targets = groups.filter { it.items.isNotEmpty() }
        // 各组并行推进；Transformer 导出类工作由 Merger.exportGate 全局限 2 路，
        // 无损拼接只吃 IO 可完全并行
        val done = AtomicInteger(0)
        lifecycleScope.launch(Dispatchers.IO) {
            targets.map { group ->
                launch(Dispatchers.IO) {
                    try {
                        val out = mergeGroup(group)
                        outputs.add(out)
                    } catch (t: Throwable) {
                        setGroupState(group, "✗ 失败：${t.message ?: t.javaClass.simpleName}", -1)
                    } finally {
                        val d = done.incrementAndGet()
                        if (d < targets.size) {
                            runOnUiThread { tvStatus.text = "拼接中…已完成 $d/${targets.size} 组" }
                        }
                    }
                }
            }.joinAll()
            withContext(Dispatchers.Main) {
                merging = false
                setUiEnabled(true)
                pbOverall.isVisible = false
                val ok = outputs.size
                tvStatus.text = if (ok == targets.size)
                    "全部完成！$ok 个成品已保存到 相册 → Movies/VideoStitcher"
                else
                    "完成 $ok/${targets.size}，失败的分组下方有提示。"
                if (ok > 0) btnOpenOutput.isVisible = true
            }
        }
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
        setGroupState(group, "分析视频参数…", null)
        val infos = group.items.map {
            runCatching { probeVideo(this@MainActivity, it.uri) }.getOrElse { t ->
                android.util.Log.e(TAG, "probe failed: ${it.uri}", t)
                TrackInfo(null, 0, 0, 0, 0f, null, 0, 0, false)
            }
        }
        val first = infos.first()
        val paramsUniform = infos.all { it.matches(first) }
        val expectedDurationMs = group.items.sumOf { it.durationMs }
        val mp4Family = group.items.all { isLosslessCapableName(it.name) }
        // mp4parser 解析不了 AV1 的 av01 采样条目（实测抛异常），无损路径必须排除 AV1
        val hasAv1 = infos.any { it.videoMime == "video/av01" }

        var outUri = createOutputUri(this@MainActivity, sanitizeFileName("${group.name}_合并.mp4"))
        if (paramsUniform && mp4Family && !hasAv1) {
            setGroupState(group, "无损拼接中…（不重新编码，秒级完成）", null)
            try {
                contentResolver.openFileDescriptor(outUri, "rw")!!.use { pfd ->
                    concatLossless(this@MainActivity, group.items, pfd)
                }
                // 成品必须自检通过：拼接"成功"不等于能播放，不过就降级下一档
                verifyOutputUsable(this@MainActivity, outUri, expectedDurationMs)?.let { reason ->
                    throw IllegalStateException("自检未通过：$reason")
                }
                setGroupState(group, "✓ 完成（无损拼接，画质无损失）", 100)
                return outUri
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
            // 先试无损转封装（拷贝压缩流，不解码不重编码），失败再转码
            try {
                transmuxConcat(this@MainActivity, group.items, outUri) { p ->
                    setGroupState(group, "无损转封装中 $p%…", p)
                }
                verifyOutputUsable(this@MainActivity, outUri, expectedDurationMs)?.let { reason ->
                    throw IllegalStateException("自检未通过：$reason")
                }
                setGroupState(group, "✓ 完成（无损转封装，无重编码）", 100)
                return outUri
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "transmux failed", t)
                setGroupState(group, "转封装失败（${t.message ?: "格式不兼容"}）…", null)
            }
        }
        // 走到这里 = 组内参数不一致或引擎处理不了。本 App 刻意不做转码：
        // 真机上重新编码产出过无法播放/拉伸错乱的成品（1.2~1.3.1 的教训），
        // 宁可中止让用户自行处理，也绝不交付坏文件
        runCatching { contentResolver.delete(outUri, null, null) }
        throw IllegalStateException(inconsistentAdvice(infos))
    }

    private fun setGroupState(group: Group, text: String, progress: Int?) {
        runOnUiThread {
            val cv = cardViews[group] ?: return@runOnUiThread
            cv.statusText.isVisible = true
            cv.statusText.text = text
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
        btnImportFolders.isEnabled = enabled
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
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast("成品保存在 相册 → Movies/VideoStitcher") }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
