package com.kai.videostitcher

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.mp4parser.BoxParser
import org.mp4parser.Container
import org.mp4parser.IsoFile
import org.mp4parser.PropertyBoxParserImpl
import org.mp4parser.boxes.iso14496.part12.TrackBox
import org.mp4parser.boxes.iso14496.part15.AvcConfigurationBox
import org.mp4parser.boxes.iso14496.part15.HevcConfigurationBox
import org.mp4parser.boxes.sampleentry.VisualSampleEntry
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 全局导出闸门：Transformer 导出（转封装）最多 2 路并发，匹配手机硬件编码器实例数；
 *  mp4parser 无损拼接只吃 IO，不占闸门，可任意并行。 */
private val exportGate = Semaphore(2)

/** 临时文件/目录唯一名：纯毫秒时间戳在多分组并行时会同毫秒撞名，互相删文件 */
fun uniqueTempName(prefix: String, suffix: String): String =
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
    val hasAudio: Boolean,
    val profile: Int = -1,
    val hdr: Boolean = false,
    val csd: ByteArray? = null,
    /** 非音视频轨数量：Android 13+ 录屏的 mett 时间元数据轨、字幕轨等 */
    val extraTracks: Int = 0
) {
    /** 视频流参数是否一致（不含音频）：解码配置字节（csd-0 = avcC/hvcC）一致时，
     *  profile/level/VUI 全部涵盖，比逐字段比对比更严也更快 */
    fun matchesVideoOnly(o: TrackInfo): Boolean =
        videoMime == o.videoMime &&
            width == o.width &&
            height == o.height &&
            rotation == o.rotation &&
            (profile < 0 || o.profile < 0 || profile == o.profile) &&
            hdr == o.hdr &&
            csdEquals(this, o)

    fun matches(o: TrackInfo): Boolean =
        matchesVideoOnly(o) &&
            hasAudio == o.hasAudio &&
            (!hasAudio || (audioMime == o.audioMime && sampleRate == o.sampleRate && channels == o.channels))
    // 帧率不再要求一致：mp4parser/转封装都按各自的样本时长拼接（输出为可变帧率），
    // 23.976 与 24、不同拍摄帧率这类差异以前会被误判成"必须转码"，现在直接走无损秒拼。
    // 旋转角度必须一致：无损拼接只保留第一个视频的旋转矩阵，角度不同会横竖错乱

    private fun csdEquals(a: TrackInfo, b: TrackInfo): Boolean =
        a.csd == null || b.csd == null || a.csd.contentEquals(b.csd)
    // csd 缺失时退回字段比对（个别容器不吐 csd-0），不因拿不到字节而误判不一致
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
fun verifyOutputUsable(
    context: Context,
    uri: Uri,
    expectedDurationMs: Long,
    checkPointsMs: List<Long> = emptyList()
): String? {
    try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return "读不到成品时长"
            if (durMs <= 0) return "成品时长为 ${durMs}ms"
            if (expectedDurationMs > 0 && durMs < expectedDurationMs * 85 / 100)
                return "成品时长 ${durMs}ms 明显短于素材总时长 ${expectedDurationMs}ms（内容被截断）"
            val hasVideo =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (hasVideo) {
                // 采样点默认 10/50/90%；传入分段中点时逐段覆盖，4 段以上分组
                // 的中间段不再漏检
                val points = if (checkPointsMs.isEmpty())
                    listOf(durMs * 10 / 100, durMs / 2, durMs * 90 / 100)
                else checkPointsMs
                for ((idx, tMs) in points.withIndex()) {
                    val tUs = tMs.coerceIn(0, durMs - 100).coerceAtLeast(0) * 1000
                    val opts = if (idx == 0) MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    else MediaMetadataRetriever.OPTION_CLOSEST
                    if (mmr.getFrameAtTime(tUs, opts) == null)
                        return "在 ${tMs / 1000.0}s 处解不出画面"
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
        var profile = -1
        var hdr = false
        var csd: ByteArray? = null
        var extraTracks = 0
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null) {
                extraTracks++
                continue
            }
            if (mime.startsWith("video/") && videoMime == null) {
                videoMime = mime
                var w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                var h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                rotation =
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
                // 个别来源吐 -90，与 270 是同一个方向，归一到 0/90/180/270 再比较，
                // 否则语义相同的两条竖拍视频会被误判为"方向不一致"
                rotation = ((rotation % 360) + 360) % 360
                if (rotation == 90 || rotation == 270) {
                    val t = w; w = h; h = t
                }
                width = w
                height = h
                fps = format.floatValue(MediaFormat.KEY_FRAME_RATE)
                profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else -1
                val transfer =
                    if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else -1
                hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
                // csd-0 = avcC/hvcC 解码配置字节：逐字节比对是"能否直接容器级拼接"
                // 的最严闸门，涵盖 profile/level/VUI 等所有字段比对覆盖不到的差异
                csd = format.getByteBuffer("csd-0")?.let { buf ->
                    val dup = buf.duplicate()
                    ByteArray(dup.remaining()).also { dup.get(it) }
                }
            } else if (mime.startsWith("audio/") && audioMime == null) {
                audioMime = mime
                sampleRate =
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                channels =
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
            } else if (!mime.startsWith("video/") && !mime.startsWith("audio/")) {
                extraTracks++
            }
        }
        return TrackInfo(videoMime, width, height, rotation, fps, audioMime, sampleRate, channels, audioMime != null, profile, hdr, csd, extraTracks)
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

/** 等价于 MovieCreator.build(channel, randomAccessSource, name)，但可注入 BoxParser。
 *  uri 同时支持 SAF content:// 与本地 file://（新引擎拼接重编后的音频段用） */
private fun loadMovie(
    context: Context,
    uri: Uri,
    name: String,
    closeables: MutableList<AutoCloseable>
): Movie {
    val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
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
                name
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
        val movies = items.map { item -> loadMovie(context, item.uri, item.name, opened) }
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

/* ---------- 第三级：ffmpeg 转码兜底（v1.5） ----------
 * 1.2~1.3.1 真机转码翻车的根因是"多项序列 + 硬件编码会话差异"：media3 对旋转元数据
 * 处理不可靠、各硬件会话 SPS/PPS 有细微差异。这里换成 ffmpeg 逐段独立软编（x264 全机型
 * 行为一致）、旋转用 transpose 亲手烤进像素、拼前再做 SPS/PPS 字节级比对——不确定点
 * 全部变成确定性步骤，最坏情况是中止，不产出坏文件。 */

/** ffmpeg-kit fork 只发布 arm64-v8a/x86_64 的 native 库；32 位设备调用会 UnsatisfiedLinkError，必须先守卫 */
fun ffmpegAvailable(): Boolean =
    Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

/** 转码目标统一为 H.264 High + AAC，固定 GOP；全组输出参数一致是拼前闸门能过的前提。
 *  superfast 比 veryfast 快约 1.5 倍；同 CRF 下档位越低压缩效率越差，
 *  crf 19 补偿观感（与 veryfast/crf20 基本持平），代价是文件略大（约三成） */
private const val TRANSCODE_VIDEO =
    "-c:v libx264 -preset superfast -crf 19 -pix_fmt yuv420p -profile:v high -g 60"
private const val TRANSCODE_AUDIO = "-c:a aac -ar 48000 -ac 2 -b:a 192k"

/** 转码视频编码参数（v1.7 起可选 H.264/H.265/AV1）。x265 同等观感 crf 比 x264 高约 5；
 *  svtav1 preset 8 兼顾速度与压缩率，crf 32 与 x264 crf 19 观感相近 */
private fun transcodeVideoOpts(codec: String): String = when (codec) {
    // bframes=0：部分解码器（含模拟器 SW HEVC 解码）对 x265 B 帧流 seek 解码不稳，
    // 关掉换取兼容性（体积略增）；hvc1 = 参数集只放 hvcC，iOS/微信兼容
    "h265" -> "-c:v libx265 -preset superfast -crf 24 -pix_fmt yuv420p -tag:v hvc1 -g 60 -x265-params bframes=0"
    "av1" -> av1VideoOpts()
    else -> TRANSCODE_VIDEO
}

/** AV1 编码器探测：优先 libsvtav1（快），退 libaom-av1（慢），都无则回退 H.264。
 *  ffmpeg -encoders 只查一次并缓存（结果进程内不变） */
@Volatile
private var av1EncoderArg: String? = null

private fun av1VideoOpts(): String {
    av1EncoderArg?.let { return it }
    val arg = runCatching {
        val session = FFmpegKit.execute("-hide_banner -encoders")
        val out = session.allLogsAsString ?: ""
        when {
            out.contains("libsvtav1") ->
                "-c:v libsvtav1 -preset 8 -crf 32 -pix_fmt yuv420p -g 60"
            out.contains("libaom-av1") ->
                "-c:v libaom-av1 -crf 30 -cpu-used 6 -row-mt 1 -pix_fmt yuv420p -g 60"
            else -> TRANSCODE_VIDEO
        }
    }.getOrDefault(TRANSCODE_VIDEO)
    av1EncoderArg = arg
    return arg
}

/** AV1 编码是否可用（设置界面展示用）；探测结果与 av1VideoOpts 共享缓存 */
fun av1EncodeAvailable(): Boolean =
    av1VideoOpts() != TRANSCODE_VIDEO

/**
 * 转码输出设置（仅转码组生效，无损路径不重编码不受影响）。
 * shortEdge：输出短边上限（0=保持原尺寸；1080/720=把超过限制的源缩到该短边）。
 * codecMode："auto"=跟随组内源编码（混合时按总时长最多者，平票取 H.264），
 * 或固定 "h264"/"h265"/"av1"。
 */
data class TranscodeSettings(val shortEdge: Int, val codecMode: String)

fun loadTranscodeSettings(context: Context): TranscodeSettings {
    val p = context.getSharedPreferences("output_settings", Context.MODE_PRIVATE)
    // 旧版本只存 h265 布尔：迁移成 codecMode 后移除旧键
    if (p.contains("h265")) {
        val mode = if (p.getBoolean("h265", false)) "h265" else "h264"
        saveTranscodeSettings(context, TranscodeSettings(p.getInt("shortEdge", 0), mode))
    }
    return TranscodeSettings(
        p.getInt("shortEdge", 0).coerceIn(0, 2160),
        p.getString("codecMode", "auto") ?: "auto"
    )
}

fun saveTranscodeSettings(context: Context, settings: TranscodeSettings) {
    context.getSharedPreferences("output_settings", Context.MODE_PRIVATE).edit()
        .putInt("shortEdge", settings.shortEdge)
        .putString("codecMode", settings.codecMode)
        .remove("h265")
        .apply()
}

/** 源编码 mime → 输出编码名（自动模式下参与计数）；不在表里的编码视作 H.264 */
private fun outputCodecOf(mime: String?): String = when (mime) {
    "video/hevc" -> "h265"
    "video/av01" -> "av1"
    else -> "h264"
}

/* ---------- 部分转码引擎（1.9.3）：只转码异编码段，其余原样拷贝 ----------
 * 混合 H.264/H.265 但分辨率/拍摄方向一致的组，旧引擎把整组都重新编码——
 * H.264 段明明不用动也跟着转，白费时间还损画质。这里按目标编码（按时长多数）
 * 逐段判断：编码相同的段视频像素一个字节不动（仅音频统一重编 AAC），
 * 不同的段才转码，最后 ffmpeg 容器级拼接 + 成品自检。 */

/** 能否走部分转码：全组可读、只含 H.264/H.265、分辨率一致、无旋转元数据、
 *  非 HDR、且分辨率压缩设置不会被违反（拷贝段无法重采样缩尺寸） */
fun partialTranscodeEligible(infos: List<TrackInfo>, shortEdge: Int): Boolean {
    if (infos.isEmpty() || infos.any { it.videoMime == null }) return false
    if (infos.any { it.videoMime != "video/avc" && it.videoMime != "video/hevc" }) return false
    val first = infos.first()
    if (first.width <= 0 || first.height <= 0) return false
    // 旋转非 0 的组：拷贝段只能靠元数据携带方向、转码段把方向烤进像素，
    // 两者混拼成品方向错乱，只能全量转码
    if (infos.any { it.width != first.width || it.height != first.height || it.rotation != 0 || it.hdr }) return false
    if (shortEdge > 0 && minOf(first.width, first.height) > shortEdge) return false
    return true
}

/** 单段能否视频直拷：编码等于目标，且 profile 是主流档位（避免 10bit/特殊档
 *  和转码段的 8bit 主档混流播放出问题，这类段宁多转不冒险）。
 *  注意 MediaFormat.KEY_PROFILE 报的是 CodecProfileLevel 常量值，不是 Annex-A
 *  的 profile_idc：AVC Baseline/Main/High = 1/2/8，HEVC Main/Main10 = 1/2 */
private fun canCopySegment(info: TrackInfo, codec: String): Boolean {
    if (outputCodecOf(info.videoMime) != codec) return false
    return when (codec) {
        "h265" -> info.profile == -1 || info.profile == 1            // HEVCProfileMain；Main10(2) 等 10bit 档不拷
        "h264" -> info.profile == -1 || info.profile in intArrayOf(1, 2, 8)  // AVCProfileBaseline/Main/High
        else -> false
    }
}

/** 视频直拷段：像素不动，音频统一重编成 AAC（与转码段一致），无声段补静音 */
private suspend fun videoCopySegment(
    context: Context,
    item: VideoItem,
    info: TrackInfo,
    out: File,
    onSegProgress: (Int) -> Unit
) {
    val input = FFmpegKitConfig.getSafParameterForRead(context, item.uri)
    val cmd = if (info.hasAudio)
        "-y -i $input -map 0:v:0 -map 0:a:0 -c:v copy $TRANSCODE_AUDIO -sn -dn '${out.absolutePath}'"
    else
        "-y -i $input -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 " +
            "-map 0:v:0 -map 1:a -shortest -c:v copy $TRANSCODE_AUDIO -sn -dn '${out.absolutePath}'"
    runFfmpeg(cmd, item.durationMs, onSegProgress)
}

/**
 * 部分转码拼接：目标编码段直拷、异编码段走 transcodeSegment（含硬解失败退软解），
 * 全部音频统一 AAC，ffmpeg concat -c copy 容器级拼接（各段解码配置不同，
 * mp4parser 单一 stsd 会写坏，必须走 ffmpeg 的多采样条目），拼后成品自检。
 * 返回 (输出编码名, 无损拷贝的段数)。失败由调用方回退全量转码。
 */
suspend fun partialTranscodeConcat(
    context: Context,
    items: List<VideoItem>,
    infos: List<TrackInfo>,
    outUri: Uri,
    onProgress: (String) -> Unit
): Pair<String, Int> {
    exportGate.withPermit {
        val settings = loadTranscodeSettings(context)
        val codec = resolveOutputCodec(infos, items.map { it.durationMs }, settings)
        val target = infos.first()
        val targetW = target.width
        val targetH = target.height
        val targetFps = target.fps.takeIf { it > 1f } ?: 30f
        val cacheDir = context.cacheDir
        val segments = mutableListOf<File>()
        var copiedCount = 0
        try {
            for ((i, item) in items.withIndex()) {
                val info = infos[i]
                val seg = File(cacheDir, uniqueTempName("混合段", ".mp4"))
                segments += seg
                val progress: (Int) -> Unit = { pct ->
                    onProgress("快速拼接中 第 ${i + 1}/${items.size} 段（$pct%）")
                }
                if (canCopySegment(info, codec)) {
                    copiedCount++
                    onProgress("快速拼接中 第 ${i + 1}/${items.size} 段（原样保留）…")
                    videoCopySegment(context, item, info, seg, progress)
                } else {
                    onProgress("快速拼接中 第 ${i + 1}/${items.size} 段（转码为${if (codec == "h265") "H.265" else "H.264"}）…")
                    val hw = hwDecoderFor(info.videoMime)
                    try {
                        transcodeSegment(context, item, info, targetW, targetH, targetFps, codec, seg, hw, progress)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        if (hw == null) throw t
                        android.util.Log.w(
                            "VideoStitcher",
                            "hw decode ($hw) failed for ${item.name}, falling back to software",
                            t
                        )
                        onProgress("快速拼接中 第 ${i + 1}/${items.size} 段（硬解无响应/失败，改用软解重试）…")
                        transcodeSegment(context, item, info, targetW, targetH, targetFps, codec, seg, null, progress)
                    }
                }
            }
            // 拼接：各段解码配置（不同相机的 SPS/PPS + 转码段的新参数集）互不相同，
            // mp4parser 只保留第一段的采样条目会产出解不动的成品，必须走 ffmpeg
            // concat 的多采样条目路径；拼后有成品自检兜底
            onProgress("无损拼接中…")
            val outTmp = File(cacheDir, uniqueTempName("拼接成品", ".mp4"))
            try {
                ffmpegConcatCopy(segments, outTmp)
                copyTmpToOut(context, outTmp, outUri)
            } finally {
                outTmp.delete()
            }
            val expectedDurationMs = items.sumOf { it.durationMs }
            verifyOutputUsable(context, outUri, expectedDurationMs, segmentCheckPoints(items))?.let { reason ->
                throw IllegalStateException("自检未通过：$reason")
            }
            return codec to copiedCount
        } finally {
            segments.forEach { runCatching { it.delete() } }
        }
    }
}

/** "auto" 模式解析组内实际输出编码：按总时长少数服从多数——哪种编码的素材
 *  总时长最长就输出谁（个数多但都是几秒的零碎小片段不再带偏输出）；平票取
 *  更低世代（H.264 低于 H.265 低于 AV1——老设备兼容面大、软解省电） */
fun resolveOutputCodec(
    infos: List<TrackInfo>,
    durationsMs: List<Long>,
    settings: TranscodeSettings
): String {
    if (settings.codecMode != "auto") return settings.codecMode
    val totals = HashMap<String, Long>()
    infos.forEachIndexed { i, info ->
        val codec = outputCodecOf(info.videoMime)
        totals[codec] = (totals[codec] ?: 0L) + durationsMs.getOrElse(i) { 0L }
    }
    val max = totals.values.maxOrNull() ?: return "h264"
    val winners = totals.filterValues { it == max }.keys
    return when {
        "h264" in winners -> "h264"
        "h265" in winners -> "h265"
        else -> "av1"
    }
}

/** 源编码 → MediaCodec 硬解码器（fork 只带硬解不带硬编）。解码不影响输出码流
 *  的确定性；个别机型/内容硬解失败时自动退回软解重跑 */
private fun hwDecoderFor(mime: String?): String? = when (mime) {
    "video/avc" -> "h264_mediacodec"
    "video/hevc" -> "hevc_mediacodec"
    "video/x-vnd.on2.vp9" -> "vp9_mediacodec"
    "video/av01" -> "av1_mediacodec"
    else -> null
}

private fun trimFps(fps: Float): String =
    // 必须 Locale.US：逗号小数点地区（德/法/西等）默认格式化出 "30,000"，
    // ffmpeg 滤镜图解析直接失败
    String.format(Locale.US, "%.3f", fps).trimEnd('0').trimEnd('.').ifEmpty { "30" }

/**
 * 第三级引擎：逐段独立转码成统一参数（目标取第一段的显示分辨率/帧率，横竖混向按
 * 各自 rotation 烤平），再容器级拼接。HDR→SDR 色调映射暂未做（已知限制，直转偏淡）。
 * 返回实际使用的视频编码名（"h264"/"h265"/"av1"，供完成文案展示）。
 */
suspend fun transcodeConcat(
    context: Context,
    items: List<VideoItem>,
    infos: List<TrackInfo>,
    outUri: Uri,
    onProgress: (String) -> Unit
): String {
    exportGate.withPermit {
        val settings = loadTranscodeSettings(context)
        // 时长在拼前已逐项补探过（mergeGroup），这里直接按素材时长加权选编码
        val codec = resolveOutputCodec(infos, items.map { it.durationMs }, settings)
        val target = infos.first()
        var targetW = target.width.takeIf { it > 0 } ?: 1920
        var targetH = target.height.takeIf { it > 0 } ?: 1080
        // 分辨率压缩：按"短边"限制（1080p=短边1080、720p=短边720），保持宽高比，
        // 宽高取偶（编码器要求）。无损组不受此设置影响（不走这里）
        if (settings.shortEdge > 0) {
            val short = minOf(targetW, targetH)
            if (short > settings.shortEdge) {
                val scale = settings.shortEdge.toFloat() / short
                targetW = (targetW * scale).toInt() / 2 * 2
                targetH = (targetH * scale).toInt() / 2 * 2
            }
        }
        val targetFps = target.fps.takeIf { it > 1f } ?: 30f
        val cacheDir = context.cacheDir
        val segments = mutableListOf<File>()
        try {
            for ((i, item) in items.withIndex()) {
                val seg = File(cacheDir, uniqueTempName("转码段", ".mp4"))
                segments += seg
                onProgress("自动转码中 第 ${i + 1}/${items.size} 段…")
                val progress: (Int) -> Unit = { pct ->
                    onProgress("自动转码中 第 ${i + 1}/${items.size} 段（$pct%）")
                }
                val hw = hwDecoderFor(infos[i].videoMime)
                try {
                    transcodeSegment(
                        context, item, infos[i], targetW, targetH, targetFps,
                        codec, seg, hw, progress
                    )
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    if (hw == null) throw t
                    // 硬解不可用（机型不支持该编码/内容特殊/会话紧张/被进度看门狗
                    // 掐掉）：退软解重跑，个别 AV1 流卡死硬解就靠这一步救回来
                    android.util.Log.w(
                        "VideoStitcher",
                        "hw decode ($hw) failed for ${item.name}, falling back to software",
                        t
                    )
                    onProgress("自动转码中 第 ${i + 1}/${items.size} 段（硬解无响应/失败，改用软解重试）…")
                    transcodeSegment(
                        context, item, infos[i], targetW, targetH, targetFps,
                        codec, seg, null, progress
                    )
                }
            }
            val outTmp = File(cacheDir, uniqueTempName("转码成品", ".mp4"))
            try {
                // 拼前参数集闸门：H.264 输出走"各段 avcC 的 SPS/PPS 字节一致才用
                // mp4parser 追加，不一致退 ffmpeg concat"；H.265/AV1 一律 ffmpeg
                // concat——实测 mp4parser 克隆 hvc1 条目会丢 hvcC、写空 stss（成品
                // 解不出画面），av01 采样条目更是解析直接抛异常，宁绕远路不硬拼
                if (codec == "h264") {
                    val paramSets = segments.map { readParameterSets(it) }
                    val uniformConfig = paramSets.all { it.isEmpty() } ||
                        (paramSets.none { it.isEmpty() } && paramSets.all { listsEqual(it, paramSets.first()) })
                    if (uniformConfig) {
                        val midItems = segments.map { VideoItem(Uri.fromFile(it), it.name, 0) }
                        ParcelFileDescriptor.open(
                            outTmp,
                            ParcelFileDescriptor.MODE_READ_WRITE or
                                ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE
                        ).use { pfd ->
                            concatLossless(context, midItems, pfd)
                        }
                    } else {
                        ffmpegConcatCopy(segments, outTmp)
                    }
                } else {
                    ffmpegConcatCopy(segments, outTmp)
                }
                copyTmpToOut(context, outTmp, outUri)
            } finally {
                outTmp.delete()
            }
            val expectedDurationMs = items.sumOf { it.durationMs }
            verifyOutputUsable(context, outUri, expectedDurationMs)?.let { reason ->
                throw IllegalStateException("自检未通过：$reason")
            }
            return codec
        } finally {
            segments.forEach { runCatching { it.delete() } }
        }
    }
}

/** 自检采样点：每个分段的中点（超过 12 段时均匀抽样），比固定 10/50/90% 覆盖全面 */
fun segmentCheckPoints(items: List<VideoItem>): List<Long> {
    val pts = mutableListOf<Long>()
    var acc = 0L
    for (item in items) {
        if (item.durationMs > 0) {
            pts.add(acc + item.durationMs / 2)
            acc += item.durationMs
        }
    }
    if (pts.size <= 12) return pts
    val step = pts.size.toDouble() / 12
    return (0 until 12).map { pts[(it * step).toInt()] }
}

/**
 * 视频无损 + 音频重编引擎（1.5.4 新增）：视频参数完全一致（含 csd 解码配置字节）
 * 但音频不一致的组——视频流一个字节不动（mp4parser 容器级追加），音频逐段单独
 * 重编成统一 AAC（秒级）或为无声段生成等长静音轨，最后两轨合并。
 * "录屏（无声）混相机（有声）""44.1k 混 48k"这类常见组从分钟级转码降到秒级，
 * 且视频零画质损失。
 */
suspend fun videoCopyConcatAudio(
    context: Context,
    items: List<VideoItem>,
    infos: List<TrackInfo>,
    outUri: Uri,
    onProgress: (String) -> Unit
) {
    exportGate.withPermit {
        val cacheDir = context.cacheDir
        val audioFiles = mutableListOf<File>()
        try {
            for ((i, item) in items.withIndex()) {
                val af = File(cacheDir, uniqueTempName("音频段", ".m4a"))
                audioFiles.add(af)
                onProgress("音频重编中 第 ${i + 1}/${items.size} 段…")
                val input = FFmpegKitConfig.getSafParameterForRead(context, item.uri)
                if (infos[i].hasAudio) {
                    // 只重编音频（-vn 跳过视频解码），秒级完成
                    runFfmpeg("-y -i $input -vn $TRANSCODE_AUDIO '${af.absolutePath}'", 0, {})
                } else {
                    // 无声段生成等长静音轨，保证成品音轨连续
                    val secs = trimFps((if (item.durationMs > 0) item.durationMs else 1000L) / 1000f)
                    runFfmpeg(
                        "-y -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 -t $secs $TRANSCODE_AUDIO '${af.absolutePath}'",
                        0, {}
                    )
                }
            }
            onProgress("视频无损拼接中…")
            context.contentResolver.openFileDescriptor(outUri, "rw")!!.use { pfd ->
                val opened = mutableListOf<AutoCloseable>()
                try {
                    val out = Movie()
                    val videoMovies = items.map { loadMovie(context, it.uri, it.name, opened) }
                    out.matrix = videoMovies.first().matrix
                    val videoTracks = videoMovies.flatMap { it.tracks }.filter { it.handler == "vide" }
                    val audioMovies = audioFiles.map { loadMovie(context, Uri.fromFile(it), it.name, opened) }
                    val audioTracks = audioMovies.flatMap { it.tracks }.filter { it.handler == "soun" }
                    require(videoTracks.isNotEmpty()) { "没有可拼接的视频轨道" }
                    out.addTrack(AppendTrack(*videoTracks.toTypedArray()))
                    if (audioTracks.isNotEmpty()) out.addTrack(AppendTrack(*audioTracks.toTypedArray()))
                    val container: Container = DefaultMp4Builder().build(out)
                    FileOutputStream(pfd.fileDescriptor).channel.use { sink ->
                        container.writeContainer(sink)
                        sink.force(true)
                    }
                } finally {
                    for (c in opened) runCatching { c.close() }
                }
            }
            val expectedDurationMs = items.sumOf { it.durationMs }
            verifyOutputUsable(context, outUri, expectedDurationMs, segmentCheckPoints(items))?.let { reason ->
                throw IllegalStateException("自检未通过：$reason")
            }
        } finally {
            audioFiles.forEach { runCatching { it.delete() } }
        }
    }
}

private fun listsEqual(a: List<ByteArray>, b: List<ByteArray>): Boolean =
    a.size == b.size && a.zip(b).all { (x, y) -> x.contentEquals(y) }

/**
 * 剥离外来轨（v1.5.7）：Android 13+ 录屏的 MP4 带两条 mett 时间元数据轨，字幕轨/
 * 时间码轨同类——mp4parser 解析到非媒体采样条目直接断言崩溃，media3 转封装产出
 * 0 时长成品，ffmpeg 的解码链也会被数据流带崩。-c copy 重封装只留视频+音频，
 * 不重编码秒级完成；失败按 false 返回，引擎链按老路走（不会更糟）。
 */
suspend fun stripForeignTracks(context: Context, uri: Uri, out: File): Boolean {
    val input = FFmpegKitConfig.getSafParameterForRead(context, uri)
    return try {
        runFfmpeg(
            "-y -i $input -map 0:v -map 0:a? -c copy '${out.absolutePath}'", 0, {}
        )
        true
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        android.util.Log.w("VideoStitcher", "strip foreign tracks failed", t)
        false
    }
}

private suspend fun transcodeSegment(
    context: Context,
    item: VideoItem,
    info: TrackInfo,
    targetW: Int,
    targetH: Int,
    targetFps: Float,
    codec: String,
    out: File,
    hwDecoder: String?,
    onSegProgress: (Int) -> Unit
) {
    val input = FFmpegKitConfig.getSafParameterForRead(context, item.uri)
    // 旋转交给 ffmpeg 的 autorotate（≥2.7 默认开启）：带旋转元数据的输入会被自动
    // 转正、输出不再携带旋转标记。此前手动 transpose 与 autorotate 叠加，90°/270°
    // 竖拍源转出来是 180° 倒置+黑边（180° 源恰好凑对纯属巧合）。
    // targetW/H 是探测出的显示方向尺寸，与 autorotate 转正后的帧天然对齐
    val dimsMatch = info.width == targetW && info.height == targetH
    val vf = if (dimsMatch) {
        // 尺寸已一致：跳过 scale/pad，源像素不经历重采样（少一次画质损失还提速）
        "setsar=1,fps=${trimFps(targetFps)}"
    } else {
        "scale=$targetW:$targetH:force_original_aspect_ratio=decrease," +
            "pad=$targetW:$targetH:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=${trimFps(targetFps)}"
    }
    // 无声段补静音轨，保证拼出的成品音轨连续（AppendTrack 要求各组音轨结构一致）
    val audioIn =
        if (info.hasAudio) ""
        else " -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 -map 0:v -map 1:a -shortest"
    // 硬解作为输入解码器（-c:v 在 -i 前是输入选项）：解码不影响输出码流，
    // 省下的 CPU 全部让给 x264；失败由调用方退软解重跑。
    // -filter_threads 0 = 滤镜（scale/pad 的 swscale）按 CPU 数切片多线程，
    // 高分辨率源上缩放曾是最长的单线程段
    val decodeOpt = if (hwDecoder != null) "-c:v $hwDecoder " else ""
    val cmd = "-y -filter_threads 0 ${decodeOpt}-i $input$audioIn -vf $vf ${transcodeVideoOpts(codec)} $TRANSCODE_AUDIO -sn -dn '${out.absolutePath}'"
    runFfmpeg(cmd, item.durationMs, onSegProgress)
}

private suspend fun ffmpegConcatCopy(segments: List<File>, out: File) {
    val list = File(out.parentFile, uniqueTempName("concat", ".txt"))
    try {
        list.writeText(
            segments.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" }
        )
        runFfmpeg(
            "-y -f concat -safe 0 -i '${list.absolutePath}' -c copy -movflags +faststart '${out.absolutePath}'",
            0, {}
        )
    } finally {
        list.delete()
    }
}

/**
 * 同步等待一场 ffmpeg 执行；durationMs>0 时按已处理时长回报百分比进度。
 * 进度看门狗：统计回调里的"已处理媒体时间"超过 stallTimeoutMs 毫秒不前进就强制
 * 结束本场会话并报错——个别 AV1 等特殊流会把硬件解码器卡死，ffmpeg 永远等不到
 * 第一帧，没有看门狗就表现为进度条 0% 且永不结束、也不报错。正常编码/拷贝的
 * 统计持续前进不会误伤；-movflags faststart 的二次搬运阶段本就无统计，超时值
 * 给足了余量。
 */
private suspend fun runFfmpeg(
    command: String,
    durationMs: Long,
    onProgress: (Int) -> Unit,
    stallTimeoutMs: Long = 180_000L
): Unit = withContext(Dispatchers.IO) {
    coroutineScope {
        val finished = CompletableDeferred<FFmpegSession>()
        // 统计回调在 ffmpegkit 的线程、看门狗轮询在协程线程：基准值用原子量传递。
        // lastMediaTimeBits 存已处理媒体时间的原始位型，位型变了才算"有进展"，
        // 反复回调同一个卡死的时间点不会不断重置看门狗
        val lastMediaTimeBits = AtomicLong(Double.NaN.toRawBits())
        val lastAdvanceMs = AtomicLong(System.currentTimeMillis())
        val session = FFmpegKit.executeAsync(
            command,
            { s -> finished.complete(s) },
            null,
            { stats ->
                val bits = stats.time.toRawBits()
                if (lastMediaTimeBits.getAndSet(bits) != bits) {
                    lastAdvanceMs.set(System.currentTimeMillis())
                }
                if (durationMs > 0) {
                    onProgress((stats.time * 100L / durationMs).toInt().coerceIn(0, 99))
                }
            }
        )
        val watchdog = launch {
            while (isActive && !finished.isCompleted) {
                delay(5_000)
                if (finished.isCompleted) break
                if (System.currentTimeMillis() - lastAdvanceMs.get() > stallTimeoutMs) {
                    android.util.Log.e(
                        "VideoStitcher",
                        "ffmpeg 看门狗：${stallTimeoutMs / 1000} 秒无进展，已中止。cmd: $command"
                    )
                    runCatching { FFmpegKit.cancel(session.sessionId) }
                    finished.completeExceptionally(
                        IllegalStateException(
                            "ffmpeg 已 ${stallTimeoutMs / 1000} 秒无进展" +
                                "（该视频流疑似卡死了本机解码器），已自动中止"
                        )
                    )
                    break
                }
            }
        }
        try {
            val s = finished.await()
            if (!ReturnCode.isSuccess(s.returnCode)) {
                val tail = runCatching { s.allLogsAsString.takeLast(300) }.getOrDefault("")
                // 完整命令 + 完整日志进 logcat：失败诊断需要首行报错，300 字符尾部只有统计行
                android.util.Log.e("VideoStitcher", "ffmpeg 失败 cmd: $command")
                runCatching { s.allLogsAsString }.getOrNull()?.let {
                    android.util.Log.e("VideoStitcher", "ffmpeg 完整日志: ${it.takeLast(4000)}")
                }
                throw IllegalStateException("ffmpeg 失败（returnCode=${s.returnCode}）$tail")
            }
        } catch (e: CancellationException) {
            // Activity 销毁会取消协程：只取消自己这场会话。FFmpegKit.cancel() 无参重载
            // 是全局的，会把同时段其它分组并行转码一起掐掉
            runCatching { FFmpegKit.cancel(session.sessionId) }
            throw e
        } finally {
            watchdog.cancel()
        }
    }
}

/** 读出视频轨解码配置里的参数集字节（avcC 的 SPS/PPS，hvcC 的 VPS/SPS/PPS），
 *  用于拼前参数集比对；无视频轨或读不到配置盒返回空表 */
private fun readParameterSets(file: File): List<ByteArray> {
    FileInputStream(file).channel.use { ch ->
        val parser = boxParser ?: return emptyList()
        val iso = IsoFile(ch, parser)
        for (trackBox in iso.movieBox.getBoxes(TrackBox::class.java)) {
            if (trackBox.mediaBox.handlerBox.handlerType != "vide") continue
            val sampleEntry = trackBox.mediaBox.mediaInformationBox.sampleTableBox
                .sampleDescriptionBox.getBoxes(VisualSampleEntry::class.java).firstOrNull()
                ?: return emptyList()
            sampleEntry.getBoxes(AvcConfigurationBox::class.java).firstOrNull()?.let { avcC ->
                val rec = avcC.avcDecoderConfigurationRecord ?: return emptyList()
                return (rec.sequenceParameterSets + rec.pictureParameterSets).map { buf ->
                    val dup = buf.duplicate()
                    ByteArray(dup.remaining()).also { dup.get(it) }
                }
            }
            sampleEntry.getBoxes(HevcConfigurationBox::class.java).firstOrNull()?.let { hvcC ->
                // nal_unit_type：32=VPS 33=SPS 34=PPS
                return hvcC.hevcDecoderConfigurationRecord.arrays
                    .filter { it.nal_unit_type in 32..34 }
                    .flatMap { it.nalUnits }
            }
        }
    }
    return emptyList()
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
        var lastProgress = -1
        var lastChangeMs = System.currentTimeMillis()
        try {
            while (!finished.isCompleted) {
                withContext(Dispatchers.Main) {
                    runCatching { transformer.getProgress(holder) }
                }
                if (holder.progress != lastProgress) {
                    lastProgress = holder.progress
                    lastChangeMs = System.currentTimeMillis()
                }
                if (holder.progress in 1..99) onProgress(holder.progress)
                // 看门狗：进度长时间不动（个别 AV1/特殊流卡死解码管线，media3 自带的
                // muxer 停顿超时盖不住所有情况）时主动取消，上层引擎链降级下一档方式，
                // 而不是进度 0% 永远挂起
                if (System.currentTimeMillis() - lastChangeMs > 180_000L) {
                    throw IllegalStateException(
                        "转封装已 180 秒无进展（该视频流疑似卡死了本机解码管线），已自动中止"
                    )
                }
                delay(300)
            }
            finished.await()
        } catch (e: CancellationException) {
            withContext(Dispatchers.Main) { runCatching { transformer.cancel() } }
            throw e
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) { runCatching { transformer.cancel() } }
            throw t
        }
    }
}

private fun copyTmpToOut(context: Context, tmp: File, outUri: Uri) {
    context.contentResolver.openFileDescriptor(outUri, "rw")!!.use { outPfd ->
        FileOutputStream(outPfd.fileDescriptor).channel.use { dst ->
            FileInputStream(tmp).channel.use { src ->
                val size = src.size()
                // transferFrom 单次调用不保证搬完（文档允许欠转），必须循环到齐
                var pos = 0L
                while (pos < size) {
                    val n = dst.transferFrom(src, pos, size - pos)
                    if (n <= 0) break
                    pos += n
                }
                if (pos != size) throw IllegalStateException("成品写入不完整（$pos/$size 字节）")
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
