package com.cordiact.core.plugin

/** A resource owner, not an authorization boundary. Failed disposals remain retryable. */
class PluginScope : AutoCloseable {
    private val lock = Any()
    private val resources = mutableListOf<AutoCloseable>()
    private var closing = false
    @Volatile private var closed = false

    val isOpen: Boolean get() = !closed

    /** Register immediately after acquisition. A late resource is disposed, never leaked. */
    fun <T : AutoCloseable> own(resource: T): T = synchronized(lock) {
        if (closed) {
            try { resource.close() } catch (failure: Exception) {
                resources.add(resource)
                throw failure
            }
            error("Plugin scope is closed")
        }
        resources.add(resource)
        resource
    }

    fun effect(dispose: () -> Unit): AutoCloseable = own(object : AutoCloseable {
        private var released = false
        @Synchronized override fun close() {
            if (!released) {
                dispose()
                released = true
            }
        }
    })

    fun child(): PluginScope = own(PluginScope())

    fun requireOpen() = check(isOpen) { "Plugin scope is closed" }

    override fun close() = synchronized(lock) {
        check(!closing) { "Reentrant plugin scope disposal" }
        closed = true
        closing = true
        var failure: Exception? = null
        try {
            // Keep failed entries in their original order; retry in reverse acquisition order.
            for (index in resources.indices.reversed()) {
                try {
                    resources[index].close()
                    resources.removeAt(index)
                } catch (caught: Exception) {
                    if (failure == null) failure = caught else failure.addSuppressed(caught)
                }
            }
        } finally {
            closing = false
        }
        failure?.let { throw it }
        Unit
    }
}
