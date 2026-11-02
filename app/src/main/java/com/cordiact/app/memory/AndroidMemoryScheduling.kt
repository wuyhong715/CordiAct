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
import com.cordiact.core.memory.DeviceConditions
import com.cordiact.core.memory.MemorySchedulingPolicy
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
class MemoryExtractionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val projectDir = applicationContext.filesDir
        val conditions = AndroidDeviceConditions(applicationContext, projectDir).read()

        // Model selection is read from the settings store on every run rather than cached, so
        // clearing it in the UI stops extraction at the next cycle instead of at next restart.
        val selected = ExtractionModelSelectionStore(applicationContext).selectedModel()
        val decision = MemorySchedulingPolicy().evaluate(conditions, modelSelected = selected != null)

        return when (decision) {
            is SchedulingDecision.Run -> Result.success()
            is SchedulingDecision.Defer -> when (decision.reason) {
                // A configuration gap does not fix itself: retrying would spin until the backoff
                // cap while nothing can change. Stop and wait for the next explicit enqueue.
                SchedulingDecision.Defer.Reason.NO_MODEL_SELECTED -> Result.success()

                // Transient conditions do clear on their own, so retrying is right.
                SchedulingDecision.Defer.Reason.LOW_STORAGE,
                SchedulingDecision.Defer.Reason.THERMAL_PRESSURE,
                SchedulingDecision.Defer.Reason.METERED,
                SchedulingDecision.Defer.Reason.DOZING,
                -> Result.retry()
            }
        }
    }
}
