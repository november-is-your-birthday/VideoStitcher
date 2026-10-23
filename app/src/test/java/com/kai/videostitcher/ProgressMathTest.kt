package com.kai.videostitcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度纯逻辑验证：权重、跨段百分比、阶段带宽、ETA、总进度、文案。
 * 这些函数不碰 Android API，可以直接在 JVM 上跑。
 */
class ProgressMathTest {

    @Test
    fun 直拷段权重只占十分之一() {
        val w = ProgressMath.weights(listOf(10_000L, 10_000L), booleanArrayOf(false, true))
        assertEquals(10_000.0, w[0], 0.01)
        assertEquals(1_000.0, w[1], 0.01)
    }

    @Test
    fun 时长为零时用下限避免除零() {
        val w = ProgressMath.weights(listOf(0L), booleanArrayOf(false))
        assertEquals(1_500.0, w[0], 0.01)
    }

    @Test
    fun 逐段进度单调递增且收尾恰好94() {
        val w = ProgressMath.weights(
            listOf(10_000L, 30_000L, 20_000L),
            booleanArrayOf(false, false, false)
        )
        var prev = ProgressMath.SEGMENT_BASE - 1
        for (i in 0..2) {
            for (p in 0..100) {
                val v = ProgressMath.segmentPercent(w, i, p)
                assertTrue("i=$i p=$p v=$v prev=$prev", v in 10..94 && v >= prev)
                prev = v
            }
        }
        assertEquals(94, prev)
    }

    @Test
    fun 空权重不崩() {
        assertEquals(10, ProgressMath.segmentPercent(DoubleArray(0), 0, 50))
    }

    @Test
    fun 阶段带宽首尾相接() {
        assertEquals(0, ProgressMath.stagePercent(MergeStage.READ_CHECK, 0))
        assertEquals(3, ProgressMath.stagePercent(MergeStage.READ_CHECK, 100))
        assertEquals(3, ProgressMath.stagePercent(MergeStage.PROBE, 0))
        assertEquals(6, ProgressMath.stagePercent(MergeStage.PROBE, 100))
        assertEquals(10, ProgressMath.stagePercent(MergeStage.STRIP, 100))
        assertEquals(94, ProgressMath.stagePercent(MergeStage.CONCAT, 0))
        assertEquals(97, ProgressMath.stagePercent(MergeStage.CONCAT, 100))
        assertEquals(99, ProgressMath.stagePercent(MergeStage.VERIFY, 100))
        assertEquals(100, ProgressMath.stagePercent(MergeStage.PUBLISH, 100))
        assertEquals(100, ProgressMath.stagePercent(MergeStage.DONE, 0))
    }

    @Test
    fun 总进度按时长加权而不是按组个数() {
        // 长组已完成、短组未开始：整体应接近 90% 而不是 50%
        val v = ProgressMath.overall(listOf(900.0, 100.0), listOf(100, 0))
        assertEquals(90, v)
        assertNull(ProgressMath.overall(emptyList(), emptyList()))
    }

    @Test
    fun 剩余时间按编码速度推算() {
        // 60 秒素材、编码到一半、2 倍速 → 还需 15 秒
        assertEquals(15_000L, ProgressMath.etaMs(60_000, 50, 2.0))
        assertNull(ProgressMath.etaMs(60_000, 50, 0.0))
        assertNull(ProgressMath.etaMs(60_000, 100, 2.0))
        assertNull(ProgressMath.etaMs(0, 10, 2.0))
    }

    @Test
    fun 转码主行说清第几段与源目标编码() {
        val seg = SegmentProgress(
            index = 2, total = 4, name = "IMG_0032.mp4",
            srcCodec = "H.264", dstCodec = "AV1", copying = false, percent = 37,
            sample = FfmpegSample(37, 12_000, 1.8, 54f, 4_000, 1_024),
            hwDecode = true
        )
        val p = GroupProgress(MergeStage.SEG_TRANSCODE, seg, percent = 45, etaMs = 130_000)

        assertEquals("视频 2/4「IMG_0032.mp4」H.264 → AV1", StatusText.title(p))
        val detail = StatusText.detail(p)
        assertTrue(detail, detail.contains("转码中 37%"))
        assertTrue(detail, detail.contains("1.8×"))
        assertTrue(detail, detail.contains("剩余约 2:10"))
        assertTrue(detail, detail.contains("硬解"))
        assertTrue(StatusText.oneLine(p).startsWith("视频 2/4"))
    }

    @Test
    fun 直拷行文案标明不重编码() {
        val seg = SegmentProgress(
            index = 1, total = 3, name = "b.mp4",
            srcCodec = "H.265", dstCodec = "H.265", copying = true, percent = 60,
            note = "音频重编 AAC 192k"
        )
        val p = GroupProgress(MergeStage.SEG_COPY, seg, percent = 30)
        assertTrue(StatusText.title(p).contains("直拷"))
        assertTrue(StatusText.detail(p).contains("直拷中 60%"))
        assertEquals("直拷中 60%", StatusText.rowText(p))
    }

    @Test
    fun 完成与失败文案走枚举而不是字符串前缀() {
        assertEquals("✓ 完成（无损拼接）", StatusText.title(GroupProgress(MergeStage.DONE, note = "完成（无损拼接）")))
        assertTrue(StatusText.title(GroupProgress(MergeStage.FAILED, note = "失败：磁盘已满")).startsWith("✗"))
        assertEquals("已停止", StatusText.title(GroupProgress(MergeStage.STOPPED)))
    }

    @Test
    fun 长文件名截断但仍能认出是哪个文件() {
        val long = "abcdefghijklmnopqrstuvwxyz.mp4"
        val out = StatusText.shortName(long, 18)
        assertEquals(18, out.length)
        assertTrue(out.endsWith("…"))
        assertEquals("短名.mp4", StatusText.shortName("短名.mp4", 18))
    }

    @Test
    fun 速度异常值不显示() {
        assertEquals("", StatusText.speedText(0.0))
        assertEquals("", StatusText.speedText(Double.NaN))
        assertEquals("", StatusText.speedText(5000.0))
        assertTrue(StatusText.speedText(1.84).endsWith("×"))
    }
}
