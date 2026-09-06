package com.kai.videostitcher

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicIntegerArray

/** 全局导出闸门：任何 Transformer 导出（转封装/转码/分段）最多 2 路并发，匹配手机
 *  硬件编码器实例数；mp4parser 无损拼接只吃 IO，不占闸门，可任意并行。 */
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

/** 本机是否有 AV1 (video/av01) 解码器；没有时 AV1 无法参与转码混拼 */
fun hasAv1Decoder(): Boolean = try {
    MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .findDecoderForFormat(MediaFormat.createVideoFormat("video/av01", 1920, 1080)) != null
} catch (t: Throwable) {
    false
}

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
 * 引擎读不动的文件（错误码 1000/1001/2xxx 一类容器层问题）不做自动修复：
 * 转码直接失败中止，由错误提示引导用户自行转码成普通 MP4 后再导入。
 * （1.2 曾内置 MediaExtractor→MediaMuxer 自动修复重封装，1.3 应用户要求回退。）
 */

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

/** 把本地 mp4 文件按顺序无损拼接写入 outPfd（用于分段并行转码的产物合并） */
private fun concatMp4Files(files: List<File>, outPfd: ParcelFileDescriptor) {
    val opened = mutableListOf<AutoCloseable>()
    try {
        val parser = boxParser ?: throw IllegalStateException("box parser 未初始化")
        val movies = files.map { f ->
            val boxChannel = FileInputStream(f).channel
            val dataChannel = FileInputStream(f).channel
            opened.add(boxChannel)
            opened.add(dataChannel)
            val isoFile = IsoFile(boxChannel, parser)
            val movie = Movie()
            for (trackBox in isoFile.movieBox.getBoxes(TrackBox::class.java)) {
                movie.addTrack(
                    Mp4TrackImpl(
                        trackBox.trackHeaderBox.trackId,
                        isoFile,
                        ChannelRandomAccessSource(dataChannel),
                        f.name
                    )
                )
            }
            movie.matrix = isoFile.movieBox.movieHeaderBox.matrix
            movie
        }
        appendAndWrite(movies, outPfd)
    } finally {
        for (c in opened) runCatching { c.close() }
    }
}

/**
 * 无损转封装：参数一致的组（任意容器如 MKV/WebM/TS）用 Transformer 的
 * transmux 模式直接拷贝压缩流，不重新编码。失败时由调用方回退到全转码。
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

        exportComposition(context, composition, tmp, forceAvc = false, forceAac = false, onProgress = onProgress)
        copyTmpToOut(context, tmp, outUri)
    } finally {
        tmp.delete()
    }
}

/**
 * 统一的 Transformer 导出入口：持有全局导出闸门（最多 2 路并发），
 * 在主线程构建/启动 Transformer 并轮询进度。
 * forceAvc/forceAac：转码时固定输出 H264/AAC——media3 的输出编码会跟随输入，
 * AV1 等编码会落到软件编码器上（极慢），固定 H264 永远走硬件编码器且兼容性最好。
 */
