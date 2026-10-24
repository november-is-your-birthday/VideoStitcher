package com.kai.videostitcher

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
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
import org.mp4parser.muxer.Movie
import org.mp4parser.muxer.Mp4TrackImpl
import org.mp4parser.muxer.RandomAccessSource
import org.mp4parser.muxer.Track as Mp4Track
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.tracks.AppendTrack
import org.mp4parser.muxer.tracks.ChangeTimeScaleTrack
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/** 全局导出闸门：Transformer 导出（转封装）最多 2 路并发，匹配手机硬件编码器实例数；
 *  mp4parser 无损拼接只吃 IO，不占闸门，可任意并行。 */
private val exportGate = Semaphore(2)

/** 临时文件/目录唯一名：纯毫秒时间戳在多分组并行时会同毫秒撞名，互相删文件 */
fun uniqueTempName(prefix: String, suffix: String): String =
    "${prefix}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}$suffix"

/** ffmpeg-kit fork 只发布 arm64-v8a/x86_64 的 native 库；32 位设备调用会
 *  UnsatisfiedLinkError，转码/转封装入口必须先守卫 */
fun ffmpegAvailable(): Boolean =
    Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

/** 录制/转码过程可能被系统杀掉，中间件会留在缓存目录里直到用户手动清理 */
private val TEMP_PREFIXES = listOf(
    "转码段_", "混合段_", "转码成品_", "拼接成品_", "转封装临时_", "剥轨_", "音频段_", "concat_"
)

/** 启动时清掉上次进程被杀残留的中间件（只动本应用命名的临时文件） */
fun cleanupStaleTempFiles(context: Context) {
    for (dir in listOfNotNull(context.cacheDir, context.getExternalFilesDir(null))) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (TEMP_PREFIXES.any { f.name.startsWith(it) }) runCatching { f.delete() }
            }
        }
    }
}

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
    val extraTracks: Int = 0,
    /** 素材时长（µs → ms），探测时一并取回，避免为了拿时长再开一次 extractor */
    val durationMs: Long = 0,
    /** 视频码率（bps），硬件编码按码率控制时作为质量基准 */
    val bitrate: Int = 0,
    /** 音频解码配置字节，用于判断能不能整段直拷音频 */
    val audioCsd: ByteArray? = null,
    /** 是否带 B 帧（解码序 PTS 出现回退即判定）：带 B 帧的直拷段过 MPEG-TS
     *  中转拼接时边界时戳易错乱（ffmpeg concat 已知问题，lossless-cut #3057），
     *  引擎链据此回避危险组合 */
    val hasBFrames: Boolean = false
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

private fun MediaFormat.intValue(key: String, def: Int = 0): Int =
    runCatching { getInteger(key) }.getOrDefault(def)

private fun MediaFormat.byteBufferCopy(key: String): ByteArray? =
    runCatching {
        getByteBuffer(key)?.let { buf ->
            val dup = buf.duplicate()
            ByteArray(dup.remaining()).also { dup.get(it) }
        }
    }.getOrNull()

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
 */
fun inconsistentAdvice(infos: List<TrackInfo>): String {
    val sb = StringBuilder()
    val unreadable = infos.count { it.videoMime == null }
    if (unreadable > 0) {
        sb.append("有 $unreadable 个视频本机读不动（编码特殊或封装不规范）。")
    } else {
        sb.append("本组视频参数不一致，无法无损拼接。")
        val codecs = infos.mapNotNull { it.videoMime?.let { m -> VideoCodec.ofMime(m).display.ifEmpty { m.substringAfter('/').uppercase() } } }.distinct()
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
 * 成品自检：引擎"导出成功"不等于文件能播。真机上各片段由相互独立的硬件
 * 编码会话产出，容器层拼接只保留第一段的解码配置，段间参数集（SPS/PPS/
 * 色彩信息）不一致时整条流会解不动——模拟器软编码器输出一致，掩盖了这类
 * 问题。这里做三道检查：时长接近素材总和、首/中/尾三点能解出画面、
 * 采样表索引可读（时间戳不倒流、结尾不截断）。
 * 返回 null 表示通过，否则返回失败原因。
 */
fun verifyOutputUsable(
    context: Context,
    uri: Uri,
    expectedDurationMs: Long,
    checkPointsMs: List<Long> = emptyList(),
    /** 组内本来就有视频时必须为 true：视频轨被容器丢弃时要判失败，不能只跳过抽帧 */
    expectVideo: Boolean = false
): String? {
    var durMs = 0L
    try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return "读不到成品时长"
            if (durMs <= 0) return "成品时长为 ${durMs}ms"
            if (expectedDurationMs > 0 && durMs < expectedDurationMs * 85 / 100)
                return "成品时长 ${durMs}ms 明显短于素材总时长 ${expectedDurationMs}ms（内容被截断）"
            // 只查"变短"是不够的：时基错乱会把 11 分钟拼成 50 分钟，容器时长变长
            // 反而躲过校验，坏文件静默进了相册。上限同样要卡。
            if (expectedDurationMs > 0 && durMs > expectedDurationMs * 120 / 100)
                return "成品时长 ${durMs}ms 明显长于素材总时长 ${expectedDurationMs}ms（时间基准错乱）"
            val hasVideo =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            // 参数集冲突最狠的后果不是花屏，而是 Android 的 MPEG4Extractor 把整条
            // 视频轨 skipTrack 掉（多 stsd 的普通音视频轨），成品只剩音频；此时
            // 时长、采样表、音轨时长全都"正常"，只有这条硬性校验能抓住。
            if (expectVideo && !hasVideo) return "成品没有视频轨（视频轨被容器丢弃）"
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
                    // 只为"能不能解出画面"，不需要原尺寸帧：4K 整帧解码一次几十毫秒，
                    // 32 倍缩略图足够判断，还能省掉大量内存
                    val frame = if (Build.VERSION.SDK_INT >= 27)
                        mmr.getScaledFrameAtTime(tUs, opts, 320, 320)
                    else
                        mmr.getFrameAtTime(tUs, opts)
                    if (frame == null) return "在 ${tMs / 1000.0}s 处解不出画面"
                    runCatching { frame.recycle() }
                }
            }
        } finally {
            mmr.release()
        }
    } catch (t: Throwable) {
        return "自检异常：${t.message ?: t.javaClass.simpleName}"
    }
    return checkSampleIndex(context, uri, durMs, expectedDurationMs, expectVideo)
}

/**
 * 采样表索引检查（纯元数据，不解码、不读样本 payload）。
 * 旧实现对 <200MB 成品用 readSampleData 把整文件再读一遍，等于自检阶段多读一份成品；
 * 这里改为"逐轨首个同步帧 + 头部样本可读 + 结尾附近仍有样本"，能抓住
 * 截尾、空 stss、轨道缺失这三类真机故障，耗时是毫秒级。
 *
 * 时间戳单调性按"包络回退上限"检查（2.0.3）：带 B 帧的码流解码序 PTS 本来就会
 * 小幅回退（重排深度内），完全不查会让拼接边界损伤（秒级回退）静默进相册；
 * 超过 1 秒的回退没有合法解释，判失败让引擎链降级。
 */
private fun checkSampleIndex(
    context: Context,
    uri: Uri,
    durMs: Long,
    expectedDurationMs: Long = 0,
    expectVideo: Boolean = false
): String? {
    try {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val trackCount = ex.trackCount
            if (trackCount == 0) return "成品里没有轨道"
            var readable = 0
            var videoTracks = 0
            for (i in 0 until trackCount) {
                val mime = runCatching { ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) }
                    .getOrNull() ?: ""
                if (mime.startsWith("video/")) videoTracks++
                ex.selectTrack(i)
                // 首个可寻址样本必须是同步帧：空 stss（历史故障）会让整条流没有
                // 关键帧，播放器只能黑屏。非视频轨（音频/元数据）不适用该判定
                ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val t = ex.sampleTime
                if (t < 0) {
                    ex.unselectTrack(i)
                    return "轨道 ${i + 1} 读不到采样"
                }
                if (mime.startsWith("video/")) {
                    val flags = runCatching { ex.sampleFlags }.getOrDefault(0)
                    if (flags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) {
                        ex.unselectTrack(i)
                        return "轨道 ${i + 1} 的采样表没有同步帧（关键帧缺失）"
                    }
                    // 逐轨时长：容器时长取各轨最大值，音轨正常而视频轨被时基压短时，
                    // 容器时长"看着正常"（如视频轨 67s + 音轨 208s → mvhd 208s），
                    // 播到视频轨结束就定格。按素材总时长单独卡视频轨才能真正抓住。
                    val trackUs = runCatching {
                        ex.getTrackFormat(i).getLong(MediaFormat.KEY_DURATION)
                    }.getOrDefault(0L)
                    if (expectedDurationMs > 0 && trackUs > 0) {
                        val trackMs = trackUs / 1000
                        if (trackMs < expectedDurationMs * 85 / 100 ||
                            trackMs > expectedDurationMs * 120 / 100
                        ) {
                            ex.unselectTrack(i)
                            return "轨道 ${i + 1} 时长 ${trackMs}ms 与素材总时长 " +
                                "${expectedDurationMs}ms 不符（时间基准错乱或拼接被截断，" +
                                "播放到后段画面会卡住）"
                        }
                    }
                } else if (mime.startsWith("audio/") && expectedDurationMs > 0) {
                    // 音轨同样要卡：多音轨被首尾相接时溢出的正是音轨（视频轨看起来
                    // 完全正常，容器时长却被音轨撑大），这条专门盯这类错位
                    val trackUs = runCatching {
                        ex.getTrackFormat(i).getLong(MediaFormat.KEY_DURATION)
                    }.getOrDefault(0L)
                    if (trackUs > 0) {
                        val trackMs = trackUs / 1000
                        if (trackMs < expectedDurationMs * 85 / 100 ||
                            trackMs > expectedDurationMs * 120 / 100
                        ) {
                            ex.unselectTrack(i)
                            return "音轨 ${i + 1} 时长 ${trackMs}ms 与素材总时长 " +
                                "${expectedDurationMs}ms 不符（可能混入了多余音轨或拼接被截断）"
                        }
                    }
                }
                var n = 0
                while (n < 256) {
                    n++
                    if (!ex.advance()) break
                    readable++
                }
                // 2.0.3：音视频轨全量时序包络检查（只走采样表）。视频解码序 PTS
                // 的局部回退是 B 帧的正常表现（≤ 重排深度，几十~几百毫秒），音频
                // 没有/极小（一帧 AAC 21ms），但拼接边界损伤会回退整整一段（秒级）
                // ——旧实现不查单调性，这类坏成品能静默进相册（时间错乱/播放卡死
                // 的直接来源）。回退超 1 秒判失败，引擎链自动降级下一档
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    var envelope = 0L
                    var worst = 0L
                    var walked = 0
                    // advance() 已在上面的 256 步里走过一段，接着走完剩下的即可
                    while (walked < 300_000) {
                        val t = ex.sampleTime
                        if (t < 0) break
                        if (t > envelope) envelope = t
                        val back = envelope - t
                        if (back > worst) worst = back
                        if (worst > 1_000_000) {
                            ex.unselectTrack(i)
                            return "轨道 ${i + 1} 时序错乱：解码序时间戳回退 ${worst / 1000}ms" +
                                "（拼接边界损坏，播放会卡死/跳秒），已拦截"
                        }
                        walked++
                        if (!ex.advance()) break
                    }
                }
                ex.unselectTrack(i)
            }
            if (expectVideo && videoTracks == 0) return "成品里没有视频轨"
            if (readable == 0) return "采样表里没有可读数据"
            if (durMs > 1000 && trackCount > 0) {
                ex.selectTrack(0)
                ex.seekTo((durMs - 200).coerceAtLeast(0) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val end = ex.sampleTime
                ex.unselectTrack(0)
                if (end < 0) return "结尾附近读不到采样（文件可能被截断）"
            }
        } finally {
            ex.release()
        }
    } catch (t: Throwable) {
        return "采样表读取失败：${t.message ?: t.javaClass.simpleName}"
    }
    return null
}

