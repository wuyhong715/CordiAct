package com.cordiact.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cordiact.core.jobs.JobReaper
import com.cordiact.core.jobs.JobRegistry
import com.cordiact.core.jobs.JobSnapshot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the connection between the core [JobRegistry] and Android's [AgentExecutionService].
 *
 * Automatically raises the process priority via foreground service when jobs are active,
 * drops it when jobs finish, and periodically sweeps dead heartbeat leases.
 */
class AgentExecutionManager(
    private val context: Context,
    private val jobRegistry: JobRegistry,
    private val sweepIntervalMillis: Long = 5_000L,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val reaper = JobReaper(jobRegistry, maxHeartbeatLagMillis = 15_000L)
    private val running = AtomicBoolean(false)

    private val sweepRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            try {
                val reaped = reaper.sweep()
                if (reaped.isNotEmpty()) {
                    Log.w(TAG, "Reaped ${reaped.size} stalled jobs: ${reaped.map { it.id }}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during periodic job sweep", e)
            } finally {
                if (running.get()) {
                    handler.postDelayed(this, sweepIntervalMillis)
                }
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        jobRegistry.addStartedListener { snapshot ->
            onJobStateChanged(snapshot)
        }

        jobRegistry.addSettledListener { snapshot ->
            onJobStateChanged(snapshot)
        }

        handler.postDelayed(sweepRunnable, sweepIntervalMillis)
        Log.d(TAG, "AgentExecutionManager started")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        handler.removeCallbacks(sweepRunnable)
        AgentExecutionService.stopService(context)
        Log.d(TAG, "AgentExecutionManager stopped")
    }

    private fun onJobStateChanged(snapshot: JobSnapshot) {
        val activeJobs = jobRegistry.listActive()
        if (activeJobs.isEmpty()) {
            AgentExecutionService.stopService(context)
        } else {
            val summary = activeJobs.joinToString("; ") { "[${it.kind.name.lowercase()}] ${it.label}" }
            AgentExecutionService.updateService(context, activeJobs.size, summary)
        }
    }

    companion object {
        private const val TAG = "AgentExecutionManager"
    }
}
