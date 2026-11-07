package com.cordiact.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service protecting running background jobs and sub-agents from being killed
 * when CordiAct is placed in the background or the screen is turned off.
 */
class AgentExecutionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_UPDATE
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 1) ?: 1
        val summary = intent?.getStringExtra(EXTRA_SUMMARY) ?: "正在后台执行任务..."

        val notification = buildNotification(activeCount, summary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "CordiAct 后台任务执行",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "常驻显示 CordiAct 正在执行的后台命令与子 Agent 状态"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(activeCount: Int, summary: String): Notification {
        val title = if (activeCount > 1) "CordiAct: $activeCount 个任务运行中" else "CordiAct: 任务运行中"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CordiAct:AgentExecutionService")
        }
        wakeLock?.let {
            if (!it.isHeld) {
                // Hold at most 30 minutes as a safety ceiling
                it.acquire(MAX_WAKE_LOCK_DURATION_MS)
                Log.d(TAG, "Partial WakeLock acquired (max 30m ceiling)")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Partial WakeLock released")
            }
        }
    }

    companion object {
        private const val TAG = "AgentExecutionService"
        const val NOTIFICATION_ID = 2026
        const val CHANNEL_ID = "cordiact_agent_execution"

        const val ACTION_START_OR_UPDATE = "com.cordiact.app.service.START_OR_UPDATE"
        const val ACTION_UPDATE = "com.cordiact.app.service.UPDATE"
        const val ACTION_STOP = "com.cordiact.app.service.STOP"

        const val EXTRA_ACTIVE_COUNT = "extra_active_count"
        const val EXTRA_SUMMARY = "extra_summary"

        // 30 minutes safety ceiling
        private const val MAX_WAKE_LOCK_DURATION_MS = 30 * 60 * 1000L

        fun updateService(context: Context, activeCount: Int, summary: String) {
            val intent = Intent(context, AgentExecutionService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_ACTIVE_COUNT, activeCount)
                putExtra(EXTRA_SUMMARY, summary)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start/update foreground execution service", e)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AgentExecutionService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop foreground execution service", e)
            }
        }
    }
}