/** 用 MediaExtractor 读取视频的编码/分辨率/帧率/音频参数，一并取回时长与码率 */
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
        var audioCsd: ByteArray? = null
        var extraTracks = 0
        var durationMs = 0L
        var bitrate = 0
        var videoIdx = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null) {
                extraTracks++
                continue
            }
            if (durationMs <= 0) {
                // KEY_DURATION 单位是微秒；部分封装不提供，保持 0 由上层补探
                val us = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                if (us > 0) durationMs = us / 1000
            }
            if (mime.startsWith("video/") && videoMime == null) {
                videoIdx = i
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
                bitrate = format.intValue(MediaFormat.KEY_BIT_RATE)
                profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else -1
                val transfer =
                    if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else -1
                hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
                // csd-0 = avcC/hvcC 解码配置字节：逐字节比对是"能否直接容器级拼接"
                // 的最严闸门，涵盖 profile/level/VUI 等所有字段比对覆盖不到的差异
                csd = format.byteBufferCopy("csd-0")
            } else if (mime.startsWith("audio/") && audioMime == null) {
                audioMime = mime
                sampleRate =
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                channels =
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                audioCsd = format.byteBufferCopy("csd-0")
            } else if (!mime.startsWith("video/") && !mime.startsWith("audio/")) {
                extraTracks++
            }
        }
        val bFrames = if (videoIdx >= 0) detectBFrames(extractor, videoIdx) else false
        return TrackInfo(
            videoMime, width, height, rotation, fps, audioMime, sampleRate, channels,
            audioMime != null, profile, hdr, csd, extraTracks, durationMs, bitrate, audioCsd, bFrames
        )
    } finally {
        extractor.release()
    }
}

/**
 * B 帧判定：MediaExtractor 按解码序吐样本，无 B 帧时解码序=显示序、PTS 严格递增；
 * 出现任何回退即带 B 帧（I P B 的 B 在 P 之后解码但 PTS 更早）。只走采样表、
 * 不读帧数据，前 120 个样本足够覆盖任何 GOP 结构。
 */
private fun detectBFrames(extractor: MediaExtractor, videoTrackIndex: Int): Boolean = runCatching {
    extractor.selectTrack(videoTrackIndex)
    try {
        var prev = Long.MIN_VALUE
        var n = 0
        while (n < 120) {
            val t = extractor.sampleTime
            if (t < 0) break
            if (t < prev) return true
            prev = t
            n++
            if (!extractor.advance()) break
        }
        false
    } finally {
        extractor.unselectTrack(videoTrackIndex)
    }
}.getOrDefault(false)

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
@Volatile
private var boxParser: BoxParser? = null

@Synchronized
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
 * 进度按输出文件已写字节 / 预计字节估算（写盘本身是顺序的，足够准）。
 */
suspend fun concatLossless(
    context: Context,
    items: List<VideoItem>,
    outPfd: ParcelFileDescriptor,
    onProgress: (Int) -> Unit = {}
) {
    val expectedBytes = items.sumOf { item ->
        runCatching {
            context.contentResolver.openFileDescriptor(item.uri, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)
    }
    coroutineScope {
        val poller = launch(Dispatchers.IO) {
            while (isActive) {
                delay(250)
                val written = runCatching { outPfd.statSize }.getOrDefault(0L)
                if (expectedBytes > 0) {
                    onProgress((written * 100 / expectedBytes).toInt().coerceIn(0, 99))
                }
            }
        }
        val opened = mutableListOf<AutoCloseable>()
        try {
            val movies = items.map { item -> loadMovie(context, item.uri, item.name, opened) }
            appendAndWrite(movies, outPfd)
        } finally {
            for (c in opened) runCatching { c.close() }
            poller.cancel()
        }
    }
    onProgress(100)
}

/** 组内时间基准不一致时的降级信号：交给 ffmpeg 拼接，不在 mp4parser 里硬算 */
class LosslessFallbackException(message: String) : Exception(message)

/**
 * 时间基准归一化（1.9.4：修复"11 分钟拼成 50 分钟、中途卡死"）。
 *
 * mp4parser 的 AppendTrack 只是把各轨的 sample duration **原始 tick 数**首尾相接，
 * 而输出轨沿用第 1 条轨的 mdhd.timescale（1.9.56 的 getTrackMetaData() 直接返回
 * tracks[0]，构造器里没有任何换算）。相机录制的 H.264 常见 timescale=90000，
 * 剪辑软件/部分下载源的 MP4 常见 16000、15360、30000——混拼时后续文件的 tick
 * 会被当成第 1 个文件的时基解释：
 *   · 90000 的轨接到 16000 上 → 时长虚增 5.6 倍（11 分钟变 50 分钟）
 *   · 16000 的轨接到 90000 上 → 被压成 1/5.6（视频轨提前结束，后段画面定住）
 * 两种都会让容器时长与真实帧数脱节，播放器跳到没有帧的时间点就卡死。
 *
 * 这里按组内**最大**时基把每条轨换算到同一基准：向上换算（小→大）不丢精度，
 * 是唯一不会引入累计误差的方向，故取 max 而非第一条轨的时基。
 * 缺少 stss 同步帧表的轨（全帧内编码）无法安全重算时间轴，抛
 * [LosslessFallbackException] 让调用方改用 ffmpeg 拼接——同步表算错会让成品的
 * 可跳转点全部失效，宁可慢一点。
 */
private fun normalizeTimeScale(tracks: List<Mp4Track>): List<Mp4Track> {
    if (tracks.size <= 1) return tracks
    val target = tracks.maxOf { it.trackMetaData.timescale }
    if (tracks.all { it.trackMetaData.timescale == target }) return tracks
    if (tracks.any { it.trackMetaData.timescale != target && it.syncSamples == null }) {
        throw LosslessFallbackException(
            "片段时间基准不同且缺少同步帧表（${tracks.map { it.trackMetaData.timescale }.distinct()}）"
        )
    }
    return tracks.map { t ->
        val ts = t.trackMetaData.timescale
        if (ts == target) t else {
            android.util.Log.i("VideoStitcher", "无损拼接：轨道时基 $ts → $target")
            ChangeTimeScaleTrack(t, target, t.syncSamples)
        }
    }
}

/** 每个文件只取第一条视频轨 / 第一条音频轨（2.0.0：修复双音轨文件时长翻倍）。
 *
 *  `AppendTrack` 会把传进去的**每一条**轨都串进同一条输出轨，而 `probeVideo`
 *  只描述第一条音/视频轨、`paramsUniform` 也只比第一条——文件里若有第二条音轨
 *  （第三方录屏同时录系统声+麦克风、双语 MP4），两条会被首尾相接：37s 的素材
 *  拼出 74s 的成品，视频轨仍是 37s，播到一半画面就定住、进度条后半段没有帧。
 *  这里按文件取首条，与探测口径保持一致；多余的轨不进成品（容器级拼接表达不了
 *  多轨，需要保留就走转封装/转码，由上层闸门决定）。
 */
private fun firstTrackOf(movies: List<Movie>, handler: String): List<Mp4Track> =
    movies.mapNotNull { m -> m.tracks.firstOrNull { it.handler == handler } }

/** B 帧表（ctts）必须"齐或不齐"。
 *
 *  `AppendTrack.getCompositionTimeEntries()` 只判断 `tracks[0]` 有没有 ctts
 *  （已按 1.9.56 字节码核实）：若首段没有而后续段有，整份 ctts 直接丢；若首段有
 *  而后续段没有，那段样本的 ctts 条目缺失，显示时间戳会错位、画面抖动/乱序。
 *  混得起来说明片段来自不同编码器，交给 ffmpeg 拼更稳。
 */
private fun requireUniformCtts(tracks: List<Mp4Track>) {
    if (tracks.size <= 1) return
    val withCtts = tracks.count { !it.compositionTimeEntries.isNullOrEmpty() }
    if (withCtts != 0 && withCtts != tracks.size) {
        throw LosslessFallbackException("片段 B 帧表不一致（$withCtts/${tracks.size} 段带 ctts）")
    }
}

/** 把多个 Movie 追加成单轨并写入输出 */
private fun appendAndWrite(movies: List<Movie>, outPfd: ParcelFileDescriptor) {
    val out = Movie()
    // 保留第一个片段的显示矩阵（片段若带旋转元数据，拼接后必须延续，否则方向丢失）
    movies.firstOrNull()?.let { out.matrix = it.matrix }
    val videos = normalizeTimeScale(firstTrackOf(movies, "vide")).also { requireUniformCtts(it) }
    val audios = normalizeTimeScale(firstTrackOf(movies, "soun"))
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
    onProgress: (GroupProgress) -> Unit
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

        onProgress(GroupProgress(MergeStage.TRANSMUX, percent = ProgressMath.stagePercent(MergeStage.TRANSMUX, 0)))
        exportComposition(context, composition, tmp) { p ->
            onProgress(GroupProgress(MergeStage.TRANSMUX, percent = ProgressMath.stagePercent(MergeStage.TRANSMUX, p)))
        }
        copyTmpToOut(context, tmp, outUri)
    } finally {
        tmp.delete()
    }
}

