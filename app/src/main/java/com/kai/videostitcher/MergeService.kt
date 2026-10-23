package com.kai.videostitcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 拼接前台服务（1.9.2 起）：拼接开始时挂一条常驻通知，让系统把本进程当
 * "用户可感知的前台任务"对待——切到其它应用/回桌面/页面被回收时拼接继续，
 * 不会再像普通后台进程那样几秒内被冻结或杀掉。
 *
 * 2.0 起通知改为"进度条 + 当前段状态"：标题是"视频 2/4「x.mp4」H.264 → AV1"，
 * 正文是百分比/速度/剩余时间，副标题是整轮总进度。每秒最多刷新一次。
 */
class MergeService : Service() {

    companion object {
        private const val CHANNEL_ID = "merge_progress"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, MergeService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MergeService::class.java))
        }
    }

    private var lastPostAt = 0L
    private var lastKey = ""
    private var wakeLock: PowerManager.WakeLock? = null

    /** 拼接状态一变就刷新通知；会话结束顺带停掉自己（调用方也会 stop，双保险） */
    private val sessionListener: () -> Unit = {
        if (MergeSession.merging) postNotification() else stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "拼接进度", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台拼接视频时的常驻通知" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        MergeSession.addListener(sessionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 12+ 要求 startForegroundService 之后 5 秒内必须 startForeground。
        // 类型用 dataSync：全版本通用；mediaProcessing 虽是 15 起的"正牌"转码类型，
        // 但实测部分 Android 15 系统对其类型校验误报 type none 直接崩，不值得冒险。
        // dataSync 在 14/15 上与 mediaProcessing 同为 6 小时/24 小时时限，不损失任何东西
        android.util.Log.d("VideoStitcher", "拼接前台服务启动（dataSync）")
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        lastPostAt = System.currentTimeMillis()
        lastKey = keyNow()
        acquireWakeLock()
        return START_NOT_STICKY
    }

    /**
     * 拼接期间持 partial wake lock（1.9.2 后台保活的另一半）。
     * 前台服务只保证进程不被回收，**不阻止 CPU 休眠**：退出应用熄屏后 CPU 挂起，
     * ffmpeg 的统计回调与输出写盘一起停摆，转码一走一停，几分钟就会被看门狗
     * 判成"卡死"而中止。这里持锁到会话结束（onDestroy 释放），并给 6 小时兜底
     * 上限（dataSync 前台服务的系统时限，防止异常路径漏释放把电池耗干）。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoStitcher:merge").apply {
                setReferenceCounted(false)
                acquire(6L * 60 * 60 * 1000)
            }
            android.util.Log.d("VideoStitcher", "拼接期间持 partial wake lock")
        }.onFailure {
            android.util.Log.w("VideoStitcher", "wake lock 申请失败（不影响前台服务）", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        MergeSession.removeListener(sessionListener)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun currentProgress(): GroupProgress? =
        MergeSession.currentGroup?.let { MergeSession.states[it] }

    private fun keyNow(): String {
        val p = currentProgress()
        return "${MergeSession.overall.percent}|${p?.stage}|${p?.seg?.percent}|${p?.seg?.index}"
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val p = currentProgress()
        val overall = MergeSession.overall
        val title = p?.let { StatusText.title(it) }
            ?: overall.text.ifBlank { "正在拼接视频（后台继续）" }
        val detail = p?.let { StatusText.detail(it) }.orEmpty()
        val bigText = listOf(overall.text, detail)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { "拼接进行中，点按查看进度" }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail.ifBlank { overall.text })
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Android 12+ 默认把前台服务通知延迟 10 秒显示；拼接往往 10 秒内就
            // 有明确进度，延迟期里用户看不到通知会以为"没启动"，故要求立即显示
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val pct = overall.percent
        if (pct != null) {
            builder.setProgress(100, pct, false).setSubText("整体 $pct%")
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** 通知刷新节流：同一状态 900ms 内不重复发，避免转码时反复走 Binder */
    private fun postNotification(force: Boolean = false) {
        val key = keyNow()
        val now = System.currentTimeMillis()
        if (!force && key == lastKey && now - lastPostAt < 900) return
        lastKey = key
        lastPostAt = now
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }
}
