package com.kai.videostitcher

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.mp4parser.BoxParser
import org.mp4parser.Container
import org.mp4parser.IsoFile
import org.mp4parser.PropertyBoxParserImpl
import org.mp4parser.boxes.iso14496.part12.TrackBox
import org.mp4parser.muxer.Movie
import org.mp4parser.muxer.RandomAccessSource
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.Mp4TrackImpl
import org.mp4parser.muxer.tracks.AppendTrack
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** 全局导出闸门：Transformer 导出（转封装）最多 2 路并发，匹配手机硬件编码器实例数；
 *  mp4parser 无损拼接只吃 IO，不占闸门，可任意并行。 */
private val exportGate = Semaphore(2)

/** 临时文件/目录唯一名：纯毫秒时间戳在多分组并行时会同毫秒撞名，互相删文件 */
private fun uniqueTempName(prefix: String, suffix: String): String =
    "${prefix}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}$suffix"

data class TrackInfo(
    val videoMime: String?,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val fps: Float,
    val audioMime: String?,
    val sampleRate: Int,
    val channels: Int,
    val hasAudio: Boolean
) {
    fun matches(o: TrackInfo): Boolean =
        videoMime == o.videoMime &&
            width == o.width &&
            height == o.height &&
            rotation == o.rotation &&
            hasAudio == o.hasAudio &&
            (!hasAudio || (audioMime == o.audioMime && sampleRate == o.sampleRate && channels == o.channels))
    // 帧率不再要求一致：mp4parser/转封装都按各自的样本时长拼接（输出为可变帧率），
    // 23.976 与 24、不同拍摄帧率这类差异以前会被误判成"必须转码"，现在直接走无损秒拼。
    // 旋转角度必须一致：无损拼接只保留第一个视频的旋转矩阵，角度不同会横竖错乱
}

private fun MediaFormat.floatValue(key: String): Float =
    runCatching { getFloat(key) }
        .recoverCatching { getInteger(key).toFloat() }
        .getOrDefault(0f)

/** media3 Mp4Muxer 支持直封进 MP4 的编码（无损转封装只对这些编码可行） */
private val muxableVideo =
    setOf("video/av01", "video/3gpp", "video/avc", "video/hevc", "video/mp4v-es", "video/x-vnd.on2.vp9", "video/apv", "video/dolby-vision")
private val muxableAudio =
    setOf("audio/mp4a-latm", "audio/3gpp", "audio/amr-wb", "audio/opus", "audio/vorbis", "audio/raw", "audio/iamf")

/** 组内编码能否直接封装进 MP4；探测失败的输入直接判否（MP3/AC3/MPEG-2 等需走转码重编码） */
fun isMp4MuxCompatible(infos: List<TrackInfo>): Boolean = infos.all {
    it.videoMime != null && it.videoMime in muxableVideo &&
        (!it.hasAudio || (it.audioMime != null && it.audioMime in muxableAudio))
}

/** 从异常链中提取 media3 ExportException 的错误码 */
fun exportErrorCode(t: Throwable): Int? {
    var cur: Throwable? = t
    while (cur != null) {
        if (cur is ExportException) return cur.errorCode
        cur = cur.cause
    }
    return null
}

/** 错误码 → 面向用户的分类提示 */
fun errorCodeHint(code: Int?): String = when {
    code == null -> ""
    code == 1000 -> "（错误码 $code：引擎加载不了这个视频。请把它自行转码成普通 MP4" +
        "（如电脑上的格式工厂/剪映）后再导入，或换用其它来源的文件）"
    code == 1001 -> "（错误码 $code：引擎加载不了其中一个视频的轨道。请把这个视频自行转码成" +
        "普通 MP4（如电脑上的格式工厂/剪映）后再导入，或换用其它来源的文件）"
    code in 2000..2999 -> "（错误码 $code：读取视频内容失败——文件可能已损坏、下载不完整或格式特殊。" +
        "请自行转码成普通 MP4 后再试，或换用其它来源的文件）"
    code == 3003 -> "（错误码 $code：手机不支持这个视频的编码格式）"
    code in 3000..3003 -> "（错误码 $code：解码失败，视频编码或规格超出手机能力）"
    code in 4000..4999 -> "（错误码 $code：编码输出失败）"
    code == 5001 -> "（错误码 $code：视频帧处理失败）"
    code == 6001 -> "（错误码 $code：音频处理失败）"
    code == 7001 -> "（错误码 $code：封装成品文件失败）"
    else -> "（错误码 $code）"
}

