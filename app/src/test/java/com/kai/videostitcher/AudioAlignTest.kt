package com.kai.videostitcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.0.5 分段音画对齐的纯函数测试：拼接按各轨自身时长累加时间轴，音轨与视频轨
 *  时长不齐会在段边界累积口型错位，对齐参数决定"截尾还是补静音、何时不动" */
class AudioAlignTest {

    private fun info(v: Long, a: Long) = TrackInfo(
        videoMime = "video/avc", width = 1920, height = 1080, rotation = 0, fps = 30f,
        audioMime = "audio/mp4a-latm", sampleRate = 48000, channels = 2, hasAudio = true,
        videoDurationMs = v, audioDurationMs = a
    )

    @Test
    fun `音轨偏长时只加 -t 截尾`() {
        assertEquals(" -t 10", audioAlignArgs(info(10_000, 10_400)))
    }

    @Test
    fun `音轨偏短时 apad 补静音到视频轨末尾`() {
        assertEquals(" -af apad -t 8", audioAlignArgs(info(8_000, 7_600)))
    }

    @Test
    fun `差异在容差内不动`() {
        assertEquals("", audioAlignArgs(info(10_000, 10_100)))
        assertEquals("", audioAlignArgs(info(10_000, 9_900)))
        assertEquals("", audioAlignArgs(info(10_000, 10_120)))
    }

    @Test
    fun `轨时长未知时不冒险`() {
        assertEquals("", audioAlignArgs(info(0, 10_000)))
        assertEquals("", audioAlignArgs(info(10_000, 0)))
    }

    @Test
    fun `时长带毫秒保留三位小数`() {
        assertEquals(" -t 10.017", audioAlignArgs(info(10_017, 10_500)))
    }

    @Test
    fun `补静音判定只认音轨偏短超容差`() {
        assertTrue(audioNeedsPad(info(10_000, 9_800)))
        assertFalse(audioNeedsPad(info(10_000, 9_950)))   // 容差内，不动
        assertFalse(audioNeedsPad(info(10_000, 10_500)))  // 偏长走截尾，不用 pad
        assertFalse(audioNeedsPad(info(0, 9_000)))        // 时长未知不判
    }

    @Test
    fun `偏差总量只累计两轨时长都明确的条目`() {
        val total = audioDriftTotalMs(
            listOf(info(10_000, 10_300), info(8_000, 7_500), info(0, 9_000))
        )
        assertEquals(300L + 500L, total)
    }
}
