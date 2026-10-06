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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 拼接前台服务（1.9.2 起）：拼接开始时挂一条常驻通知，让系统把本进程当
 * "用户可感知的前台任务"对待——切到其它应用/回桌面/页面被回收时拼接继续，
 * 不会再像普通后台进程那样几秒内被冻结或杀掉。
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
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MergeSession.removeListener(sessionListener)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("正在拼接视频（后台继续）")
            .setContentText(MergeSession.overallText.ifBlank { "拼接进行中，点按查看进度" })
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(MergeSession.overallText.ifBlank { "拼接进行中，点按查看进度" }))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun postNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }
}