/* ---------- 编码模型：严格映射，绝不做"未知编码视作 H.264"的兜底 ---------- */

/**
 * 视频编码的严格标识。旧实现 `outputCodecOf` 用 `else -> h264` 兜底，
 * 在放开直拷后会把 VP9/MPEG-4 当成 H.264 直接拷贝（成品解不动）；
 * 这里改成表驱动：[copyProfiles] 是允许直拷的主流档位，[swEncodable] 表示
 * ffmpeg 是否有对应软编器（只有这四个能作为"需要重编"时的输出目标）。
 */
enum class VideoCodec(
    val id: String,
    val display: String,
    val mime: String,
    val copyProfiles: Set<Int>,
    val swEncodable: Boolean
) {
    H264("h264", "H.264", "video/avc", setOf(1, 2, 8), true),          // Baseline/Main/High
    H265("h265", "H.265", "video/hevc", setOf(1), true),                // HEVC Main（Main10 不拷）
    // 0 与 1 都要：av1C 的 seq_profile 里 Main=0（部分 MediaExtractor 直接上报这个值），
    // 而 Android 的 CodecProfileLevel.AV1ProfileMain8 常量是 1——取决于厂商怎么填
    // KEY_PROFILE，同一段 8bit Main 可能报 0 也可能报 1。只认 1 会把报 0 的机器上
    // 所有 AV1 段判成"档位不支持"，整组退回全量转码。10bit（Android 常量 2）仍被排除。
    AV1("av1", "AV1", "video/av01", setOf(0, 1), true),                 // AV1 Main 8bit
    VP9("vp9", "VP9", "video/x-vnd.on2.vp9", setOf(1), true),           // VP9 Profile 0
    VP8("vp8", "VP8", "video/x-vnd.on2.vp8", setOf(-1), false),
    MPEG4("mpeg4", "MPEG-4", "video/mp4v-es", setOf(1, 2), false),
    H263("h263", "H.263", "video/3gpp", setOf(-1), false),
    UNKNOWN("", "", "", emptySet(), false);

    val selectable: Boolean get() = id.isNotEmpty() && swEncodable

    companion object {
        fun ofMime(mime: String?): VideoCodec = entries.firstOrNull { it.mime == mime } ?: UNKNOWN
        fun ofId(id: String?): VideoCodec = entries.firstOrNull { it.id == id } ?: UNKNOWN

        /** 显示名：表里没有的编码退回原始子类型，仍有辨识价值 */
        fun displayOfMime(mime: String?): String {
            val c = ofMime(mime)
            return if (c == UNKNOWN) videoCodecDisplay(mime) else c.display
        }

        /** 自动模式的平票优先级：越靠前兼容性越好 */
        val preference = listOf(H264, H265, VP9, AV1)
    }
}

/**
 * 本机可解码性判定。直拷的前提是"拷出来的成品这台手机自己能播"——
 * MPEG-2/ProRes/Dolby Vision 这类 Android 没有解码器的格式即使容器能装，
 * 拷进去也是黑屏，因此一律不拷、走重编码。
 */
object CodecCaps {
    private val cache = ConcurrentHashMap<String, Boolean>()

    /**
     * 只看"本机有没有这个编码的解码器"。刻意不带 profile 去查：
     * 部分机型对 KEY_PROFILE 的组合会直接抛异常，反而把可拷的段判死；
     * 10bit/特殊档位由 [VideoCodec.copyProfiles] 的白名单负责拦。
     */
    fun androidCanDecode(mime: String?, w: Int, h: Int): Boolean {
        if (mime == null) return false
        val key = "$mime|$w|$h"
        return cache.getOrPut(key) {
            runCatching {
                val fmt = MediaFormat.createVideoFormat(mime, w.coerceAtLeast(2), h.coerceAtLeast(2))
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(fmt) != null
            }.getOrDefault(false)
        }
    }
}

/**
 * 硬件编码能力探测（MediaCodec）。
 *
 * 只做解码探测、不做"跑一段 1 秒 testsrc 冒烟"：真机上编码器存在但当前会话
 * 被占满时，冒烟也会失败，反而误判成不支持；真正的可靠性由逐段三级降级
 * （硬编 → 软编 → 软解软编）+ 拼后自检兜住。
 *
 * ffmpeg 的 mediacodec 编码器选项在不同 ROM 上略有差异（例如 bitrate_mode），
 * 首次使用时用 `-h encoder=<name>` 确认，只拼实测存在的选项，避免整条命令
 * 因为一个 unrecognized option 直接失败。
 */
object HwEncoders {
    private val encoders = HashMap<VideoCodec, String>()
    private val optionCache = HashMap<String, Set<String>>()
    private var probed = false

    @Synchronized
    fun encoderName(codec: VideoCodec): String? {
        if (!probed) {
            probed = true
            runCatching {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in list.codecInfos) {
                    if (!info.isEncoder) continue
                    if (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated) continue
                    val n = info.name.lowercase(Locale.US)
                    // 排除 Google 的软实现：它快不起来，还会抢走软编的可控性
                    if (n.startsWith("omx.google.") || n.startsWith("c2.android.")) continue
                    for (type in info.supportedTypes) {
                        val c = VideoCodec.ofMime(type)
                        if (c != VideoCodec.H264 && c != VideoCodec.H265) continue
                        encoders.putIfAbsent(c, "${c.id}_mediacodec")
                    }
                }
            }
        }
        return encoders[codec]
    }

    /** 返回该编码器在 `-h encoder=` 里真实存在的选项名集合 */
    @Synchronized
    fun supportedOptions(name: String): Set<String> {
        optionCache[name]?.let { return it }
        val opts = runCatching {
            val logs = FFmpegKit.execute("-hide_banner -h encoder=$name").allLogsAsString ?: ""
            Regex("(?m)^\\s*-([a-zA-Z0-9_]+)\\s").findAll(logs).map { it.groupValues[1] }.toSet()
        }.getOrDefault(emptySet())
        optionCache[name] = opts
        return opts
    }
}

/**
 * 转码输出设置（仅转码组生效，无损路径不重编码不受影响）。
 * shortEdge：输出短边上限（0=保持原尺寸；1080/720=把超过限制的源缩到该短边）。
 * codecMode："auto"=跟随源编码（混合时按总时长最多者，平票取 H.264），
 * 或固定 "h264"/"h265"/"av1"。
 * encoderMode："sw"=软件编码（默认，行为全机型一致）；"hw"=优先 MediaCodec 硬编。
 */
data class TranscodeSettings(
    val shortEdge: Int,
    val codecMode: String,
    val encoderMode: String = "sw"
)

fun loadTranscodeSettings(context: Context): TranscodeSettings {
    val p = context.getSharedPreferences("output_settings", Context.MODE_PRIVATE)
    // 旧版本只存 h265 布尔：迁移成 codecMode 后移除旧键
    if (p.contains("h265")) {
        val mode = if (p.getBoolean("h265", false)) "h265" else "h264"
        saveTranscodeSettings(context, TranscodeSettings(p.getInt("shortEdge", 0), mode))
    }
    return TranscodeSettings(
        p.getInt("shortEdge", 0).coerceIn(0, 2160),
        p.getString("codecMode", "auto") ?: "auto",
        p.getString("encoderMode", "sw") ?: "sw"
    )
}

fun saveTranscodeSettings(context: Context, settings: TranscodeSettings) {
    context.getSharedPreferences("output_settings", Context.MODE_PRIVATE).edit()
        .putInt("shortEdge", settings.shortEdge)
        .putString("codecMode", settings.codecMode)
        .putString("encoderMode", settings.encoderMode)
        .remove("h265")
        .apply()
}

/* ---------- 部分转码引擎（1.9.3）：只转码需要改动的段，其余原样拷贝 ----------
 * 2.0 起"可直拷"从 H.264/H.265 放开到所有本机可解码的编码：
 * 编码相同、画布相同、无旋转、非 HDR 的段，视频像素一个字节不动（仅音频按需统一），
 * 只有真正需要改动的段才重编码，最后 ffmpeg 容器级拼接 + 成品自检。 */

/** 目标画布与帧率：按总时长多数选择，不再无条件取第一段
 *  （首段 4K 会把整组拖进 4K 重编，首段 480p 又会丢掉后面的清晰度）。
 *  fps 只用于码率估算，不进滤镜：转码逐段保留源帧率（可变帧率输出），
 *  强制对齐多数帧率会把高帧率段压半（2.0.4 修复"拼接后明显卡顿"） */
data class TranscodePlan(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Float
)

private fun even(v: Int): Int = (v / 2 * 2).coerceAtLeast(2)