/** 带错误码与组内编码清单的失败详情，便于定位问题 */
fun failureDetail(t: Throwable, infos: List<TrackInfo>): String {
    val sb = StringBuilder(t.message ?: t.javaClass.simpleName)
    sb.append(errorCodeHint(exportErrorCode(t)))
    // media3 的包装异常会藏住真实检查信息，把内层原因带出来
    var cause = t.cause
    var n = 0
    while (cause != null && n < 2) {
        val m = cause.message
        if (!m.isNullOrBlank() && m != t.message) {
            sb.append("｜原因：").append(m.take(140))
            n++
        }
        cause = cause.cause
    }
    val fmts = infos.filter { it.videoMime != null }
        .map { "${it.videoMime!!.substringAfter('/')} ${it.width}x${it.height}" }
        .distinct()
        .joinToString("、")
    if (fmts.isNotEmpty()) sb.append("｜组内检测：").append(fmts)
    return sb.toString()
}

/**
 * 组内无法无损拼接时的中止提示：说清楚差在哪，并给出可操作的自助建议。
 * 本 App 刻意不做转码——真机上重新编码不可控（实测会产出拉伸、错乱甚至
 * 无法播放的成品，详见 1.3/1.3.1 两版的教训），无损拼接的成品则永远是
 * 原画质拷贝，宁可中止也不产出坏文件。
 */
fun inconsistentAdvice(infos: List<TrackInfo>): String {
    val sb = StringBuilder()
    val unreadable = infos.count { it.videoMime == null }
    if (unreadable > 0) {
        sb.append("有 $unreadable 个视频本机读不动（编码特殊或封装不规范）。")
    } else {
        sb.append("本组视频参数不一致，无法无损拼接。")
        val codecs = infos.map { it.videoMime!!.substringAfter('/').uppercase() }.distinct()
        val dims = infos.map { "${it.width}x${it.height}" }.distinct()
        val rots = infos.map { it.rotation }.distinct()
        val audioSig = infos.map { if (it.hasAudio) "${it.audioMime}/${it.sampleRate}/${it.channels}" else "无声" }.distinct()
        if (codecs.size > 1) sb.append("编码不同（").append(codecs.joinToString("、")).append("）；")
        if (dims.size > 1) sb.append("分辨率不同（").append(dims.joinToString("、")).append("）；")
        if (rots.size > 1) sb.append("拍摄方向不同；")
        if (audioSig.size > 1) sb.append("音频不一致（").append(audioSig.joinToString("、")).append("）；")
    }
    sb.append("请把这些视频自行转码成参数一致的普通 MP4（推荐 H.264+AAC、同一分辨率）后再导入，")
        .append("或按来源分成参数一致的多个分组分别拼接")
    return sb.toString()
}

/**
 * 引擎处理不了的文件不做自动修复也不做转码：直接中止，由错误提示引导用户
 * 自行转码成普通 MP4 后再导入。
 * （1.2 曾内置 MediaExtractor→MediaMuxer 自动修复重封装，1.3 应用户要求回退；
 *  1.4 起进一步移除全部转码路径——真机上转码产出过无法播放/拉伸错乱的成品，
 *  参数不一致的分组一律中止并提示，详见 inconsistentAdvice。）
 */

/**
 * 成品自检：引擎"导出成功"不等于文件能播。真机上各片段由相互独立的硬件
 * 编码会话产出，容器层拼接只保留第一段的解码配置，段间参数集（SPS/PPS/
 * 色彩信息）不一致时整条流会解不动——模拟器软编码器输出一致，掩盖了这类
 * 问题。这里做三道检查：时长接近素材总和、首/中/尾三点能解出画面、
 * 小文件再全量过一遍采样表。返回 null 表示通过，否则返回失败原因。
 */
fun verifyOutputUsable(context: Context, uri: Uri, expectedDurationMs: Long): String? {
    try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return "读不到成品时长"
            if (expectedDurationMs > 0 && durMs < expectedDurationMs * 85 / 100)
                return "成品时长 ${durMs}ms 明显短于素材总时长 ${expectedDurationMs}ms（内容被截断）"
            val hasVideo =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (hasVideo) {
                for (percent in intArrayOf(10, 50, 90)) {
                    val tUs = durMs * 1000L * percent / 100
                    val opts = if (percent == 10) MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    else MediaMetadataRetriever.OPTION_CLOSEST
                    if (mmr.getFrameAtTime(tUs, opts) == null) return "在 ${percent}% 处解不出画面"
                }
            }
        } finally {
            mmr.release()
        }
    } catch (t: Throwable) {
        return "自检异常：${t.message ?: t.javaClass.simpleName}"
    }
    // 采样表全量扫描只对小块文件做（纯 IO 不解码）：超大文件代价高，
    // 且截尾/时长异常已被上面两道检查覆盖
    val sizeBytes = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L)
    if (sizeBytes in 1 until 200L * 1024 * 1024) {
        try {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                val buf = ByteBuffer.allocateDirect(8 * 1024 * 1024)
                for (i in 0 until ex.trackCount) ex.selectTrack(i)
                var count = 0
                while (ex.readSampleData(buf, 0) >= 0) {
                    count++
                    if (!ex.advance()) break
                }
                if (count == 0) return "采样表里没有可读数据"
            } finally {
                ex.release()
            }
        } catch (t: Throwable) {
            return "采样表读取失败：${t.message ?: t.javaClass.simpleName}"
        }
    }
    return null
}

