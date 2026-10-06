package com.kai.videostitcher

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局拼接会话（1.9.2 起）：分组数据与拼接过程不再依附 Activity。
 * 页面销毁/重建（退后台被系统回收页面、划掉任务后重进、昼夜切换）都只是
 * "换个窗口看同一场拼接"，拼接本体在全局协程 + 前台服务（MergeService）里
 * 继续跑，进度通过 states + 监听者列表推给当前活着的页面。
 */
object MergeSession {

    /** 拼接跑在这里而不是 lifecycleScope：Activity 销毁不再连带取消拼接 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 全局分组数据：Activity 重建时绝不重新 Store.load（那会换掉正在拼接的 Group 实例），
     *  只有进程真正重启（会话归零）后的第一次 onCreate 才加载 */
    val groups = mutableListOf<Group>()
    @Volatile var loaded = false

    /** 本轮成品、正在进行的一轮拼接 */
    val outputs = Collections.synchronizedList(mutableListOf<Uri>())
    var job: Job? = null

    @Volatile var merging = false

    /** 每组最新状态：文本 + 进度（null=转圈不定，-1=隐藏，0..100=百分比）。
     *  拼接结束后保留（✓/✗ 胶囊重建页面时还在），下一轮 begin 时清空 */
    val states = ConcurrentHashMap<Group, Pair<String, Int?>>()

    /** 底部状态栏文案：拼接中 = overallText，结束 = finalText */
    @Volatile var overallText: String = "准备就绪"
    @Volatile var finalText: String = ""

    // ---- 监听者（活着的页面 / 前台服务通知）----
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun notifyChanged() { listeners.forEach { runCatching { it() } } }

    /** 开始一轮拼接：清空上一轮的成品与状态 */
    fun begin() {
        merging = true
        outputs.clear()
        states.clear()
        overallText = "拼接中…"
        finalText = ""
        notifyChanged()
    }

    /** 一轮正常跑完（含失败组） */
    fun finish(ok: Int, total: Int) {
        merging = false
        finalText = if (ok == total)
            "全部完成！$ok 个成品已保存到 相册 → Movies/VideoStitcher"
        else
            "完成 $ok/$total，失败的分组下方有提示。"
        notifyChanged()
    }

    /** 用户停止后的收尾：未完成分组标"已停止"，已完成的保留 */
    fun stopped(targets: List<Group>, succeeded: Collection<Group>) {
        merging = false
        val ok = succeeded.size
        finalText = if (ok > 0)
            "已停止拼接：$ok 组已完成（成品保留在相册），未完成的半成品已清理"
        else
            "已停止拼接，未完成的半成品已清理"
        val okSet = succeeded.toSet()
        for (g in targets) {
            if (g in okSet) continue
            val cur = states[g]?.first ?: ""
            if (!cur.startsWith("✓") && !cur.startsWith("✗")) states[g] = "已停止" to -1
        }
        notifyChanged()
    }
}
