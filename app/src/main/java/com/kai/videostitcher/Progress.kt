package com.kai.videostitcher

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 拼接阶段。UI 文案、进度带、完成态全部由这一套枚举驱动，
 * 不再靠"字符串以 ✓/✗ 开头"来猜语义（旧实现改一个字就会配色错乱）。
 */
enum class MergeStage {
    READ_CHECK,     // 检查可读性 / 授权
    PROBE,          // 探测编码参数
    STRIP,          // 剥离外来数据轨
    LOSSLESS,       // mp4parser 容器级拼接
    TRANSMUX,       // media3 无损转封装
    AUDIO_REDO,     // 视频不动、音频重编
    SEG_COPY,       // 单段视频直拷（+ 音频重编）
    SEG_TRANSCODE,  // 单段真正重编码
    CONCAT,         // ffmpeg 容器级拼接
    VERIFY,         // 成品自检
    PUBLISH,        // 写入相册
    DONE, FAILED, STOPPED
}

/** ffmpeg 统计回调的采样值：只保留 UI 需要的量，避免把 Statistics 对象带出引擎线程 */
data class FfmpegSample(
    val percent: Int,
    val timeMs: Long,
    val speed: Double,
    val fps: Float,
    val bitrateKbps: Int,
    val sizeBytes: Long
)

/** 当前正在处理的单个视频段 */
data class SegmentProgress(
    val index: Int,            // 1-based
    val total: Int,
    val name: String,
    val srcCodec: String,      // 显示名，如 "H.264"
    val dstCodec: String,
    val copying: Boolean,      // true = 视频直拷，不重编码
    val percent: Int,
    val sample: FfmpegSample? = null,
    val hwDecode: Boolean = false,
    val hwEncode: Boolean = false,
    /** 段级补充说明，如"音频重编 AAC 192k" / "硬解失败，改用软解" */
    val note: String? = null
)

/**
 * 一组拼接的完整状态。引擎只产出这个对象，文案由 [StatusText] 在 UI 层统一生成，
 * 百分比由 [ProgressMath] 计算——三层分开，单测只需覆盖纯函数。
 */
data class GroupProgress(
    val stage: MergeStage,
    val seg: SegmentProgress? = null,
    val percent: Int = 0,
    val etaMs: Long? = null,
    /** DONE/FAILED 时为结果描述；RUNNING 时为次要说明 */
    val note: String? = null
) {
    val finished: Boolean
        get() = stage == MergeStage.DONE || stage == MergeStage.FAILED || stage == MergeStage.STOPPED
}

/** 整轮拼接的总体状态：总进度 + "第 2/3 组"这类上下文 */
data class OverallProgress(val percent: Int?, val text: String)

object StatusText {

    /** 主行：说清"在干什么、第几段、什么编码转什么编码" */
    fun title(p: GroupProgress): String {
        val seg = p.seg
        return when (p.stage) {
            MergeStage.READ_CHECK -> "检查视频可读性"
            MergeStage.PROBE -> "分析视频参数"
            MergeStage.STRIP -> seg?.let { "清理附加数据轨 第 ${it.index}/${it.total} 个" } ?: "清理附加数据轨"
            MergeStage.LOSSLESS -> "无损拼接${seg?.let { " 第 ${it.index}/${it.total} 段" } ?: ""}"
            MergeStage.TRANSMUX -> "无损转封装"
            MergeStage.AUDIO_REDO -> seg?.let {
                "音频重编 第 ${it.index}/${it.total} 段「${shortName(it.name)}」"
            } ?: "音频重编"
            MergeStage.SEG_COPY -> seg?.let {
                "视频 ${it.index}/${it.total}「${shortName(it.name)}」${it.srcCodec} 直拷"
            } ?: "视频直拷"
            MergeStage.SEG_TRANSCODE -> seg?.let {
                "视频 ${it.index}/${it.total}「${shortName(it.name)}」${it.srcCodec} → ${it.dstCodec}"
            } ?: "转码中"
            MergeStage.CONCAT -> "拼接成品"
            MergeStage.VERIFY -> "成品自检"
            MergeStage.PUBLISH -> "写入相册"
            MergeStage.DONE -> "✓ ${p.note ?: "完成"}"
            MergeStage.FAILED -> "✗ ${p.note ?: "失败"}"
            MergeStage.STOPPED -> "已停止"
        }
    }

