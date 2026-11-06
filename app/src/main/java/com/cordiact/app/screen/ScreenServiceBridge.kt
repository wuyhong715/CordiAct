package com.cordiact.app.screen

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide hand-off point between the Android [CordiActAccessibilityService]
 * and the tool layer.
 *
 * The accessibility service is created by the system, not by the DI graph, so it
 * publishes itself here. Tools must check [connected] before touching a node;
 * a stopped service is an expected state, not an exception.
 */
object ScreenServiceBridge {

    private val serviceRef = AtomicReference<CordiActAccessibilityService?>(null)

    @Volatile
    var lastPackageName: String? = null
        private set

    val service: CordiActAccessibilityService?
        get() = serviceRef.get()

    val connected: Boolean
        get() = serviceRef.get() != null

    fun attach(service: CordiActAccessibilityService) {
        serviceRef.set(service)
    }

    fun detach(service: CordiActAccessibilityService) {
        serviceRef.compareAndSet(service, null)
    }

    fun notePackage(packageName: String?) {
        if (!packageName.isNullOrBlank()) lastPackageName = packageName
    }
}