private fun resolveCanvas(infos: List<TrackInfo>, durationsMs: List<Long>): Pair<Int, Int> {
    val totals = LinkedHashMap<Pair<Int, Int>, Long>()
    infos.forEachIndexed { i, info ->
        val w = info.width.takeIf { it > 0 } ?: 1920
        val h = info.height.takeIf { it > 0 } ?: 1080
        val key = even(w) to even(h)
        totals[key] = (totals[key] ?: 0L) + max(durationsMs.getOrElse(i) { 0L }, 1000L)
    }
    // 平票取面积更大的（保画质），面积再相同取字典序最小的，保证结果稳定
    val best = totals.entries.maxWithOrNull(
        compareBy({ it.value }, { it.key.first.toLong() * it.key.second }, { -it.key.first })
    )?.key ?: (1920 to 1080)
    return best
}

private fun resolveFps(infos: List<TrackInfo>, durationsMs: List<Long>): Float {
    val buckets = HashMap<Int, Long>()
    infos.forEachIndexed { i, info ->
        val f = info.fps
        if (f > 1f) {
            val k = f.roundToInt().coerceIn(5, 120)
            buckets[k] = (buckets[k] ?: 0L) + max(durationsMs.getOrElse(i) { 0L }, 1000L)
        }
    }
    return (buckets.entries.maxByOrNull { it.value }?.key ?: 30).toFloat()
}

private fun buildPlan(
    codec: VideoCodec,
    infos: List<TrackInfo>,
    durationsMs: List<Long>,
    settings: TranscodeSettings
): TranscodePlan {
    var (w, h) = resolveCanvas(infos, durationsMs)
    if (settings.shortEdge > 0) {
        val short = minOf(w, h)
        if (short > settings.shortEdge) {
            val scale = settings.shortEdge.toFloat() / short
            w = even((w * scale).toInt())
            h = even((h * scale).toInt())
        }
    }
    return TranscodePlan(codec, even(w), even(h), resolveFps(infos, durationsMs))
}

/** "auto" 模式解析组内实际输出编码：按总时长少数服从多数——哪种编码的素材
 *  总时长最长就输出谁（个数多但都是几秒的零碎小片段不再带偏输出）；平票取
 *  更低世代（H.264 低于 H.265 低于 VP9 低于 AV1——老设备兼容面大、软解省电）。
 *  未收录的编码不参与投票：它们本来就必须重编，目标统一落到 H.264。 */
fun resolveOutputCodec(
    infos: List<TrackInfo>,
    durationsMs: List<Long>,
    settings: TranscodeSettings
): String {
    if (settings.codecMode != "auto") return settings.codecMode
    val codecs = infos.map { VideoCodec.ofMime(it.videoMime) }
    if (codecs.any { it == VideoCodec.UNKNOWN }) return VideoCodec.H264.id
    val distinct = codecs.distinct()
    // 全组同一编码：直接跟随，允许 100% 直拷（该编码没有软编器也没关系，不需要编）
    if (distinct.size == 1) return distinct[0].id
    val totals = HashMap<VideoCodec, Long>()
    codecs.forEachIndexed { i, c ->
        totals[c] = (totals[c] ?: 0L) + durationsMs.getOrElse(i) { 0L }
    }
    val maxTotal = totals.values.maxOrNull() ?: return VideoCodec.H264.id
    val winners = totals.filterValues { it == maxTotal }.keys
    return (VideoCodec.preference.firstOrNull { it in winners }
        ?: winners.firstOrNull { it.selectable }
        ?: VideoCodec.H264).id
}

/**
 * 单段能否视频直拷。要求：编码严格相等 + 画布严格相等 + 无旋转 + 非 HDR +
 * 主流档位 + 有解码配置 + 本机有该编码的解码器。
 * 旋转为什么不能放：见 [copyPlan]（ffmpeg concat 分离器不搬 display matrix）。
 */
private fun canCopySegment(info: TrackInfo, plan: TranscodePlan): Boolean {
    if (info.videoMime == null) return false
    val src = VideoCodec.ofMime(info.videoMime)
    if (src == VideoCodec.UNKNOWN || src != plan.codec) return false
    if (info.profile >= 0 && info.profile !in src.copyProfiles) return false
    if (info.width != plan.width || info.height != plan.height) return false
    if (info.rotation != 0 || info.hdr) return false
    // 这三种编码的 stsd 必须带解码配置（avcC/hvcC/av1C）；VP8/VP9/MPEG-4 的
    // 配置可以缺省，只要档位白名单与解码器检查都过就允许直拷
    if (info.csd == null && (src == VideoCodec.H264 || src == VideoCodec.H265 || src == VideoCodec.AV1)) return false
    return CodecCaps.androidCanDecode(info.videoMime, plan.width, plan.height)
}

/**
 * 逐段直拷计划。
 *
 * 只允许无旋转段直拷。原因不是"concat 丢矩阵"——ffmpeg 的 copy_stream_props 会
 * 复制第一个文件的流级 side data，整组同角度的竖拍其实能保住方向；真正的问题是
 * 一条 MP4 轨道只有**一套编码尺寸 + 一个 tkhd 矩阵**：竖拍段的编码尺寸是
 * 1920x1080（方向写在矩阵里），转码段是 1080x1920 且矩阵为 0。两者混拼时，
 * 后者的画面会被前者的矩阵再转一次。只有"整组都拷、角度一致"才安全，
 * 那需要单独的引擎路径；当前统一走转码把方向烤平，宁可慢不出错片。
 */
private fun copyPlan(infos: List<TrackInfo>, plan: TranscodePlan): BooleanArray =
    BooleanArray(infos.size) { canCopySegment(infos[it], plan) }

/**
 * 能否走部分转码：全组可读、编码全部可识别、至少一段能直拷，且拼装方式可行。
 *
 * 2.0 起不再要求"全组画布一致"：目标取按时长多数的画布，画布不一致的段被缩放
 * 重编、其余段照旧直拷（"AV1 1080p + AV1 720p"只重编 720p 那段）。
 *
 * "混拷"只在参数集能带内携带时才成立：H.264/H.265/MPEG-4 可以走 mpegts 中转
 * （见 [ffmpegConcatInBand]），AV1/VP9/VP8 做不到——它们必须"全拷且 csd 一致"，
 * 否则成品会出现两套参数集而 Android 只认第一套。这条在函数末尾单独判。
 */
fun partialTranscodeEligible(
    infos: List<TrackInfo>,
    durationsMs: List<Long>,
    settings: TranscodeSettings,
    codecId: String
): Boolean {
    if (infos.isEmpty() || infos.any { it.videoMime == null }) return false
    if (infos.any { VideoCodec.ofMime(it.videoMime) == VideoCodec.UNKNOWN }) return false
    val codec = VideoCodec.ofId(codecId)
    if (codec == VideoCodec.UNKNOWN) return false
    val plan = buildPlan(codec, infos, durationsMs, settings)
    val flags = copyPlan(infos, plan)
    val copyable = flags.count { it }
    if (copyable == 0) return false
    // 目标编码没有软编器（VP8/MPEG-4/H.263）：只要有一段要转就无解
    if (!codec.swEncodable && copyable != infos.size) return false
    // 2.0.3 门禁：带 B 帧的直拷段 + 各段参数集不一致（要走 MPEG-TS 中转）是
    // ffmpeg concat 的已知雷区——B 帧解码序/显示序在段边界重叠，产生非单调
    // DTS（lossless-cut #3057、trac #4924），真机上表现为时间错乱与播放卡死。
    // 这类组放弃快速拼接、整组退全量转码（转码段由同一编码器统一产出，无此问题）
    val copiedInfos = infos.filterIndexed { i, _ -> flags[i] }
    if (copiedInfos.any { it.hasBFrames }) {
        val mixed = copyable != infos.size
        val csdUniform = copiedInfos.all { it.csd != null } &&
            copiedInfos.all { it.csd!!.contentEquals(copiedInfos.first().csd!!) }
        if (mixed || !csdUniform) return false
    }
    // 能带内拼参数集的编码（H.264/H.265/MPEG-4）允许"拷一部分、转一部分"；
    // 其余编码（AV1/VP9/VP8）做不到带内参数集，必须全拷且 csd 逐字节一致，
    // 否则成品会出现两套参数集，而 Android 只认第一套（后段花屏/解不动）
    if (!inBandCapable(codec)) {
        if (copyable != infos.size) return false
        val csds = infos.filterIndexed { i, _ -> flags[i] }.map { it.csd }
        if (csds.any { it == null }) return false
        val first = csds.first()!!
        return csds.all { it!!.contentEquals(first) }
    }
    return true
}

/** 源编码 → MediaCodec 硬解码器（fork 只带硬解，硬编单独探测）。
 *  解码不影响输出码流的确定性；个别机型/内容硬解失败时自动退回软解 */
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

/** 音频统一参数：AAC 48k 立体声 192k。直拷路径只有在全部段都已是这个规格
 *  且解码配置字节一致时才跳过重编（见 canCopyAudio）。 */
private const val TRANSCODE_AUDIO = "-c:a aac -ar 48000 -ac 2 -b:a 192k"

/** 软件编码参数。AV1 的 `-b:v 0` 是 `-crf` 生效的前提：没有它 libaom 会走
 *  受默认码率约束的约束质量模式，crf 基本失效、成品严重糊化。 */
private fun swVideoArgs(codec: VideoCodec): String = when (codec) {
    VideoCodec.H265 ->
    // bframes=0：部分解码器（含模拟器 SW HEVC 解码）对 x265 B 帧流 seek 解码不稳，
    // 关掉换取兼容性（体积略增）；hvc1 = 参数集只放 hvcC，iOS/微信兼容
        "-c:v libx265 -preset superfast -crf 24 -pix_fmt yuv420p -tag:v hvc1 -g 60 -x265-params bframes=0"
    VideoCodec.AV1 ->
        "-c:v libaom-av1 -crf 32 -b:v 0 -cpu-used 8 -row-mt 1 -tiles 2x2 -lag-in-frames 0 -usage realtime -g 120 -pix_fmt yuv420p"
    VideoCodec.VP9 ->
        "-c:v libvpx-vp9 -crf 32 -b:v 0 -deadline realtime -cpu-used 5 -row-mt 1 -g 120 -pix_fmt yuv420p"
    else ->
    // superfast 比 veryfast 快约 1.5 倍；同 CRF 下档位越低压缩效率越差，
    // crf 19 补偿观感（与 veryfast/crf20 基本持平），代价是文件略大（约三成）
        "-c:v libx264 -preset superfast -crf 19 -pix_fmt yuv420p -profile:v high -g 60"
}

