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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val receipts = com.cordiact.core.jobs.ForegroundReceiptGate()
    private val mutableConditions = kotlinx.coroutines.flow.MutableStateFlow(com.cordiact.core.jobs.AndroidExecutionConditions())
    val conditions: kotlinx.coroutines.flow.StateFlow<com.cordiact.core.jobs.AndroidExecutionConditions> =
        mutableConditions

    @Synchronized fun accepts(id: String): Boolean = receipts.requestId == id

    @Synchronized fun serviceReceipt(context: Context, id: String, active: Boolean) {
        if (!receipts.acknowledge(id, active)) return
        render(context, requestService = false)
    }

    @Synchronized fun serviceStopped(context: Context, id: String?) {
        if (id == null || receipts.requestId != id) return
        if (receipts.state != com.cordiact.core.jobs.ForegroundExecutionState.REJECTED) receipts.stop()
        render(context, requestService = false)
    }
    private const val CHANNEL = "task_execution_state"
    private const val ID = 2002
    private const val PREFS = "execution_runtime"
    private const val BLOCKED = "foreground_budget_exhausted"

    @Synchronized fun onForegroundTimeout(context: Context) {
        // Survives process death. Only actual user return clears the stop condition.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(BLOCKED, true).commit()
        receipts.stop(timedOut = true)
        render(context, requestService = false)
    }

    @Synchronized fun onUserForeground(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(BLOCKED).apply()
        if (receipts.state in setOf(com.cordiact.core.jobs.ForegroundExecutionState.REJECTED, com.cordiact.core.jobs.ForegroundExecutionState.START_TIMEOUT)) receipts.stop()
        render(context)
    }

    @Synchronized fun replace(context: Context, source: String, entries: List<ExecutionPresence>) {
        registry.replace(source, entries)
        render(context)
    }

    @Synchronized private fun expireRequest(context: Context, id: String) {
        if (!receipts.expire(id)) return
        AgentExecutionService.stopService(context)
        render(context, requestService = false)
    }

    private var monitoredContext: Context? = null
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = refreshConditions()
        override fun onLost(network: android.net.Network) = refreshConditions()
        override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = refreshConditions()
    }
    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshConditions()
    }
    private var networkRegistered = false
    private var receiverRegistered = false
    private fun refreshConditions() {
        handler.post { synchronized(this) { monitoredContext?.let { render(it, requestService = false) } } }
    }
    private fun updateMonitoring(context: Context, active: Boolean) {
        if (active && monitoredContext == null) {
            monitoredContext = context
            networkRegistered = runCatching {
                context.getSystemService(android.net.ConnectivityManager::class.java)
                    .registerDefaultNetworkCallback(networkCallback)
                true
            }.getOrDefault(false)
            receiverRegistered = runCatching {
                val filter = android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                }
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                true
            }.getOrDefault(false)
        } else if (!active && monitoredContext != null) {
            if (networkRegistered) runCatching { context.getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
            networkRegistered = false; receiverRegistered = false; monitoredContext = null
        }
    }

    private fun render(context: Context, requestService: Boolean = true) {
        val snapshot = registry.snapshot()
        updateMonitoring(context.applicationContext, snapshot.activeCount > 0)
        val blocked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(BLOCKED, false)
        if (!snapshot.needsForeground || blocked) {
            if (blocked) receipts.stop(timedOut = true)
            else if (receipts.requestId != null) receipts.stop()
            AgentExecutionService.stopService(context)
        } else if (requestService && receipts.state !in setOf(
                com.cordiact.core.jobs.ForegroundExecutionState.REQUESTED,
                com.cordiact.core.jobs.ForegroundExecutionState.REJECTED,
                com.cordiact.core.jobs.ForegroundExecutionState.START_TIMEOUT)) {
            val id = java.util.UUID.randomUUID().toString()
            receipts.request(id)
            val app = context.applicationContext
            handler.postDelayed({ expireRequest(app, id) }, 10_000L)
            if (!AgentExecutionService.updateService(context, snapshot.activeCount, snapshot.summary, id))
                receipts.acknowledge(id, false)
        }
        val foregroundActive = receipts.state == com.cordiact.core.jobs.ForegroundExecutionState.ACTIVE
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val permission = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled()
        val channelId = if (foregroundActive) AgentExecutionService.CHANNEL_ID else CHANNEL
        val channelVisible = manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
        val network = runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false
        }.getOrNull()
        mutableConditions.value = com.cordiact.core.jobs.AndroidExecutionConditions(
            receipts.state, permission && channelVisible && snapshot.activeCount > 0,
            snapshot.needsForeground, blocked,
            networkValidated = network,
            deviceLocked = runCatching { context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked }.getOrNull(),
            powerSave = runCatching { context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode }.getOrNull(),
            backgroundRestricted = runCatching { context.getSystemService(android.app.ActivityManager::class.java)?.isBackgroundRestricted }.getOrNull(),
            sampledAtMillis = System.currentTimeMillis())
        if (snapshot.activeCount == 0 || foregroundActive) { manager.cancel(ID); return }
        if (!permission || !channelVisible) { manager.cancel(ID); return }
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
