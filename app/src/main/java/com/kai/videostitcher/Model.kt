package com.kai.videostitcher

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject

data class VideoItem(val uri: Uri, val name: String, var durationMs: Long, var codec: String = "")

class Group(var name: String, val items: MutableList<VideoItem> = mutableListOf())

/** 视频封面缩略图的内存缓存与加载 */
object Thumbs {
    /** 缩略图最长边：卡片显示足够清晰，又不必解码整帧（4K 整帧一帧 30MB+） */
    private const val MAX_DIM = 512

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(uri: Uri): Bitmap? = cache.get(uri.toString())

    fun get(key: String): Bitmap? = cache.get(key)

    /**
     * 解码指定视频的缩略图：最长边 MAX_DIM、保持原始宽高比。
     * @Synchronized 串行化解码：快速滚动时同一视频不会被并发重复解码，
     * 几十个整帧解码同时跑挤爆 CPU/内存导致相册页卡顿的问题也从根上消除。
     */
    @Synchronized
    fun load(context: Context, uri: Uri): Bitmap? {
        cache.get(uri.toString())?.let { return it }
        // API 29+ 优先取系统缩略图缓存：系统已生成过的直接命中，毫秒级返回
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(MAX_DIM, MAX_DIM), null)
            }.getOrNull()?.let { thumb ->
                cache.put(uri.toString(), thumb)
                return thumb
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_DIM, MAX_DIM
                )
            } else {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    val scale = MAX_DIM.toFloat() / maxOf(full.width, full.height)
                    val small = Bitmap.createScaledBitmap(
                        full,
                        (full.width * scale).toInt().coerceAtLeast(1),
                        (full.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                    if (small != full) full.recycle()
                    small
                }
            } ?: return null
            cache.put(uri.toString(), frame)
            frame
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

object Store {
    private const val PREFS = "groups"

    fun save(context: Context, groups: List<Group>) {
        val arr = JSONArray()
        for (g in groups) {
            val gi = JSONObject()
            gi.put("name", g.name)
            val items = JSONArray()
            for (item in g.items) {
                val o = JSONObject()
                o.put("uri", item.uri.toString())
                o.put("name", item.name)
                o.put("dur", item.durationMs)
                if (item.codec.isNotEmpty()) o.put("codec", item.codec)
                items.put(o)
            }
            gi.put("items", items)
            arr.put(gi)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("data", arr.toString()).apply()
    }

    fun load(context: Context): MutableList<Group> {
        val out = mutableListOf<Group>()
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("data", null) ?: return out
        runCatching {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val gi = arr.getJSONObject(i)
                val g = Group(gi.getString("name"))
                val items = gi.getJSONArray("items")
                for (j in 0 until items.length()) {
                    val o = items.getJSONObject(j)
                    g.items.add(
                        VideoItem(
                            Uri.parse(o.getString("uri")),
                            o.getString("name"),
                            o.optLong("dur"),
                            o.optString("codec")
                        )
                    )
                }
                out.add(g)
            }
        }
        return out
    }
}

/** 自然排序：让 01、02、10 按数字顺序而不是字符串顺序排列 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val si = i
            while (i < a.length && a[i].isDigit()) i++
            val sj = j
            while (j < b.length && b[j].isDigit()) j++
            val na = a.substring(si, i).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(sj, j).trimStart('0').ifEmpty { "0" }
            val c = if (na.length != nb.length) na.length.compareTo(nb.length) else na.compareTo(nb)
            if (c != 0) return c
        } else {
            val c = ca.compareTo(cb)
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mov", "mkv", "webm", "avi", "3gp", "3g2", "m4v", "ts", "m2ts",
    "mts", "flv", "mpg", "mpeg", "vob", "divx"
)

fun isVideoName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

/** 只有 mp4/mov 容器才可能走 mp4parser 无损拼接 */
fun isLosslessCapableName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext == "mp4" || ext == "mov" || ext == "m4v"
}

fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "输出" }

fun queryDisplayName(context: Context, uri: Uri, fallback: String): String {
    runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let { if (it.isNotBlank()) return it }
            }
        }
    }
    return fallback
}

fun probeDuration(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (t: Throwable) {
        0L
    } finally {
        runCatching { retriever.release() }
    }
}

/** 视频 mime → 用户可读编码名（未收录的编码显示原始子类型，仍有辨识价值） */
fun videoCodecDisplay(mime: String?): String = when (mime?.substringAfter('/')) {
    "avc" -> "H.264"
    "hevc" -> "H.265"
    "av01" -> "AV1"
    "mp4v-es" -> "MPEG-4"
    "x-vnd.on2.vp9" -> "VP9"
    "3gpp" -> "H.263"
    "apv" -> "APV"
    "dolby-vision" -> "杜比视界"
    "x-prores" -> "ProRes"
    else -> mime?.substringAfter('/', "") ?: ""
}

/** 音频 mime → 用户可读编码名 */
fun audioCodecDisplay(mime: String?): String = when (mime?.substringAfter('/')) {
    "mp4a-latm" -> "AAC"
    "opus" -> "Opus"
    "mpeg" -> "MP3"
    "ac3" -> "AC3"
    "eac3" -> "E-AC3"
    "amr-wb" -> "AMR-WB"
    "3gpp" -> "AMR"
    "vorbis" -> "Vorbis"
    "raw" -> "PCM"
    "flac" -> "FLAC"
    else -> mime?.substringAfter('/', "") ?: ""
}

/**
 * 导入时给条目生成编码描述：视频编码 分辨率 · 音频编码，
 * 如 "AV1 1072×1920 · AAC"。探测失败返回空串。
 */
fun probeCodecSummary(context: Context, uri: Uri): String {
    val info = runCatching { probeVideo(context, uri) }.getOrNull() ?: return ""
    val parts = mutableListOf<String>()
    if (info.videoMime != null) {
        val v = videoCodecDisplay(info.videoMime)
        parts.add(if (info.width > 0 && info.height > 0) "$v ${info.width}×${info.height}" else v)
    }
    if (info.hasAudio && info.audioMime != null) parts.add(audioCodecDisplay(info.audioMime))
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}