/** 硬件编码按码率控制：mediacodec 没有 CRF，只能给目标码率。
 *  bpp=0.11 是"观感接近 x264 crf19"的经验值（1080p30≈6.8Mbps），再夹到设备能力内。 */
private fun estimateBitrateKbps(plan: TranscodePlan): Int {
    val fps = plan.fps.coerceIn(24f, 60f)
    val raw = plan.width.toDouble() * plan.height * fps * 0.11 / 1000.0
    return raw.toInt().coerceIn(2_500, 40_000)
}

private fun hwVideoArgs(plan: TranscodePlan): String {
    val name = HwEncoders.encoderName(plan.codec) ?: return swVideoArgs(plan.codec)
    val opts = HwEncoders.supportedOptions(name)
    return buildString {
        append("-c:v ").append(name)
        append(" -b:v ").append(estimateBitrateKbps(plan)).append('k')
        if ("bitrate_mode" in opts) append(" -bitrate_mode VBR")
        if ("i_frame_interval" in opts) append(" -i_frame_interval 1")
        append(" -pix_fmt yuv420p")
    }
    // 刻意不传 -g：mediacodec 的 KEY_I_FRAME_INTERVAL 是"秒"，把 60 传进去会变成
    // 60 秒一个关键帧；关键帧间隔交给上面的 i_frame_interval（1 秒）
}

/** 一次编码尝试的完整参数：视频参数 + 输入解码器 + 是否硬编 */
private data class EncChoice(
    val videoArgs: String,
    val hwDecoder: String?,
    val hwEncode: Boolean
)

private fun hardwareAllowed(settings: TranscodeSettings, plan: TranscodePlan): Boolean {
    if (settings.encoderMode != "hw") return false
    if (plan.codec != VideoCodec.H264 && plan.codec != VideoCodec.H265) return false
    // 720p 及以下软编本来就快、画质更可控，硬编收益不明显，反而多一层风险
    if (minOf(plan.width, plan.height) < 1080) return false
    return HwEncoders.encoderName(plan.codec) != null
}

private fun choicesFor(
    plan: TranscodePlan,
    info: TrackInfo,
    hwAllowed: Boolean
): List<EncChoice> {
    val sw = swVideoArgs(plan.codec)
    val hwDec = hwDecoderFor(info.videoMime)
    val list = mutableListOf<EncChoice>()
    if (hwAllowed) list += EncChoice(hwVideoArgs(plan), hwDec, true)
    list += EncChoice(sw, hwDec, false)
    list += EncChoice(sw, null, false)
    return list.distinctBy { it.videoArgs to it.hwDecoder }
}

/** 部分转码结果：实际输出编码 + 直拷段数 + 硬编段数，供完成文案如实展示 */
data class PartialResult(val codec: String, val copied: Int, val hwEncoded: Int)

/** 全量转码结果 */
data class TranscodeResult(val codec: String, val hwEncoded: Int)

/** 段进度驱动器：把"第几段/多少%"翻译成整组进度，权重按段时长、直拷段降权 */
private class SegDriver(
    private val items: List<VideoItem>,
    private val infos: List<TrackInfo>,
    private val copying: BooleanArray,
    private val dstCodec: String
) {
    private val weights = ProgressMath.weights(items.map { it.durationMs }, copying)

    fun tick(
        index: Int,
        stage: MergeStage,
        pct: Int,
        sample: FfmpegSample? = null,
        hwDecode: Boolean = false,
        hwEncode: Boolean = false,
        note: String? = null
    ): GroupProgress {
        val item = items[index]
        val info = infos.getOrNull(index)
        val seg = SegmentProgress(
            index = index + 1,
            total = items.size,
            name = item.name,
            srcCodec = VideoCodec.displayOfMime(info?.videoMime),
            dstCodec = dstCodec,
            copying = copying.getOrElse(index) { false },
            percent = pct.coerceIn(0, 100),
            sample = sample,
            hwDecode = hwDecode,
            hwEncode = hwEncode,
            note = note
        )
        return GroupProgress(
            stage = stage,
            seg = seg,
            percent = ProgressMath.segmentPercent(weights, index, seg.percent),
            etaMs = ProgressMath.etaMs(item.durationMs, seg.percent, sample?.speed ?: 0.0)
        )
    }
}

/** 全部段都有音轨且 AAC/48k/2ch、解码配置字节完全一致时，音频也能直拷 */
private fun canCopyAudio(infos: List<TrackInfo>): Boolean {
    if (infos.isEmpty()) return false
    if (!infos.all {
            it.hasAudio && it.audioMime == "audio/mp4a-latm" &&
                it.sampleRate == 48000 && it.channels == 2 && it.audioCsd != null
        }
    ) return false
    val first = infos.first().audioCsd ?: return false
    return infos.all { it.audioCsd!!.contentEquals(first) }
}

/**
 * 部分转码拼接：目标编码段直拷、异编码/异画布段走 transcode（含硬解/硬编降级），
 * 全部音频按需统一，ffmpeg concat -c copy 容器级拼接（各段解码配置不同，
 * mp4parser 单一 stsd 会写坏，必须走 ffmpeg 的多采样条目），拼后成品自检。
 */
suspend fun partialTranscodeConcat(
    context: Context,
    items: List<VideoItem>,
    infos: List<TrackInfo>,
    outUri: Uri,
    settings: TranscodeSettings,
    onProgress: (GroupProgress) -> Unit
): PartialResult {
    exportGate.withPermit {
        val durations = items.map { it.durationMs }
        val codec = VideoCodec.ofId(resolveOutputCodec(infos, durations, settings))
        val plan = buildPlan(codec, infos, durations, settings)
        val copyFlags = copyPlan(infos, plan)
        // 目标编码没有软编器（VP8/MPEG-4/H.263）时，只要有一段拷不了就无解：
        // swVideoArgs 没有对应参数，硬转只会产出编码与文案不符的成品，直接退回全量转码
        if (!codec.swEncodable && copyFlags.any { !it }) {
            throw IllegalStateException("目标编码 ${codec.display} 无法在本组内直拷，改用全量转码")
        }
        val audioCopy = canCopyAudio(infos)
        val audioNote = if (audioCopy) "音频直拷" else "音频重编 AAC 192k"
        val hwAllowed = hardwareAllowed(settings, plan)
        val driver = SegDriver(items, infos, copyFlags, codec.display)
        val cacheDir = context.cacheDir
        val segments = mutableListOf<File>()
        var copiedCount = 0
        var hwEncoded = 0
        var hwFailures = 0
        try {
            for ((i, item) in items.withIndex()) {
                val seg = File(cacheDir, uniqueTempName("混合段", ".mp4"))
                segments += seg
                if (copyFlags[i]) {
                    copiedCount++
                    onProgress(driver.tick(i, MergeStage.SEG_COPY, 0, note = audioNote))
                    videoCopySegment(context, item, infos[i], audioCopy, seg) { pct, sample ->
                        onProgress(driver.tick(i, MergeStage.SEG_COPY, pct, sample, note = audioNote))
                    }
                } else {
                    val choices = choicesFor(plan, infos[i], hwAllowed && hwFailures < 2)
                    val res = transcodeWithTiers(context, item, infos[i], plan, choices, audioCopy, seg) { c, pct, sample, note ->
                        onProgress(
                            driver.tick(
                                i, MergeStage.SEG_TRANSCODE, pct, sample,
                                hwDecode = c.hwDecoder != null,
                                hwEncode = c.hwEncode,
                                note = note
                            )
                        )
                    }
                    if (res.choice.hwEncode) hwEncoded++
                    if (res.hwAttemptFailed) hwFailures++
                }
            }
            // 拼接：参数集逐字节一致的组直接拼（单 stsd，最快）。不一致时**不能**
            // 指望 ffmpeg 写多条 stsd——Android 的 MPEG4Extractor 对普通音视频轨
            // entry_count>1 直接 skipTrack（"we only support a single type of media
            // per track"），整条视频轨会消失；必须让每段关键帧自带参数集。
            val outTmp = File(cacheDir, uniqueTempName("拼接成品", ".mp4"))
            try {
                val totalMs = items.sumOf { it.durationMs }
                val report = { pct: Int ->
                    onProgress(
                        GroupProgress(
                            MergeStage.CONCAT,
                            percent = ProgressMath.stagePercent(MergeStage.CONCAT, pct)
                        )
                    )
                }
                if (segmentsShareCsd(context, segments)) {
                    ffmpegConcatCopy(segments, outTmp, totalMs, report)
                } else {
                    ffmpegConcatInBand(context, segments, outTmp, codec, totalMs, report)
                }
                onProgress(GroupProgress(MergeStage.PUBLISH, percent = 99))
                copyTmpToOut(context, outTmp, outUri)
            } finally {
                outTmp.delete()
            }
            val expectedDurationMs = items.sumOf { it.durationMs }
            onProgress(GroupProgress(MergeStage.VERIFY, percent = 98))
            verifyOutputUsable(
                context, outUri, expectedDurationMs, segmentCheckPoints(items), expectVideo = true
            )?.let { reason ->
                throw IllegalStateException("自检未通过：$reason")
            }
            return PartialResult(codec.id, copiedCount, hwEncoded)
        } finally {
            segments.forEach { runCatching { it.delete() } }
        }
    }
}

/**
 * 全量转码引擎：逐段独立转码成统一编码与画布（画布按时长多数；帧率逐段保留、
 * 成品为可变帧率；横竖混向按各自 rotation 烤平），再容器级拼接。
 * 返回实际使用的视频编码与硬编段数（供完成文案展示）。
 */