/** 用 MediaExtractor 读取视频的编码/分辨率/帧率/音频参数 */
fun probeVideo(context: Context, uri: Uri): TrackInfo {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        var videoMime: String? = null
        var width = 0
        var height = 0
        var rotation = 0
        var fps = 0f
        var audioMime: String? = null
        var sampleRate = 0
        var channels = 0
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoMime == null) {
                videoMime = mime
                var w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                var h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                rotation =
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rotation == 90 || rotation == 270) {
                    val t = w; w = h; h = t
                }
                width = w
                height = h
                fps = format.floatValue(MediaFormat.KEY_FRAME_RATE)
            } else if (mime.startsWith("audio/") && audioMime == null) {
                audioMime = mime
                sampleRate =
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                channels =
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
            }
        }
        return TrackInfo(videoMime, width, height, rotation, fps, audioMime, sampleRate, channels, audioMime != null)
    } finally {
        extractor.release()
    }
}

/** 基于 ParcelFileDescriptor 的随机访问源，供 mp4parser 懒读取 mdat 中的样本数据 */
private class ChannelRandomAccessSource(private val channel: FileChannel) : RandomAccessSource {
    override fun get(offset: Long, size: Long): ByteBuffer {
        val buffer = ByteBuffer.allocate(size.toInt())
        channel.position(offset)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break
        }
        buffer.flip()
        return buffer
    }

    override fun close() {}
}

/**
 * mp4parser 默认的 PropertyBoxParserImpl 会用系统类加载器读取 JAR 内的
 * isoparser2-default.properties，该资源不会被打进 APK，安卓上必然 NPE。
 * 这里从 assets 读取同一份配置，用 Properties 构造器直接注入。
 */
private var boxParser: BoxParser? = null

fun initBoxParser(context: Context) {
    if (boxParser != null) return
    val props = java.util.Properties()
    context.assets.open("isoparser2-default.properties").use { props.load(it) }
    boxParser = PropertyBoxParserImpl(props)
}

/** 等价于 MovieCreator.build(channel, randomAccessSource, name)，但可注入 BoxParser */
private fun loadMovie(
    context: Context,
    item: VideoItem,
    closeables: MutableList<AutoCloseable>
): Movie {
    val pfd = context.contentResolver.openFileDescriptor(item.uri, "r")!!
    closeables.add(pfd)
    val boxChannel = FileInputStream(pfd.fileDescriptor).channel
    closeables.add(boxChannel)
    val dataChannel = FileInputStream(pfd.fileDescriptor).channel
    closeables.add(dataChannel)
    val parser = boxParser ?: throw IllegalStateException("box parser 未初始化")
    val isoFile = IsoFile(boxChannel, parser)
    val movie = Movie()
    for (trackBox in isoFile.movieBox.getBoxes(TrackBox::class.java)) {
        movie.addTrack(
            Mp4TrackImpl(
                trackBox.trackHeaderBox.trackId,
                isoFile,
                ChannelRandomAccessSource(dataChannel),
                item.name
            )
        )
    }
    movie.matrix = isoFile.movieBox.movieHeaderBox.matrix
    return movie
}

/**
 * 无损拼接：mp4parser 在容器层面把多个 mp4/mov 的音视频轨串起来，
 * 不重新编码，速度快且零画质损失。要求各源参数一致（由调用方先行探测）。
 */
fun concatLossless(context: Context, items: List<VideoItem>, outPfd: ParcelFileDescriptor) {
    val opened = mutableListOf<AutoCloseable>()
    try {
        val movies = items.map { item -> loadMovie(context, item, opened) }
        appendAndWrite(movies, outPfd)
    } finally {
        for (c in opened) runCatching { c.close() }
    }
}