private suspend fun exportComposition(
    context: Context,
    composition: Composition,
    tmp: File,
    forceAvc: Boolean,
    forceAac: Boolean,
    onProgress: (Int) -> Unit
) {
    exportGate.withPermit {
        tmp.delete()
        val finished = CompletableDeferred<Unit>()
        val transformer = withContext(Dispatchers.Main) {
            val builder = Transformer.Builder(context)
            if (forceAvc) builder.setVideoMimeType("video/avc")
            if (forceAac) builder.setAudioMimeType("audio/mp4a-latm")
            // 官方 Troubleshooting 建议：芯片上 MediaCodec 偶发卡顿时，默认 10s 的
            // muxer 间隔超时会把慢导出（如软解 4K AV1）误判为卡死而中止，放宽到 60s
            builder.setMaxDelayBetweenMuxerSamplesMs(60_000)
            val t = builder.build()
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

/**
 * 转码拼接兜底：Media3 Transformer 解码后重新编码，能处理参数不一致、
 * 不同容器（mkv/webm 等）的混合输入。输出固定 H264/AAC，高度取组内最小
 * 高度（不超过 1080），HDR 自动压成 SDR。
 * 引擎读不动的文件（错误码 1000/1001/2xxx）不做自动修复：直接失败中止，
 * 由错误提示引导用户自行转码成普通 MP4 后再导入。
 */
suspend fun transcodeConcat(
    context: Context,
    items: List<VideoItem>,
    infos: List<TrackInfo>,
    outUri: Uri,
    onProgress: (Int) -> Unit
): String {
    val targetHeight = (infos.map { it.height }.filter { it > 0 }.minOrNull() ?: 720)
        .coerceAtMost(1080)
    // 组内有的视频有音轨、有的没有时，Transformer 无法混拼，统一去掉音频保成功率
    val mixedAudio = infos.map { it.hasAudio }.distinct().size > 1
    val first = infos.first()
    // 音频参数完全一致且都是 AAC（或整组无声）时直接拷贝音轨，省一整遍音频编解码
    val audioPassthrough = !mixedAudio && infos.all {
        it.audioMime == first.audioMime &&
            it.sampleRate == first.sampleRate &&
            it.channels == first.channels
    } && (first.audioMime == null || first.audioMime == "audio/mp4a-latm")
    val audioUniform = !mixedAudio && infos.all {
        it.audioMime == first.audioMime &&
            it.sampleRate == first.sampleRate &&
            it.channels == first.channels
    }
    val rotationsUniform = infos.all { it.rotation == first.rotation }
    // 分段拼接要求每个片段编码后尺寸完全一致（mp4parser 同一条轨不允许不同宽高）
    val dimsUniform = infos.all { it.width > 0 && it.width == first.width && it.height == first.height }

    // 画布策略：组内显示尺寸或旋转不一致时，所有视频统一等比缩放进同一画布
    //（LAYOUT_SCALE_TO_FIT 保持比例、黑边补齐）。带旋转元数据的视频先显式反向旋转，
    // 把旋转烤进像素——media3 多项序列对旋转元数据的处理不可靠（实测会转置编码方向
    // 又丢掉 displaymatrix，画面横竖颠倒/拉伸，androidx/media#2788 一类），必须烤死。
    val needsCanvas = !dimsUniform || !rotationsUniform
    val effectFor: (Int) -> Effects? = if (needsCanvas) {
        val aspect = if (first.height > 0) first.width.toDouble() / first.height else 16.0 / 9.0
        var canvasH = targetHeight
        if (canvasH % 2 != 0) canvasH -= 1 // 编码器普遍要求宽高为偶数，奇数高度会无法播放
        if (canvasH < 2) canvasH = 2
        var canvasW = (canvasH * aspect).toInt()
        if (canvasW % 2 != 0) canvasW -= 1
        if (canvasW < 2) canvasW = 2
        { i ->
            val info = infos.getOrNull(i)
            val list = mutableListOf<Effect>()
            if (info != null && info.rotation != 0) {
                list.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(-info.rotation.toFloat()).build())
            }
            list.add(Presentation.createForWidthAndHeight(canvasW, canvasH, Presentation.LAYOUT_SCALE_TO_FIT))
            Effects(emptyList(), list)
        }
    } else {
        { i ->
            val h = infos[i].height
            if (h == 0 || h != targetHeight) {
                Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight)))
            } else null
        }
    }

    // 尺寸/旋转一致时按视频逐个"分段并行转码"（2 路硬件编码器同时跑）再无损拼接，
    // 提速约一半；混合尺寸/旋转组走整组单路导出（统一画布）。音频参数不一致
    //（如 MP3 混 AAC）时片段音轨无法无损相接，同样回退整组单路。
    if (items.size >= 2 && dimsUniform && rotationsUniform && (mixedAudio || audioUniform)) {
        try {
            android.util.Log.i("VideoStitcher", "分段并行转码 ${items.size} 段")
            return transcodeParallelSegments(
                context, items, infos, effectFor, mixedAudio, audioPassthrough, outUri, onProgress
            )
        } catch (t: Throwable) {
            android.util.Log.e("VideoStitcher", "分段并行转码失败，回退单路转码", t)
        }
    }
    return transcodeSingle(context, items, infos, effectFor, mixedAudio, audioPassthrough, outUri, onProgress)
}

