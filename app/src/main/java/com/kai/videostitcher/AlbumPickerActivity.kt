package com.kai.videostitcher

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内相册选择器：直接查询系统视频库（MediaStore），
 * 不依赖厂商文件选择器，OriginOS 阉割版 SAF 也能绕过。
 * 在原子隐私系统内运行时，查询到的就是隐私系统里的视频。
 *
 * 支持两种视图（可切换并记住偏好）：
 * 0 = 详细信息（列表），1 = 大图标（网格）
 */
class AlbumPickerActivity : AppCompatActivity() {

    private data class AlbumItem(
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val folder: String
    )

    /** 一行/一格的控件引用：避免 getView 里反复 findViewById，快速滚动更顺滑 */
    private class AlbumViews(
        val thumb: ImageView,
        val name: TextView,
        val info: TextView,
        val cb: CheckBox
    ) {
        var item: AlbumItem? = null
    }

    private val items = mutableListOf<AlbumItem>()
    private val selected = LinkedHashSet<Uri>()
    private var adapter: AlbumAdapter? = null
    private var viewMode = MODE_LIST

    /** 滚动状态：快速滑动（FLING）时暂停解码缩略图，停下来再补，避免 CPU 争抢掉帧 */
    private var scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE

    private lateinit var tvTitle: TextView
    private lateinit var btnConfirm: Button
    private lateinit var btnViewMode: Button
    private lateinit var tvEmpty: TextView
    private lateinit var listView: ListView
    private lateinit var gridView: GridView

    private val mediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadVideos() else showEmpty()
        }

    private fun onItemClick(position: Int) {
        val item = items.getOrNull(position) ?: return
        if (item.uri in selected) selected.remove(item.uri) else selected.add(item.uri)
        adapter?.notifyDataSetChanged()
        refreshHeader()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album_picker)

        tvTitle = findViewById(R.id.tvAlbumTitle)
        tvEmpty = findViewById(R.id.tvAlbumEmpty)
        btnConfirm = findViewById(R.id.btnAlbumConfirm)
        btnViewMode = findViewById(R.id.btnViewMode)
        listView = findViewById(R.id.listAlbum)
        gridView = findViewById(R.id.gridAlbum)

        viewMode = getSharedPreferences("album", MODE_PRIVATE).getInt(KEY_VIEW_MODE, MODE_LIST)

        listView.setOnItemClickListener { _, _, position, _ -> onItemClick(position) }
        gridView.setOnItemClickListener { _, _, position, _ -> onItemClick(position) }
        btnConfirm.setOnClickListener {
            if (selected.isEmpty()) return@setOnClickListener
            setResult(RESULT_OK, Intent().putParcelableArrayListExtra(EXTRA_URIS, ArrayList(selected)))
            finish()
        }
        btnViewMode.setOnClickListener {
            viewMode = if (viewMode == MODE_LIST) MODE_GRID else MODE_LIST
            getSharedPreferences("album", MODE_PRIVATE)
                .edit().putInt(KEY_VIEW_MODE, viewMode).apply()
            applyMode()
        }

        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadVideos()
        } else {
            runCatching { mediaPermission.launch(perm) }
        }
    }

    private fun applyMode() {
        btnViewMode.text = if (viewMode == MODE_LIST) "▦ 大图" else "☰ 详细"
        adapter = AlbumAdapter(viewMode)
        val scrollListener = object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                scrollState = state
                // 滑动中跳过的缩略图，停下来补齐
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) fillVisibleThumbs()
            }

            override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {}
        }
        listView.setOnScrollListener(scrollListener)
        gridView.setOnScrollListener(scrollListener)
        if (viewMode == MODE_LIST) {
            gridView.isVisible = false
            listView.isVisible = true
            listView.adapter = adapter
        } else {
            listView.isVisible = false
            gridView.isVisible = true
            gridView.adapter = adapter
        }
    }

    private fun showEmpty() {
        tvEmpty.isVisible = true
        listView.isVisible = false
        gridView.isVisible = false
    }

    private fun loadVideos() {
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = mutableListOf<AlbumItem>()
            runCatching {
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATA
                )
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        loaded += AlbumItem(
                            uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                            ),
                            name = c.getString(nameCol) ?: "",
                            durationMs = c.getLong(durCol),
                            folder = File(c.getString(dataCol) ?: "").parent ?: ""
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) {
                items.clear()
                items.addAll(loaded)
                applyMode()
                if (items.isEmpty()) showEmpty() else tvEmpty.isVisible = false
                refreshHeader()
            }
        }
    }

    private fun refreshHeader() {
        tvTitle.text = "已选 ${selected.size} 个"
        btnConfirm.isEnabled = selected.isNotEmpty()
        btnConfirm.text = if (selected.isEmpty()) "添加所选到新分组" else "添加所选（${selected.size} 个）到新分组"
    }

    private fun bindItemView(holder: AlbumViews, item: AlbumItem, grid: Boolean) {
        holder.item = item
        val thumb = holder.thumb
        thumb.tag = item.uri.toString()
        holder.name.text = item.name
        val d = formatDuration(item.durationMs)
        holder.info.text =
            (if (d.isEmpty()) "" else "$d · ") + if (grid) "" else item.folder.ifEmpty { "未知目录" }
        holder.cb.isChecked = item.uri in selected
        loadThumb(holder, item)
    }

    private fun loadThumb(holder: AlbumViews, item: AlbumItem) {
        val key = item.uri.toString()
        Thumbs.get(item.uri)?.let {
            holder.thumb.setImageBitmap(it)
            return
        }
        // 复用的行先清掉上一条视频的封面，快速滚动时才不会显示错图
        holder.thumb.setImageBitmap(null)
        if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING) return
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = Thumbs.load(applicationContext, item.uri) ?: return@launch
            withContext(Dispatchers.Main) {
                if (holder.thumb.tag == key && holder.thumb.isAttachedToWindow) {
                    holder.thumb.setImageBitmap(bmp)
                }
            }
        }
    }

    /** 滑动结束后把当前可见行的缩略图补齐 */
    private fun fillVisibleThumbs() {
        val view = if (viewMode == MODE_LIST) listView else gridView
        for (i in 0 until view.childCount) {
            val holder = view.getChildAt(i)?.tag as? AlbumViews ?: continue
            holder.item?.let { loadThumb(holder, it) }
        }
    }

    private inner class AlbumAdapter(private val mode: Int) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val layout = if (mode == MODE_GRID) R.layout.item_album_grid else R.layout.item_album
            val view = convertView ?: layoutInflater.inflate(layout, parent, false).also {
                it.tag = AlbumViews(
                    it.findViewById(R.id.ivAlbumThumb),
                    it.findViewById(R.id.tvAlbumName),
                    it.findViewById(R.id.tvAlbumInfo),
                    it.findViewById(R.id.cbAlbumPick)
                )
            }
            val holder = view.tag as AlbumViews
            bindItemView(holder, items[position], mode == MODE_GRID)
            return view
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_URIS = "extra_uris"
        private const val KEY_VIEW_MODE = "viewMode"
        private const val MODE_LIST = 0
        private const val MODE_GRID = 1
    }
}
