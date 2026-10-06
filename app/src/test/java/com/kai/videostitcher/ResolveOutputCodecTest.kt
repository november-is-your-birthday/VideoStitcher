package com.kai.videostitcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动编码选择"按时长"逻辑的纯 JVM 验证（1.9.1 起按素材总时长取多数，
 * 不再按视频个数）。
 */
class ResolveOutputCodecTest {

    private val auto = TranscodeSettings(0, "auto")

    private fun info(mime: String?) = TrackInfo(
        mime, 1920, 1080, 0, 30f, "audio/mp4a-latm", 48000, 2, true
    )

    @Test
    fun 个数多但总时长短的编码不再胜出() {
        // 1 个 20 分钟 H.264 vs 4 个 1 秒 H.265：按个数旧逻辑会选 H.265，按时长应选 H.264
        val infos = listOf(
            info("video/avc"), info("video/hevc"), info("video/hevc"),
            info("video/hevc"), info("video/hevc")
        )
        val durations = listOf(1_200_000L, 1_000L, 1_000L, 1_000L, 1_000L)
        assertEquals("h264", resolveOutputCodec(infos, durations, auto))
    }

    @Test
    fun 总时长打平时取更低世代() {
        // 1 个 20 秒 H.264 vs 2 个 10 秒 H.265：总时长 20s 打平，取更低世代 H.264
        val infos = listOf(info("video/avc"), info("video/hevc"), info("video/hevc"))
        val durations = listOf(20_000L, 10_000L, 10_000L)
        assertEquals("h264", resolveOutputCodec(infos, durations, auto))
    }

    @Test
    fun 单一编码跟随源() {
        val infos = listOf(info("video/hevc"), info("video/hevc"))
        assertEquals("h265", resolveOutputCodec(infos, listOf(5_000L, 7_000L), auto))
    }

    @Test
    fun 未收录编码视作H264参与时长比较() {
        val infos = listOf(info("video/whatever"), info("video/av01"))
        // AV1 总时长更长才输出 AV1，否则落回 H.264
        assertEquals("av1", resolveOutputCodec(infos, listOf(1_000L, 9_000L), auto))
        assertEquals("h264", resolveOutputCodec(infos, listOf(9_000L, 1_000L), auto))
    }

    @Test
    fun 时长全缺失打平时取在场编码的更低世代() {
        val infos = listOf(info("video/hevc"), info("video/av01"))
        // 时长全 0 → 平票；h264 不在组里不能赢平票，取在场更低世代 h265
        assertEquals("h265", resolveOutputCodec(infos, listOf(0L, 0L), auto))
        // 混入 h264 后同为零时长，h264 赢平票
        val withAvc = listOf(info("video/hevc"), info("video/av01"), info("video/avc"))
        assertEquals("h264", resolveOutputCodec(withAvc, listOf(0L, 0L, 0L), auto))
    }

    @Test
    fun 固定编码设置不受时长影响() {
        val infos = listOf(info("video/avc"))
        assertEquals("h265", resolveOutputCodec(infos, listOf(9_000L), TranscodeSettings(0, "h265")))
    }
}
