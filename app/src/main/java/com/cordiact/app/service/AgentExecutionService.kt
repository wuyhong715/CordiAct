package com.cordiact.app.service

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
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Best-effort foreground execution host. Does not guarantee survival or replay work.
 */
class AgentExecutionService : Service() {


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_OR_UPDATE
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 1) ?: 1
        val summary = intent?.getStringExtra(EXTRA_SUMMARY) ?: "正在后台执行任务..."

        if (getSharedPreferences("execution_runtime", MODE_PRIVATE)
                .getBoolean("foreground_budget_exhausted", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification(activeCount, summary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // The FGS alone does not require a CPU wake lock; bounded workers own CPU needs.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 dataSync budget exhausted: do not restart or replay side effects.
        // Stop this service only: FGS timeout is not permission to kill remote jobs.
        android.util.Log.w("AgentExecutionService", "Foreground execution time budget exhausted")
        try {
            ExecutionPresenceHost.onForegroundTimeout(this)
        } catch (error: Exception) {
            Log.e("AgentExecutionService", "Could not publish timeout state", error)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
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
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, com.cordiact.app.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("CordiAct 正在执行任务").build())
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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

        fun updateService(context: Context, activeCount: Int, summary: String): Boolean {
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
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start/update foreground execution service", e)
                return false
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AgentExecutionService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop foreground execution service", e)
            }
        }
    }
}