    /** 次行：进度、速度、剩余时间、解码方式 */
    fun detail(p: GroupProgress): String {
        val seg = p.seg
        val sb = StringBuilder()
        when (p.stage) {
            MergeStage.SEG_TRANSCODE, MergeStage.SEG_COPY, MergeStage.AUDIO_REDO -> {
                if (seg != null) {
                    val verb = when {
                        p.stage == MergeStage.SEG_COPY -> "直拷中"
                        p.stage == MergeStage.AUDIO_REDO -> "音频重编"
                        else -> "转码中"
                    }
                    sb.append(verb).append(' ').append(seg.percent).append('%')
                    val speed = seg.sample?.speed ?: 0.0
                    if (p.stage == MergeStage.SEG_TRANSCODE && speed > 0.05) {
                        sb.append(" · ").append(speedText(speed))
                    }
                    p.etaMs?.let { sb.append(" · 剩余约 ").append(etaText(it)) }
                    if (p.stage == MergeStage.SEG_TRANSCODE) {
                        if (seg.hwEncode) sb.append(" · 硬编")
                        else if (seg.hwDecode) sb.append(" · 硬解")
                    }
                    seg.note?.let { if (it.isNotBlank()) sb.append(" · ").append(it) }
                }
            }
            MergeStage.LOSSLESS -> {
                sb.append("容器层追加，不重新编码")
                if (p.percent > 0) sb.append(" · ").append(p.percent).append('%')
            }
            MergeStage.TRANSMUX -> sb.append("只换封装，不重新编码 · ").append(p.percent).append('%')
            MergeStage.CONCAT -> sb.append("容器级拼接，不重新编码").append(" · ").append(p.percent).append('%')
            MergeStage.VERIFY -> sb.append("抽帧 + 采样表索引检查")
            MergeStage.PUBLISH -> sb.append(p.percent).append('%')
            MergeStage.STRIP -> sb.append("无损剥离，不重编码")
            MergeStage.PROBE -> sb.append("并行探测编码、分辨率、方向与音频")
            MergeStage.READ_CHECK -> sb.append("授权失效或已被移动的视频会在这里提前发现")
            MergeStage.DONE, MergeStage.FAILED, MergeStage.STOPPED -> sb.append(p.note.orEmpty())
        }
        return sb.toString()
    }

    fun oneLine(p: GroupProgress): String {
        val t = title(p)
        val d = detail(p)
        return if (d.isBlank() || p.stage == MergeStage.DONE || p.stage == MergeStage.FAILED) t
        else "$t · $d"
    }

    /** 视频行内的短状态（不换行、不抢注意力） */
    fun rowText(p: GroupProgress): String? {
        val seg = p.seg ?: return null
        return when (p.stage) {
            MergeStage.SEG_TRANSCODE -> buildString {
                append("转码中 ").append(seg.percent).append('%')
                val s = seg.sample?.speed ?: 0.0
                if (s > 0.05) append(" · ").append(speedText(s))
            }
            MergeStage.SEG_COPY -> "直拷中 ${seg.percent}%"
            MergeStage.AUDIO_REDO -> "音频重编 ${seg.percent}%"
            else -> null
        }
    }

    /** 文件名过长时截断：卡片和通知都要放得下 */
    fun shortName(name: String, max: Int = 18): String =
        if (name.length <= max) name else name.take(max - 1) + "…"

    fun speedText(x: Double): String =
        if (!x.isFinite() || x <= 0.05 || x > 999) "" else String.format(Locale.US, "%.1f×", x)