/** 单路转码：整组一次 Transformer 导出（effectFor 决定每个视频的缩放效果） */
private suspend fun transcodeSingle(
    context: Context,
    items: List<VideoItem>,
    infos: List<TrackInfo>,
    effectFor: (Int) -> Effects?,
    mixedAudio: Boolean,
    audioPassthrough: Boolean,
    outUri: Uri,
    onProgress: (Int) -> Unit
): String {
    val tmp = File(context.getExternalFilesDir(null), uniqueTempName("转码临时", ".mp4"))
    try {
        suspend fun buildComposition(passthroughAudio: Boolean): Composition {
            val editedItems = items.mapIndexed { index, item ->
                val builder = EditedMediaItem.Builder(MediaItem.fromUri(item.uri))
                effectFor(index)?.let { builder.setEffects(it) }
                if (mixedAudio) builder.setRemoveAudio(true)
                builder.build()
            }
            return Composition.Builder(EditedMediaItemSequence.Builder(editedItems).build())
                .apply { if (passthroughAudio) setTransmuxAudio(true) }
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
        }
        try {
            exportComposition(
                context, buildComposition(audioPassthrough), tmp,
                forceAvc = true, forceAac = !audioPassthrough && !mixedAudio, onProgress = onProgress
            )
        } catch (t: Throwable) {
            if (!audioPassthrough) throw t
            android.util.Log.e("VideoStitcher", "音频直通导出失败，改回音频重编码重试", t)
            exportComposition(
                context, buildComposition(false), tmp,
                forceAvc = true, forceAac = !mixedAudio, onProgress = onProgress
            )
        }
        copyTmpToOut(context, tmp, outUri)
    } finally {
        tmp.delete()
    }
    return if (mixedAudio) "（注：组内部分视频没有声音，成品已去掉全部音轨）" else ""
}

/**
 * 分段并行转码：每个视频单独转成参数一致的片段（exportGate 限 2 路并发，
 * 吃满两路硬件编码器），全部完成后用 mp4parser 在容器层无损拼接成成品。
 */
private suspend fun transcodeParallelSegments(
    context: Context,
    items: List<VideoItem>,
    infos: List<TrackInfo>,
    effectFor: (Int) -> Effects?,
    mixedAudio: Boolean,
    audioPassthrough: Boolean,
    outUri: Uri,
    onProgress: (Int) -> Unit
): String {
    val segDir = File(context.getExternalFilesDir(null), uniqueTempName("分段", ""))
    segDir.mkdirs()
    try {
        val progresses = AtomicIntegerArray(items.size)
        coroutineScope {
            items.mapIndexed { index, item ->
                launch(Dispatchers.IO) {
                    val builder = EditedMediaItem.Builder(MediaItem.fromUri(item.uri))
                    effectFor(index)?.let { builder.setEffects(it) }
                    if (mixedAudio) builder.setRemoveAudio(true)
                    val composition = Composition.Builder(
                        EditedMediaItemSequence.Builder(listOf(builder.build())).build()
                    )
                        .apply { if (audioPassthrough) setTransmuxAudio(true) }
                        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                        .build()
                    val segFile = File(segDir, "seg_$index.mp4")
                    exportComposition(
                        context, composition, segFile,
                        forceAvc = true, forceAac = !audioPassthrough && !mixedAudio
                    ) { p ->
                        progresses.set(index, p)
                        var sum = 0
                        for (i in 0 until items.size) sum += progresses.get(i)
                        onProgress(sum / items.size)
                    }
                }
            }
        }
        val segFiles = items.indices.map { File(segDir, "seg_$it.mp4") }
        // 片段在容器层无损拼接成成品（纯 IO，无二次转封装开销）
        context.contentResolver.openFileDescriptor(outUri, "rw")!!.use { outPfd ->
            concatMp4Files(segFiles, outPfd)
        }
    } finally {
        segDir.deleteRecursively()
    }
    return if (mixedAudio) "（注：组内部分视频没有声音，成品已去掉全部音轨）" else ""
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
