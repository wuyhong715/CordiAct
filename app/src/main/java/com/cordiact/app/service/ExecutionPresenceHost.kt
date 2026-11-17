package com.cordiact.app.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cordiact.core.jobs.ExecutionPresence
import com.cordiact.core.jobs.ExecutionPresenceRegistry

/** One process-wide arbiter. An ordinary task notification is NOT a keep-alive promise. */
object ExecutionPresenceHost {
    private val registry = ExecutionPresenceRegistry()
    private const val CHANNEL = "task_execution_state"
    private const val ID = 2002
    private const val PREFS = "execution_runtime"
    private const val BLOCKED = "foreground_budget_exhausted"

    @Synchronized fun onForegroundTimeout(context: Context) {
        // Survives process death. Only actual user return clears the stop condition.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(BLOCKED, true).commit()
        render(context)
    }

    @Synchronized fun onUserForeground(context: Context) {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(BLOCKED, false)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(BLOCKED).apply()
        render(context)
    }

    @Synchronized fun replace(context: Context, source: String, entries: List<ExecutionPresence>) {
        registry.replace(source, entries)
        render(context)
    }

    private fun render(context: Context) {
        val snapshot = registry.snapshot()
        val blocked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(BLOCKED, false)
        val foregroundRequested = if (snapshot.needsForeground && !blocked) {
            AgentExecutionService.updateService(context, snapshot.activeCount, snapshot.summary)
        } else {
            AgentExecutionService.stopService(context)
            false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (snapshot.activeCount == 0 || foregroundRequested) { manager.cancel(ID); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "任务状态", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, com.cordiact.app.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("CordiAct 有任务正在处理").build()
        manager.notify(ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("${snapshot.activeCount} 个任务")
            .setContentText(if (blocked) "系统后台执行额度已耗尽 · 请打开应用" else snapshot.summary).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build())
    }
}
