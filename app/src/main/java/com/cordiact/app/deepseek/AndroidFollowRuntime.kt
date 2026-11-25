package com.cordiact.app.deepseek

import android.os.Build
import android.os.SystemClock
import com.cordiact.harness.deepseek.FollowRouteController
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

/**
 * Unregistered, opt-in factory for a future trusted Android owner. Does not create a route,
 * open sockets, or read credentials. Caller owns both executors and closes the controller first.
 */
internal object AndroidFollowRuntime {
    fun create(scheduler: ScheduledExecutorService, reads: Executor): FollowRouteController? =
        createForPlatform(Build.VERSION.SDK_INT, SystemClock::elapsedRealtimeNanos, scheduler, reads)

    internal fun createForPlatform(
        sdkInt: Int,
        elapsedNanos: () -> Long,
        scheduler: ScheduledExecutorService,
        reads: Executor,
    ): FollowRouteController? {
        // Public SSLSocket ALPN and TLS 1.3 support start at Android 10. No legacy fallback.
        if (sdkInt < Build.VERSION_CODES.Q) return null
        return FollowRouteController(scheduler, reads, elapsedNanos)
    }
}