/** 把多个 Movie 追加成单轨并写入输出 */
private fun appendAndWrite(movies: List<Movie>, outPfd: ParcelFileDescriptor) {
    val out = Movie()
    // 保留第一个片段的显示矩阵（片段若带旋转元数据，拼接后必须延续，否则方向丢失）
    movies.firstOrNull()?.let { out.matrix = it.matrix }
    val videos = movies.flatMap { it.tracks }.filter { it.handler == "vide" }
    val audios = movies.flatMap { it.tracks }.filter { it.handler == "soun" }
    require(videos.isNotEmpty() || audios.isNotEmpty()) { "没有可拼接的音视频轨道" }
    if (videos.isNotEmpty()) out.addTrack(AppendTrack(*videos.toTypedArray()))
    if (audios.isNotEmpty()) out.addTrack(AppendTrack(*audios.toTypedArray()))

    val container: Container = DefaultMp4Builder().build(out)
    FileOutputStream(outPfd.fileDescriptor).channel.use { sink ->
        container.writeContainer(sink)
        sink.force(true)
    }
}

/**
 * 无损转封装：参数一致的组（任意容器如 MKV/WebM/TS）用 Transformer 的
 * transmux 模式直接拷贝压缩流，不重新编码。失败时由调用方中止并提示。
 */
suspend fun transmuxConcat(
    context: Context,
    items: List<VideoItem>,
    outUri: Uri,
    onProgress: (Int) -> Unit
) {
    val tmp = File(context.getExternalFilesDir(null), uniqueTempName("转封装临时", ".mp4"))
    try {
        val editedItems = items.map { item ->
            EditedMediaItem.Builder(MediaItem.fromUri(item.uri)).build()
        }
        val sequence = EditedMediaItemSequence.Builder(editedItems).build()
        val composition = Composition.Builder(sequence)
            .setTransmuxAudio(true)
            .setTransmuxVideo(true)
            .build()

        exportComposition(context, composition, tmp, onProgress = onProgress)
        copyTmpToOut(context, tmp, outUri)
    } finally {
        tmp.delete()
    }
}

/**
 * 统一的 Transformer 导出入口：持有全局导出闸门（最多 2 路并发），
 * 在主线程构建/启动 Transformer 并轮询进度。
 */
private suspend fun exportComposition(
    context: Context,
    composition: Composition,
    tmp: File,
    onProgress: (Int) -> Unit
) {
    exportGate.withPermit {
        tmp.delete()
        val finished = CompletableDeferred<Unit>()
        val transformer = withContext(Dispatchers.Main) {
            // 官方 Troubleshooting 建议：芯片上 MediaCodec 偶发卡顿时，默认 10s 的
            // muxer 间隔超时会把慢导出误判为卡死而中止，放宽到 60s
            val t = Transformer.Builder(context)
                .setMaxDelayBetweenMuxerSamplesMs(60_000)
                .build()
            t.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    finished.completeExceptionally(exportException)
                }
            })
            t.start(composition, tmp.absolutePath)
            t
        }

        val holder = ProgressHolder()
        while (!finished.isCompleted) {
            withContext(Dispatchers.Main) {
                runCatching { transformer.getProgress(holder) }
            }
            if (holder.progress in 1..99) onProgress(holder.progress)
            delay(300)
        }
        finished.await()
    }
}

private fun copyTmpToOut(context: Context, tmp: File, outUri: Uri) {
    context.contentResolver.openFileDescriptor(outUri, "rw")!!.use { outPfd ->
        FileOutputStream(outPfd.fileDescriptor).channel.use { dst ->
            FileInputStream(tmp).channel.use { src ->
                val size = src.size()
                dst.transferFrom(src, 0, size)
                // 同一输出条目可能被重试级联写多次，必须截齐，避免旧内容残尾
                dst.truncate(size)
                dst.force(true)
            }
        }
    }
}


/** 在 MediaStore 创建输出条目（相册 Movies/VideoStitcher），自动避免重名覆盖 */
fun createOutputUri(context: Context, desiredName: String): Uri {
    val name = uniqueVideoName(context, desiredName)
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoStitcher")
        }
    }
    return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("无法创建输出文件")
}

private fun uniqueVideoName(context: Context, desired: String): String {
    val existing = mutableSetOf<String>()
    runCatching {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            val idx = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            while (c.moveToNext()) existing.add(c.getString(idx) ?: "")
        }
    }
    if (desired !in existing) return desired
    val base = desired.removeSuffix(".mp4")
    var i = 1
    while ("$base ($i).mp4" in existing) i++
    return "$base ($i).mp4"
}
