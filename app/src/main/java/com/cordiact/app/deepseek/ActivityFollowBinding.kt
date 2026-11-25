package com.cordiact.app.deepseek

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

/** Attach on the main thread during creation, before the first resume. Never inherits an old Activity's route. */
internal class ActivityFollowBinding private constructor(
    private val activity: Activity,
    val scope: ForegroundFollowScope,
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private var closed = false

    override fun onActivityPostResumed(owner: Activity) { if (owner === activity && !closed) scope.resume() }
    override fun onActivityPrePaused(owner: Activity) { if (owner === activity && !closed) scope.pause() }
    override fun onActivityDestroyed(owner: Activity) { if (owner === activity) close() }
    override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(owner: Activity) = Unit
    override fun onActivityResumed(owner: Activity) = Unit
    override fun onActivityPaused(owner: Activity) = Unit
    override fun onActivityStopped(owner: Activity) = Unit
    override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit

    override fun close() {
        check(Looper.myLooper() === Looper.getMainLooper())
        if (closed) return
        closed = true
        try { scope.close() } finally { activity.unregisterActivityLifecycleCallbacks(this) }
    }

    companion object {
        /** Not called by App startup or any production screen. Executors remain owned by the caller. */
        fun attach(activity: Activity, scheduler: ScheduledExecutorService, reads: Executor): ActivityFollowBinding? {
            check(Looper.myLooper() === Looper.getMainLooper())
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || activity.isFinishing || activity.isDestroyed) return null
            val controller = AndroidFollowRuntime.create(scheduler, reads) ?: return null
            val binding = ActivityFollowBinding(activity, ForegroundFollowScope(controller))
            try {
                activity.registerActivityLifecycleCallbacks(binding)
                return binding
            } catch (failure: RuntimeException) {
                binding.scope.close()
                throw failure
            }
        }
    }
}