suspend fun transcodeConcat(
    context: Context,
    items: List<VideoItem>,
    infos: List<TrackInfo>,
    outUri: Uri,
    settings: TranscodeSettings,
    onProgress: (GroupProgress) -> Unit
): TranscodeResult {
    exportGate.withPermit {
        val durations = items.map { it.durationMs }
        val resolved = VideoCodec.ofId(resolveOutputCodec(infos, durations, settings))
        // 全量转码一定有段需要重编，目标必须可软编；否则退回兼容性最好的 H.264
        val codec = if (resolved.swEncodable) resolved else VideoCodec.H264
        val plan = buildPlan(codec, infos, durations, settings)
        val audioCopy = canCopyAudio(infos)
        val audioNote = if (audioCopy) "音频直拷" else "音频重编 AAC 192k"
        val hwAllowed = hardwareAllowed(settings, plan)
        val driver = SegDriver(items, infos, BooleanArray(items.size), codec.display)
        val cacheDir = context.cacheDir
        val segments = mutableListOf<File>()
        var hwEncoded = 0
        var hwFailures = 0
        try {
            for ((i, item) in items.withIndex()) {
                val seg = File(cacheDir, uniqueTempName("转码段", ".mp4"))
                segments += seg
                onProgress(driver.tick(i, MergeStage.SEG_TRANSCODE, 0, note = audioNote))
                val choices = choicesFor(plan, infos[i], hwAllowed && hwFailures < 2)
                val res = transcodeWithTiers(context, item, infos[i], plan, choices, audioCopy, seg) { c, pct, sample, note ->
                    onProgress(
                        driver.tick(
                            i, MergeStage.SEG_TRANSCODE, pct, sample,
                            hwDecode = c.hwDecoder != null,
                            hwEncode = c.hwEncode,
                            note = note
                        )
                    )
                }
                if (res.choice.hwEncode) hwEncoded++
                if (res.hwAttemptFailed) hwFailures++
            }
            val outTmp = File(cacheDir, uniqueTempName("转码成品", ".mp4"))
            try {
                // 参数集一致的组才敢用 mp4parser 快拼（它只写一份 stsd，正好符合
                // Android 的口径）；不一致时走带内参数集拼接——不能指望 ffmpeg 写
                // 多条 stsd，Android 的 MPEG4Extractor 会整条视频轨 skipTrack。
                val totalMs = items.sumOf { it.durationMs }
                val report = { pct: Int ->
                    onProgress(
                        GroupProgress(
                            MergeStage.CONCAT,
                            percent = ProgressMath.stagePercent(MergeStage.CONCAT, pct)
                        )
                    )
                }
                val uniform = segmentsShareCsd(context, segments)
                var appended = false
                if (uniform && codec == VideoCodec.H264) {
                    val midItems = segments.map { VideoItem(Uri.fromFile(it), it.name, 0) }
                    // 时基不齐（或同步帧表缺失）时 mp4parser 会拼出错误时长，
                    // 捕获后改走 ffmpeg 拼接，不让整个转码任务失败
                    appended = try {
                        ParcelFileDescriptor.open(
                            outTmp,
                            ParcelFileDescriptor.MODE_READ_WRITE or
                                ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE
                        ).use { pfd ->
                            concatLossless(context, midItems, pfd) { pct -> report(pct) }
                        }
                        true
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Exception) {
                        // 缺 stss、异常采样条目等都会让 mp4parser 抛 RuntimeException，
                        // 只挡 LosslessFallbackException 会让整个转码任务直接失败
                        android.util.Log.w("VideoStitcher", "转码段改用 ffmpeg 拼接：${t.message}")
                        false
                    }
                }
                if (!appended) {
                    if (uniform) ffmpegConcatCopy(segments, outTmp, totalMs, report)
                    else ffmpegConcatInBand(context, segments, outTmp, codec, totalMs, report)
                }
                onProgress(GroupProgress(MergeStage.PUBLISH, percent = 99))
                copyTmpToOut(context, outTmp, outUri)
            } finally {
                outTmp.delete()
            }
            val expectedDurationMs = items.sumOf { it.durationMs }
            onProgress(GroupProgress(MergeStage.VERIFY, percent = 98))
            // 全量转码同样按段中点抽帧：只抽 10/50/90% 时，中间某段整段花屏会漏过
            verifyOutputUsable(
                context, outUri, expectedDurationMs, segmentCheckPoints(items), expectVideo = true
            )?.let { reason ->
                throw IllegalStateException("自检未通过：$reason")
            }
            return TranscodeResult(codec.id, hwEncoded)
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
 */
suspend fun videoCopyConcatAudio(
    context: Context,
    items: List<VideoItem>,
    infos: List<TrackInfo>,
    outUri: Uri,
    onProgress: (GroupProgress) -> Unit
) {
    exportGate.withPermit {
        val cacheDir = context.cacheDir
        val audioFiles = mutableListOf<File>()
        val driver = SegDriver(items, infos, BooleanArray(items.size), "")
        try {
            for ((i, item) in items.withIndex()) {
                val af = File(cacheDir, uniqueTempName("音频段", ".m4a"))
                audioFiles.add(af)
                onProgress(driver.tick(i, MergeStage.AUDIO_REDO, 0))
                val input = FFmpegKitConfig.getSafParameterForRead(context, item.uri)
                if (infos[i].hasAudio) {
                    // 只重编音频（-vn 跳过视频解码），秒级完成
                    runFfmpeg(
                        "-y -i $input -vn $TRANSCODE_AUDIO '${af.absolutePath}'",
                        item.durationMs,
                        af
                    ) { s -> onProgress(driver.tick(i, MergeStage.AUDIO_REDO, s.percent, s)) }
                } else {
                    // 无声段生成等长静音轨，保证成品音轨连续
                    val secs = trimFps((if (item.durationMs > 0) item.durationMs else 1000L) / 1000f)
                    runFfmpeg(
                        "-y -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 -t $secs $TRANSCODE_AUDIO '${af.absolutePath}'",
                        0, af
                    )
                }
            }
            // 音频段已就绪，进入容器级合并（工作带末端）
            onProgress(GroupProgress(MergeStage.LOSSLESS, percent = ProgressMath.stagePercent(MergeStage.LOSSLESS, 100)))
            context.contentResolver.openFileDescriptor(outUri, "rw")!!.use { pfd ->
                val opened = mutableListOf<AutoCloseable>()
                try {
                    val out = Movie()
                    val videoMovies = items.map { loadMovie(context, it.uri, it.name, opened) }
                    out.matrix = videoMovies.first().matrix
                    val videoTracks = normalizeTimeScale(firstTrackOf(videoMovies, "vide"))
                        .also { requireUniformCtts(it) }
                    val audioMovies = audioFiles.map { loadMovie(context, Uri.fromFile(it), it.name, opened) }
                    val audioTracks = normalizeTimeScale(firstTrackOf(audioMovies, "soun"))
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
            onProgress(GroupProgress(MergeStage.VERIFY, percent = 98))
            verifyOutputUsable(
                context, outUri, expectedDurationMs, segmentCheckPoints(items), expectVideo = true
            )?.let { reason ->
                throw IllegalStateException("自检未通过：$reason")
            }
        } finally {
            audioFiles.forEach { runCatching { it.delete() } }
        }
    }
}

/**
 * 剥离外来轨（v1.5.7）：Android 13+ 录屏的 MP4 带两条 mett 时间元数据轨，字幕轨/
 * 时间码轨同类——mp4parser 解析到非媒体采样条目直接断言崩溃，media3 转封装产出
 * 0 时长成品，ffmpeg 的解码链也会被数据流带崩。-c copy 重封装只留视频+音频，
 * 不重编码秒级完成；失败按 false 返回，引擎链按老路走（不会更糟）。
 */
suspend fun stripForeignTracks(context: Context, uri: Uri, out: File): Boolean {
    val input = FFmpegKitConfig.getSafParameterForRead(context, uri)
    return try {
        // 只保留第一条视频/音频：-map 0:v -map 0:a? 会把双音轨、封面图这类附加轨一起
        // 带进产物，而 probeVideo 只记录第一条——两套口径不一致，转码段就会"探 A 拷 B"
        runFfmpeg(
            "-y -i $input -map 0:v:0 -map 0:a:0? -c copy '${out.absolutePath}'",
            0, out
        )
        true
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        android.util.Log.w("VideoStitcher", "strip foreign tracks failed", t)
        false
    }
}

private data class TierResult(val choice: EncChoice, val hwAttemptFailed: Boolean)

/**
 * 逐段编码的三级降级：硬解+硬编 → 硬解+软编 → 软解+软编。
 * 任一级失败就换下一级，全部失败才向上抛；硬编失败会被上层计数，
 * 同编码连续两次失败后本轮不再尝试硬编（避免每个段都白跑一次）。
 */
private suspend fun transcodeWithTiers(
    context: Context,
    item: VideoItem,
    info: TrackInfo,
    plan: TranscodePlan,
    choices: List<EncChoice>,
    audioCopy: Boolean,
    out: File,
    onTick: (EncChoice, Int, FfmpegSample?, String?) -> Unit
): TierResult {
    var last: Throwable? = null
    var hwFailed = false
    for ((idx, choice) in choices.withIndex()) {
        try {
            transcodeSegment(context, item, info, plan, choice, audioCopy, out) { pct, sample ->
                onTick(choice, pct, sample, null)
            }
            return TierResult(choice, hwFailed)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            last = t
            if (choice.hwEncode) hwFailed = true
            android.util.Log.w(
                "VideoStitcher",
                "encode tier ${choice.videoArgs.take(40)} failed for ${item.name}, trying next",
                t
            )
            if (idx < choices.lastIndex) {
                val next = choices[idx + 1]
                val note = when {
                    choice.hwEncode -> "硬件编码不可用，改用软编重试"
                    choice.hwDecoder != null -> "硬解失败，改用软解重试"
                    else -> "重新尝试"
                }
                onTick(next, 0, null, note)
            }
        }
    }
    throw last ?: IllegalStateException("转码失败")
}

/** 视频直拷段：像素不动，音频按需重编成 AAC 或生成静音，保证各段音轨结构一致 */
private suspend fun videoCopySegment(
    context: Context,
    item: VideoItem,
    info: TrackInfo,
    audioCopy: Boolean,
    out: File,
    onSample: (Int, FfmpegSample?) -> Unit
) {
    val input = FFmpegKitConfig.getSafParameterForRead(context, item.uri)
    val audioArgs = if (audioCopy && info.hasAudio) "-c:a copy" else TRANSCODE_AUDIO
    val cmd = if (info.hasAudio)
        "-y -i $input -map 0:v:0 -map 0:a:0 -c:v copy $audioArgs -sn -dn '${out.absolutePath}'"
    else
        "-y -i $input -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 " +
            "-map 0:v:0 -map 1:a -shortest -c:v copy $audioArgs -sn -dn '${out.absolutePath}'"
    runFfmpeg(cmd, item.durationMs, out) { s -> onSample(s.percent, s) }
}

/**
 * 单段转码。旋转交给 ffmpeg 的 autorotate（≥2.7 默认开启）：带旋转元数据的输入会被自动
 * 转正、输出不再携带旋转标记，targetW/H 是探测出的显示方向尺寸，天然对齐。
 * 尺寸一致时跳过缩放滤镜：源像素不经历重采样，少一次画质损失还提速。
 * 帧率永远不强制（2.0.4）：见下方滤镜链注释。
 */
private suspend fun transcodeSegment(
    context: Context,
    item: VideoItem,
    info: TrackInfo,
    plan: TranscodePlan,
    choice: EncChoice,
    audioCopy: Boolean,
    out: File,
    onSample: (Int, FfmpegSample?) -> Unit
) {
    val input = FFmpegKitConfig.getSafParameterForRead(context, item.uri)
    val dimsMatch = info.width == plan.width && info.height == plan.height
    val filters = buildList {
        if (!dimsMatch) {
            add("scale=${plan.width}:${plan.height}:force_original_aspect_ratio=decrease")
            add("pad=${plan.width}:${plan.height}:(ow-iw)/2:(oh-ih)/2")
        }
        // setsar=1 必须保留：不同相机的 SAR 不一致会让播放器把画面拉伸
        add("setsar=1")
        // 刻意不加 fps= 滤镜（2.0.4）：它会把帧率偏离"时长多数"的段强制压到
        // 多数帧率——60fps 素材混进 30fps 组直接丢半帧，成品肉眼可见卡顿。
        // 逐段保留源时间戳、输出可变帧率即可；无损拼接早已是同一口径
        // （见 TrackInfo.matchesVideoOnly 上方注释），各播放器均支持
    }.joinToString(",")
    // 无声段补静音轨，保证拼出的成品音轨连续
    val audioIn = if (info.hasAudio) ""
    else " -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000"
    val audioArgs = if (audioCopy && info.hasAudio) "-c:a copy" else TRANSCODE_AUDIO
    // 必须锁流号：ffmpeg 默认按"声道最多"挑音轨，和 probeVideo 记录的第一条不一定是
    // 同一条（双音轨文件会把 AC3 当成可直拷的 AAC 拷进 MP4）；而 -map 0:v 会把封面图
    // 这类附加视频流一起映射，拼出带两条视频轨的段，后面 concat 直接错位
    val maps = if (info.hasAudio) "-map 0:v:0 -map 0:a:0"
    else "-map 0:v:0 -map 1:a -shortest"
    // 硬解作为输入解码器（-c:v 在 -i 前是输入选项）：解码不影响输出码流，
    // 省下的 CPU 全部让给编码器；失败由调用方降级重跑。
    // -filter_threads 0 = 滤镜（scale/pad 的 swscale）按 CPU 数切片多线程，
    // 高分辨率源上缩放曾是最长的单线程段
    val decodeOpt = choice.hwDecoder?.let { "-c:v $it " } ?: ""
    val cmd = "-y -filter_threads 0 ${decodeOpt}-i $input$audioIn $maps -vf $filters " +
        "${choice.videoArgs} $audioArgs -sn -dn '${out.absolutePath}'"
    runFfmpeg(cmd, item.durationMs, out) { s -> onSample(s.percent, s) }
}

/**
 * 容器级拼接（**仅限各段参数集逐字节一致**）。
 * ffmpeg 8.x 的 mov 复用器确实会为不同 extradata 写多条 stsd，但 Android 的
 * MPEG4Extractor 只支持单条：entry_count>1 时对普通音视频轨直接 skipTrack。
 * 所以这里必须由调用方先用 [segmentsShareCsd] 把关；不一致走 [ffmpegConcatInBand]。
 * +faststart 会对整个文件做一次重写（把 moov 挪到头部），大文件代价明显，
 * 因此只在小成品上启用；本地相册播放不需要它。
 */
private suspend fun ffmpegConcatCopy(
    segments: List<File>,
    out: File,
    durationMs: Long = 0,
    onPercent: (Int) -> Unit = {}
) {
    val list = File(out.parentFile, uniqueTempName("concat", ".txt"))
    try {
        list.writeText(
            segments.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" }
        )
        val totalBytes = segments.sumOf { runCatching { it.length() }.getOrDefault(0L) }
        val faststart = if (totalBytes <= FASTSTART_MAX_BYTES) "-movflags +faststart " else ""
        runFfmpeg(
            "-y -f concat -safe 0 -i '${list.absolutePath}' -c copy ${faststart}'${out.absolutePath}'",
            durationMs,
            out,
            stallTimeoutMs = 600_000L,
            wallTimeoutMs = 6L * 3600 * 1000
        ) { s -> onPercent(s.percent) }
    } finally {
        list.delete()
    }
}

/** 小于该体积才加 faststart：重写一遍文件的代价，大成品上不如直接跳转播放 */
private const val FASTSTART_MAX_BYTES = 256L * 1024 * 1024

/**
 * 各段产物的视频解码配置是否逐字节一致（csd-0 = avcC / hvcC / av1C / vpcC）。
 *
 * 这是"能不能直接 `-c copy` 拼成 MP4"的唯一判据：MP4 一条轨道只有一份 stsd，
 * 而 Android 的 MPEG4Extractor 对 entry_count>1 的普通音视频轨直接 skipTrack
 * （源码注释："For now we only support a single type of media per track"）。
 * 只要有一段的 csd 不同，就必须走 [ffmpegConcatInBand] 把参数集塞进码流里。
 */
private fun segmentsShareCsd(context: Context, segments: List<File>): Boolean {
    if (segments.size <= 1) return true
    val csds = ArrayList<ByteArray?>(segments.size)
    for (seg in segments) {
        csds.add(runCatching { probeVideo(context, Uri.fromFile(seg)).csd }.getOrNull())
    }
    // 全都没有 csd 的编码（部分 VP8/VP9 裸流）没有"切换参数集"的概念，直接拼
    if (csds.all { it == null }) return true
    val first = csds.firstOrNull() ?: return false
    return csds.all { it != null && it.contentEquals(first) }
}

/**
 * 带内参数集拼接支持的编码：只有能可靠转成 MPEG-TS 的这几种。
 *
 * AV1 刻意不在列表里：FFmpeg 给 AV1 的 extradata 是 **av1C 记录**（首字节 0x81），
 * 不是裸 sequence header OBU，`dump_extra` 插进去会得到 "obu_forbidden_bit out of
 * range"（实测 MKV/MP4 都直接失败）。而 mpegts 复用器也不认 AV1（会降级成
 * private data）。所以 AV1 组一旦出现参数集不一致，只能整组重编。
 */
private fun inBandCapable(codec: VideoCodec): Boolean =
    codec == VideoCodec.H264 || codec == VideoCodec.H265 || codec == VideoCodec.MPEG4

/**
 * 带内参数集拼接（保留"只转异编码段"的唯一可行路线）。
 *
 * 背景：MP4 一条轨道只有一份 stsd；FFmpeg 8.x 虽然能写多条，但 Android 的
 * MPEG4Extractor 遇到 entry_count>1 会把整条普通音视频轨 skipTrack
 * （"For now we only support a single type of media per track"）。
 * 所以参数集不同的段不能直接拼，必须让每段的关键帧在码流里自带 SPS/PPS：
 *
 *  · H.264 / H.265 / MPEG-4：逐段 `-f mpegts` 重封装。mpegts 复用器会自动插入
 *    h264_mp4toannexb / hevc_mp4toannexb，把**该段自己的** extradata（SPS/PPS）
 *    写进段首；再把各 TS 部分二进制直拼成连续流、按单输入重封装回 MP4（官方
 *    wiki 配方，2.0.3 起替换 concat 分离器——分离器要按估算时长推算各段起点，
 *    B 帧边界上多次产出非单调 DTS）。回到 MP4 时 mov 只把起始码换成长度前缀、
 *    不剥带内参数集，解码器会在每个 IDR 前重新初始化。
 *  · AV1/VP9/VP8 走不了这条路（见 [inBandCapable]），由调用方提前判负、整组重编。
 *
 * 关键点：**每段单独转**。整组一起转时 mp4toannexb 只认第一段的 extradata，
 * 后面段的参数集根本不会出现。
 */
private suspend fun ffmpegConcatInBand(
    context: Context,
    segments: List<File>,
    out: File,
    codec: VideoCodec,
    durationMs: Long = 0,
    onPercent: (Int) -> Unit = {}
) {
    if (!inBandCapable(codec)) {
        throw IllegalStateException("各段参数集不一致，且 ${codec.display} 不支持带内参数集拼接")
    }
    val work = File(out.parentFile, uniqueTempName("带内拼接", ""))
    if (!work.mkdirs()) throw IllegalStateException("无法创建带内拼接临时目录")
    val parts = mutableListOf<File>()
    val sizes = segments.map { max(it.length(), 1L) }
    val totalBytes = sizes.sum()
    var doneBytes = 0L
    try {
        for ((i, seg) in segments.withIndex()) {
            val part = File(work, "seg$i.ts")
            // 每段单独转一次：mp4toannexb 只认该段自己的 extradata，
            // 整组一起转只会带上第一段的 SPS/PPS
            runFfmpeg(
                "-y -i '${seg.absolutePath}' -map 0:v:0 -map 0:a:0? -c copy " +
                    "-f mpegts '${part.absolutePath}'",
                0, part, stallTimeoutMs = 600_000L
            )
            parts += part
            // 分段 MP4 已经用完（失败就整组降级全量转码），及时删掉：否则
            // 分段 + 带内中间件 + 成品会同时占三份空间
            runCatching { seg.delete() }
            doneBytes += sizes[i]
            onPercent((doneBytes * 70 / totalBytes).toInt().coerceIn(0, 70))
        }
        val totalPartBytes = parts.sumOf { runCatching { it.length() }.getOrDefault(0L) }
        val faststart = if (totalPartBytes <= FASTSTART_MAX_BYTES) "-movflags +faststart " else ""
        // 2.0.3：最终拼装从 concat 分离器改为官方 wiki 配方的"协议级"形态——
        // 把各 TS 部分二进制直拼成一条连续流、按单输入重封装回 MP4。
        // 分离器要按"每段估算时长"推算下一段起点（TS 无索引，估不准，且对
        // start_time/B 帧的对齐在真机 ffmpeg 上多次翻车：trac #4924）；单输入
        // 则完全由实际包的 DTS/PTS 重建时间轴，没有按段累加偏移的环节
        val allTs = File(work, "all.ts")
        runCatching {
            java.io.FileOutputStream(allTs).use { sink ->
                for (p in parts) java.io.FileInputStream(p).use { it.copyTo(sink, 1 shl 16) }
            }
        }.onFailure { throw IllegalStateException("TS 中转文件拼接失败：${it.message}") }
        runFfmpeg(
            "-y -f mpegts -i '${allTs.absolutePath}' -map 0:v:0 -map 0:a:0? -c copy " +
                "-bsf:a aac_adtstoasc -avoid_negative_ts make_zero ${faststart}'${out.absolutePath}'",
            durationMs, out, stallTimeoutMs = 600_000L,
            wallTimeoutMs = 6L * 3600 * 1000
        ) { s -> onPercent(70 + s.percent * 30 / 100) }
        runCatching { allTs.delete() }
    } finally {
        parts.forEach { runCatching { it.delete() } }
        runCatching { work.deleteRecursively() }
    }
}

/**
 * 同步等待一场 ffmpeg 执行；durationMs>0 时按已处理时长回报进度。
 * 看门狗：媒体时间前进**或输出文件变大**都算有进展——`-movflags +faststart`
 * 的第二遍搬运阶段没有统计输出，只看 stats 会把大文件的正常搬运误判成卡死。
 * 另设墙钟上限兜住"文件在长但永远写不完"的极端情况。
 */
private suspend fun runFfmpeg(
    command: String,
    durationMs: Long,
    output: File? = null,
    stallTimeoutMs: Long = 180_000L,
    wallTimeoutMs: Long = 0L,
    onSample: (FfmpegSample) -> Unit = {}
): Unit = withContext(Dispatchers.IO) {
    coroutineScope {
        val finished = CompletableDeferred<FFmpegSession>()
        // 统计回调在 ffmpegkit 的线程、看门狗轮询在协程线程：基准值用原子量传递。
        // 反复回调同一个卡死的时间点不会不断重置看门狗
        val lastMediaBits = AtomicLong(Double.NaN.toRawBits())
        // 看门狗一律用 uptimeMillis（不计深度睡眠）：退出应用熄屏后 CPU 挂起，
        // ffmpeg 统计回调随之停摆，而墙钟照走——用 currentTimeMillis 会在设备唤醒
        // 的瞬间算出"已 180 秒无进展"，把一次完全正常的拼接误杀（1.9.2 后台保活
        // 失效的直接原因之一）。
        val lastAdvanceMs = AtomicLong(SystemClock.uptimeMillis())
        val lastPct = AtomicInteger(-1)
        val lastEmitMs = AtomicLong(0L)
        val startedAt = SystemClock.uptimeMillis()
        val session = FFmpegKit.executeAsync(
            command,
            { s -> finished.complete(s) },
            null,
            { stats ->
                val bits = stats.time.toRawBits()
                if (lastMediaBits.getAndSet(bits) != bits) {
                    lastAdvanceMs.set(SystemClock.uptimeMillis())
                }
                val pct = if (durationMs > 0)
                    (stats.time * 100.0 / durationMs).toInt().coerceIn(0, 99) else 0
                val now = SystemClock.uptimeMillis()
                // 400ms 或 1% 变化才上报：转码期间每秒几十次回调拼字符串、
                // 发通知会直接抢走编码器的 CPU 时间
                if (pct != lastPct.getAndSet(pct) || now - lastEmitMs.get() >= 400) {
                    lastEmitMs.set(now)
                    onSample(
                        FfmpegSample(
                            percent = pct,
                            timeMs = stats.time.toLong(),
                            speed = stats.speed,
                            fps = stats.videoFps,
                            bitrateKbps = stats.bitrate.toInt(),
                            sizeBytes = stats.size
                        )
                    )
                }
            }
        )
        val watchdog = launch {
            var lastSize = -1L
            while (isActive && !finished.isCompleted) {
                delay(2_000)
                if (finished.isCompleted) break
                val now = SystemClock.uptimeMillis()
                if (output != null) {
                    val len = runCatching { output.length() }.getOrDefault(0L)
                    if (len > lastSize) {
                        lastSize = len
                        lastAdvanceMs.set(now)
                    }
                }
                if (now - lastAdvanceMs.get() > stallTimeoutMs) {
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
                if (wallTimeoutMs > 0 && now - startedAt > wallTimeoutMs) {
                    runCatching { FFmpegKit.cancel(session.sessionId) }
                    finished.completeExceptionally(
                        IllegalStateException("ffmpeg 超过 ${wallTimeoutMs / 60_000} 分钟仍未完成，已自动中止")
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
            // 2.0.3：成功执行也可能藏着时戳告警（非单调 DTS 被自动"纠正"）——
            // 记进 logcat 供事后诊断，不因此判失败（成品由自检的包络检查把关）
            runCatching { s.allLogsAsString }.getOrNull()?.let { logs ->
                if (logs.contains("on-monoton")) {
                    android.util.Log.w(
                        "VideoStitcher",
                        "ffmpeg 输出含非单调时戳告警（已自动纠正，成品待自检）：" +
                            "cmd: ${command.take(160)}"
                    )
                }
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
        var lastChangeMs = SystemClock.uptimeMillis() // 与 runFfmpeg 同理：不能用墙钟
        try {
            while (!finished.isCompleted) {
                withContext(Dispatchers.Main) {
                    runCatching { transformer.getProgress(holder) }
                }
                if (holder.progress != lastProgress) {
                    lastProgress = holder.progress
                    lastChangeMs = SystemClock.uptimeMillis()
                }
                if (holder.progress in 1..99) onProgress(holder.progress)
                // 看门狗：进度长时间不动（个别 AV1/特殊流卡死解码管线，media3 自带的
                // muxer 停顿超时盖不住所有情况）时主动取消，上层引擎链降级下一档方式，
                // 而不是进度 0% 永远挂起
                if (SystemClock.uptimeMillis() - lastChangeMs > 180_000L) {
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

/**
 * 在 MediaStore 创建输出条目（相册 Movies/VideoStitcher），自动避免重名覆盖。
 * API 29+ 用 IS_PENDING 标记"仍在写入"：进程被杀/任务被取消时，相册里不会
 * 出现 0 字节或半截的 MP4——旧实现会在用户相册留下解不动的残缺文件。
 */
fun createOutputUri(context: Context, desiredName: String): Uri {
    val name = uniqueVideoName(context, desiredName)
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoStitcher")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("无法创建输出文件")
}

/** 成品写完：清掉 IS_PENDING，相册才会真正收录这条视频 */
fun publishOutput(context: Context, uri: Uri) {
    if (Build.VERSION.SDK_INT < 29) return
    runCatching {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }
}

/** 失败/取消：删掉半成品条目（pending 条目不会出现在相册，但会占空间） */
fun discardOutput(context: Context, uri: Uri) {
    runCatching { context.contentResolver.delete(uri, null, null) }
}

/** 启动清理：上次进程被杀留下的 pending 条目（只清本应用目录下的） */
fun cleanupPendingOutputs(context: Context) {
    if (Build.VERSION.SDK_INT < 29) return
    runCatching {
        val sel = "${MediaStore.MediaColumns.IS_PENDING}=1 AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        context.contentResolver.delete(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, sel, arrayOf("Movies/VideoStitcher%")
        )
    }
}

private fun uniqueVideoName(context: Context, desired: String): String {
    val existing = mutableSetOf<String>()
    val base = desired.removeSuffix(".mp4")
    runCatching {
        // 只查本组目标名与 "名字 (n).mp4" 形态，不再把整个相册的 DISPLAY_NAME 全拉一遍
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
            "${MediaStore.Video.Media.DISPLAY_NAME} = ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf(desired, "$base (%"),
            null
        )?.use { c ->
            val idx = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            while (c.moveToNext()) existing.add(c.getString(idx) ?: "")
        }
    }
    if (desired !in existing) return desired
    var i = 1
    while ("$base ($i).mp4" in existing) i++
    return "$base ($i).mp4"
}

/* ---------- 磁盘空间 ---------- */

/**
 * 磁盘空间预检（成品输出卷）：成品整文件写出，转封装还要先写临时件再整块
 * 拷贝（峰值约 2×）；素材体积只是下限参考（crf 转码输出可能比源更大）。
 * 不足时开工前就报清楚，而不是半路 IOException 被误归成"格式不兼容"。
 */
fun checkDiskSpace(context: Context, items: List<VideoItem>) {
    val dir = context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory() ?: return
    val available = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
    var needBytes = 0L
    for (item in items) {
        runCatching {
            context.contentResolver.openFileDescriptor(item.uri, "r")?.use { needBytes += it.statSize }
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
fun checkInternalDiskSpace(context: Context, items: List<VideoItem>) {
    val available = runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
    var needBytes = 0L
    for (item in items) {
        runCatching {
            context.contentResolver.openFileDescriptor(item.uri, "r")?.use { needBytes += it.statSize }
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
