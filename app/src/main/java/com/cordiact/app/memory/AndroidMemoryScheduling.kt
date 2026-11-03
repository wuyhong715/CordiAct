package com.cordiact.app.memory

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cordiact.app.CordiActApplication
import com.cordiact.core.memory.DeviceConditions
import com.cordiact.core.memory.MemorySchedulingPolicy
import com.cordiact.core.memory.SchedulingRetry
import com.cordiact.core.memory.SchedulingDecision
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the device conditions the scheduling policy consults.
 *
 * Deliberately has no charging field: fast charging is a major heat source, so gating
 * background model calls on it would schedule them into the hottest part of the day.
 * Thermal status is the direct measurement of what charging was standing in for.
 */
class AndroidDeviceConditions(
    private val context: Context,
    private val workspaceRoot: File,
) {

    fun read(): DeviceConditions {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) {
            power.currentThermalStatus
        } else {
            0
        }

        val metered = connectivity?.activeNetwork?.let { network ->
            connectivity.getNetworkCapabilities(network)?.let { caps ->
                !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        } ?: false

        return DeviceConditions(
            freeBytes = freeBytes(workspaceRoot),
            meteredNetwork = metered,
            // isDeviceIdleMode is the public equivalent of "doze is active right now".
            dozing = power?.isDeviceIdleMode == true,
            batteryPercent = batteryPercent(power),
            thermalStatus = thermalStatus,
        )
    }

    private fun freeBytes(root: File): Long = try {
        val dir = if (root.exists()) root else root.parentFile ?: root
        StatFs(dir.absolutePath).availableBytes
    } catch (e: Exception) {
        // Unknown free space must not be reported as zero, or nothing would ever run.
        Long.MAX_VALUE
    }

    private fun batteryPercent(power: PowerManager?): Int? {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }
}

/**
 * Builds WorkManager constraints from the scheduling policy.
 *
 * Constraints are deliberately minimal. WorkManager cannot express thermal status, so that
 * check lives in the worker itself; putting a constraint here that the platform cannot honour
 * would give a false sense of safety. Charging is not a constraint for the reason documented
 * on [AndroidDeviceConditions].
 */
object MemoryWorkConstraints {

    fun build(policy: MemorySchedulingPolicy): Constraints = Constraints.Builder()
        .setRequiresStorageNotLow(true)
        .apply {
            if (policy.requireUnmeteredNetwork) {
                setRequiredNetworkType(NetworkType.UNMETERED)
            }
        }
        .build()
}

/** Work name is stable so enqueueing replaces rather than stacks. */
const val MEMORY_EXTRACTION_WORK = "cordiact-memory-extraction"

/**
 * Enqueues memory extraction work.
 *
 * Uses KEEP so a queued run is not duplicated: the extraction is idempotent per session, and
 * stacking requests would only multiply the retries of a failing session.
 */
class MemoryWorkScheduler(private val context: Context) {

    fun enqueue(policy: MemorySchedulingPolicy = MemorySchedulingPolicy()) {
        val request = OneTimeWorkRequestBuilder<MemoryExtractionWorker>()
            .setConstraints(MemoryWorkConstraints.build(policy))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MEMORY_EXTRACTION_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

/**
 * Placeholder worker for the scheduling layer.
 *
 * It reports the scheduling decision it would have made and then returns success, which lets
 * the pipeline be observed before the extraction IO exists. It must not silently succeed on a
 * deferral: returning success while doing nothing is how a stalled pipeline looks healthy.
 */
/**
 * Runs one memory pass.
 *
 * The worker owns no policy: it reads device conditions, asks the pipeline to decide, and maps
 * the outcome onto WorkManager's retry vocabulary. That mapping is the only judgement here, and
 * it matters — the wrong choice either spins on a permanent problem or gives up on a transient
 * one.
 */
class MemoryExtractionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val host = applicationContext as? CordiActApplication
            ?: return Result.failure(workDataOf(KEY_REASON to "host application unavailable"))

        val conditions = host.deviceConditions()
        val selection = ExtractionModelSelectionStore(applicationContext).selectedModel()

        // Checked before the pipeline so a configuration gap does not look like a backlog: the
        // pipeline would also defer, but reporting it here keeps the reason in the work data.
        if (selection == null) {
            return Result.success(workDataOf(KEY_REASON to "no extraction model selected"))
        }

        val workspace = try {
            host.workspaceManager.activeWorkspace()
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_REASON to "no active Workspace"))
        }

        return try {
            // The selection was already resolved above; the callback exists so the pipeline can
            // re-ask per Workspace, and here there is exactly one.
            val report = host.runMemoryPass(workspace.id, conditions) { selection }
            // The mapping lives in core so it is testable without WorkManager; it is also the
            // one judgement that can turn a permanent condition into an infinite retry loop.
            when (SchedulingRetry.decide(report.decision)) {
                SchedulingRetry.Action.DONE ->
                    Result.success(workDataOf(KEY_EXTRACTED to report.extractedCount))
                SchedulingRetry.Action.RETRY,
                SchedulingRetry.Action.RETRY_AFTER_FAILURE,
                -> Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_REASON = "reason"
        const val KEY_EXTRACTED = "extracted"
    }
}