    fun etaText(ms: Long): String = ProgressMath.formatEta(ms)
}

/**
 * 百分比与 ETA 的纯函数集合。
 * 阶段带宽固定为：准备 0-10、逐段 10-94、拼接 94-97、自检 97-99、写入 99-100，
 * 这样进度条不会在"段切换"时来回跳，也不会在收尾阶段长时间卡 99%。
 */
object ProgressMath {

    const val SEGMENT_BASE = 10
    const val SEGMENT_SPAN = 84

    fun stagePercent(stage: MergeStage, pct: Int): Int {
        val p = pct.coerceIn(0, 100)
        return when (stage) {
            MergeStage.READ_CHECK -> p * 3 / 100
            MergeStage.PROBE -> 3 + p * 3 / 100
            MergeStage.STRIP -> 6 + p * 4 / 100
            // 整组一次性完成的工作（无损拼接/转封装）也走 10-94 的工作带，
            // 这样从"分析参数 6%"进入时进度条不会突然掉回 0
            MergeStage.LOSSLESS, MergeStage.TRANSMUX,
            MergeStage.SEG_COPY, MergeStage.SEG_TRANSCODE, MergeStage.AUDIO_REDO ->
                SEGMENT_BASE + SEGMENT_SPAN * p / 100
            MergeStage.CONCAT -> 94 + p * 3 / 100
            MergeStage.VERIFY -> 97 + p * 2 / 100
            MergeStage.PUBLISH -> 99 + p / 100
            MergeStage.DONE -> 100
            else -> p
        }
    }

    /**
     * 段权重：直拷段接近零耗时，权重取 0.1，否则一次秒拷会吃掉整条进度条。
     * 只依赖时长（不依赖 VideoItem），便于纯 JVM 单测。
     */
    fun weights(durationsMs: List<Long>, copying: BooleanArray): DoubleArray =
        DoubleArray(durationsMs.size) { i ->
            val d = max(durationsMs[i].toDouble(), 1500.0)
            if (copying.getOrElse(i) { false }) d * 0.1 else d
        }

    /** 逐段循环内的整组进度：10% 起步，按已完成段的时长加权推进到 94% */
    fun segmentPercent(weights: DoubleArray, index: Int, segPct: Int): Int {
        val total = weights.sum()
        if (total <= 0.0 || weights.isEmpty()) return SEGMENT_BASE
        var done = 0.0
        for (i in 0 until index.coerceIn(0, weights.size)) done += weights[i]
        val cur = weights.getOrElse(index) { 0.0 } * segPct.coerceIn(0, 100) / 100.0
        val ratio = ((done + cur) / total).coerceIn(0.0, 1.0)
        return (SEGMENT_BASE + SEGMENT_SPAN * ratio).roundToInt().coerceIn(SEGMENT_BASE, 94)
    }

    /** 段内剩余时间：speed = 每秒编码多少秒媒体，0 或未知时不显示 */
    fun etaMs(durationMs: Long, pct: Int, speed: Double): Long? {
        if (durationMs <= 0 || pct >= 100) return null
        if (!speed.isFinite() || speed <= 0.05) return null
        val remainMedia = durationMs.toDouble() * (100 - pct.coerceIn(0, 100)) / 100.0
        return (remainMedia / speed).toLong().coerceIn(0L, 24L * 3600 * 1000)
    }

    /** 整轮总进度：按组总时长加权，避免长组被短组平均掉 */
    fun overall(weights: List<Double>, percents: List<Int?>): Int? {
        if (weights.isEmpty()) return null
        var sum = 0.0
        var total = 0.0
        for (i in weights.indices) {
            val p = (percents.getOrNull(i) ?: 0).coerceIn(0, 100)
            sum += weights[i] * p / 100.0
            total += weights[i]
        }
        if (total <= 0.0) return null
        return (sum / total * 100).roundToInt().coerceIn(0, 100)
    }

    fun formatEta(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
