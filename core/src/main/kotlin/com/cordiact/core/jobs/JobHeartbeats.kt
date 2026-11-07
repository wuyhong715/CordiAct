package com.cordiact.core.jobs

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a job's liveness lease fresh while a blocking call is in flight.
 *
 * The lease exists so [JobReaper] can tell "this worker is still here" from "this worker is gone".
 * A worker blocked inside a single long call cannot tick the lease itself, so it holds one of these
 * for the duration and closes it when the call returns.
 *
 * What this does NOT prove: that the call is making progress. A call hung forever inside a syscall
 * keeps ticking. The per-tool wall-clock timeout remains the backstop for that case.
 */
object JobHeartbeats {

    fun start(handle: JobHandle, intervalMillis: Long = 3_000L): AutoCloseable {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        val running = AtomicBoolean(true)
        val thread = Thread({
            while (running.get()) {
                try {
                    Thread.sleep(intervalMillis)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!running.get()) return@Thread
                handle.heartbeat()
            }
        }, "job-heartbeat-${handle.id}").apply {
            isDaemon = true
            start()
        }

        return AutoCloseable {
            running.set(false)
            thread.interrupt()
        }
    }
}
