package com.cordiact.app.deepseek

import android.app.Activity
import android.os.Bundle
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Non-exported debug fixture for the instrumentation suite. No credentials, UI, or network setup. */
class FollowLifecycleProbeActivity : Activity() {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    internal var binding: ActivityFollowBinding? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFollowBinding.attach(this, scheduler, Executor { error("Unexpected projection I/O") })
    }

    override fun onDestroy() {
        try { super.onDestroy() } finally {
            // Do not close the binding directly: the test observes Android's destroy callback.
            scheduler.shutdownNow()
        }
    }
}
