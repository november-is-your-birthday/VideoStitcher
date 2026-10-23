package com.kai.videostitcher

import android.net.Uri
import android.os.Handler
import android.os.Looper
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
 *
 * 2.0 起 states 由 `Pair<String, Int?>` 换成结构化 [GroupProgress]：
 * 引擎只汇报"哪个阶段、第几段、什么编码、百分比、速度"，文案与配色统一在 UI 层生成。
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

    /** 每组最新状态。拼接结束后保留（✓/✗ 胶囊重建页面时还在），下一轮 begin 时清空 */
    val states = ConcurrentHashMap<Group, GroupProgress>()

    /** 整轮进度：总百分比 + "第 2/3 组"这样的上下文，通知与底部状态栏共用 */
    @Volatile var overall: OverallProgress = OverallProgress(null, "准备就绪")

    @Volatile var finalText: String = ""

    /** 当前正在跑的组，供通知拼出"第 2/3 组「旅行」" */
    @Volatile var currentGroup: Group? = null

    /** 兼容旧调用点：底部状态栏与通知都读它 */
    val overallText: String get() = overall.text

    private var targets: List<Group> = emptyList()
    private var weights: List<Double> = emptyList()

    // ---- 监听者（活着的页面 / 前台服务通知）----
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var lastNotifyAt = 0L

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /**
     * 所有监听者统一在主线程被回调：进度回调来自 ffmpeg 统计线程，
     * 旧实现让每个监听者各自 runOnUiThread，一次状态变化会排进多条消息。
     */
    fun notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { runCatching { it() } }
        } else {
            main.post { listeners.forEach { runCatching { it() } } }
        }
    }

    /** 开始一轮拼接：清空上一轮的成品与状态，锁定本轮的组与权重（设置已在调用方快照） */
    fun begin(roundTargets: List<Group>) {
        merging = true
        outputs.clear()
        states.clear()
        targets = roundTargets
        weights = roundTargets.map { g ->
            g.items.sumOf { it.durationMs }.toDouble().coerceAtLeast(1000.0)
        }
        currentGroup = null
        overall = OverallProgress(0, "拼接中…")
        finalText = ""
        lastNotifyAt = 0L
        notifyChanged()
    }

    /** 标记当前正在处理的组（驱动"第 i/N 组"与总进度） */
    fun setCurrent(group: Group) {
        currentGroup = group
        recomputeOverall()
        notifyChanged()
    }

    /**
     * 引擎汇报状态。高频（每段几百毫秒一次）时合并到 200ms 一帧，
     * 阶段切换与收尾态立即刷新——转码期间 UI 刷新抢 CPU 会直接拖慢编码。
     */
    fun publish(group: Group, p: GroupProgress, force: Boolean = false) {
        // 拼接已结束时忽略迟到的进度回调：取消 ffmpeg 后统计回调可能再触发一次，
        // 把"已停止"覆盖回"自动转码中…"。收尾态（✓ / ✗ / 已停止）总是允许写入。
        if (!merging && !p.finished) return
        states[group] = p
        recomputeOverall()
        val now = System.currentTimeMillis()
        if (force || p.finished || now - lastNotifyAt >= 200) {
            lastNotifyAt = now
            notifyChanged()
        }
    }

    private fun recomputeOverall() {
        if (targets.isEmpty()) {
            overall = OverallProgress(null, "拼接中…")
            return
        }
        val pct = ProgressMath.overall(weights, targets.map { states[it]?.percent })
        val idx = targets.indexOf(currentGroup)
        val prefix = if (idx >= 0) {
            val name = currentGroup?.name?.takeIf { it.isNotBlank() }?.let { "「${StatusText.shortName(it, 12)}」" } ?: ""
            "拼接中 · 第 ${idx + 1}/${targets.size} 组$name"
        } else {
            "拼接中…"
        }
        overall = OverallProgress(pct, prefix)
    }

    /** 一轮正常跑完（含失败组） */
    fun finish(ok: Int, total: Int) {
        merging = false
        currentGroup = null
        finalText = if (ok == total)
            "全部完成！$ok 个成品已保存到 相册 → Movies/VideoStitcher"
        else
            "完成 $ok/$total，失败的分组下方有提示。"
        val pct = if (total > 0) ok * 100 / total else null
        overall = OverallProgress(pct, finalText)
        notifyChanged()
    }

    /** 用户停止后的收尾：未完成分组标"已停止"，已完成的保留 */
    fun stopped(roundTargets: List<Group>, succeeded: Collection<Group>) {
        merging = false
        val ok = succeeded.size
        finalText = if (ok > 0)
            "已停止拼接：$ok 组已完成（成品保留在相册），未完成的半成品已清理"
        else
            "已停止拼接，未完成的半成品已清理"
        val okSet = succeeded.toSet()
        for (g in roundTargets) {
            if (g in okSet) continue
            val cur = states[g]
            if (cur != null && cur.finished) continue
            states[g] = GroupProgress(MergeStage.STOPPED, percent = cur?.percent ?: 0)
        }
        overall = OverallProgress(
            ProgressMath.overall(weights, targets.map { states[it]?.percent }),
            finalText
        )
        notifyChanged()
    }
}
