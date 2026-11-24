package com.cordiact.core.plugin

/** Keys compare by name AND JVM type; a name cannot be rebound to a different type in a scope. */
data class ServiceKey<T : Any>(val name: String, val type: Class<T>) {
    init { require(name.isNotBlank()) }
}

/** Exactly one visibility namespace. No implicit cross-workspace or cross-session fallback. */
data class PluginInstanceId(val pluginId: String, val workspaceId: String? = null, val sessionId: String? = null) {
    init {
        require(pluginId.isNotBlank())
        require(workspaceId == null || workspaceId.isNotBlank())
        require(sessionId == null || (sessionId.isNotBlank() && workspaceId != null))
    }
}

interface ContextPlugin {
    val requiredServices: Set<ServiceKey<*>> get() = emptySet()
    fun start(context: PluginContext)
}

enum class PluginInstanceState { DISABLED, WAITING, STARTING, ACTIVE, FAILED }
data class PluginInstanceStatus(
    val id: PluginInstanceId,
    val state: PluginInstanceState,
    val missingServices: Set<String>,
    val lastError: String?,
)

/** Host-provided capabilities only. This API grants neither storage access nor approval bypass. */
class PluginContext internal constructor(
    val id: PluginInstanceId,
    val scope: PluginScope,
    private val lookup: (ServiceKey<*>) -> Any?,
    private val publish: (ServiceKey<*>, Any) -> Unit,
    private val publicationActive: () -> Boolean,
) {
    /** Lock-free read for registries. A contribution is callable only after its start commits. */
    fun isContributionActive(): Boolean = publicationActive() && scope.isOpen

    fun <T : Any> service(key: ServiceKey<T>): T {
        scope.requireOpen()
        return key.type.cast(checkNotNull(lookup(key)) { "Unavailable service '${key.name}'" })
    }
    fun <T : Any> provide(key: ServiceKey<T>, value: T) {
        scope.requireOpen()
        require(key.type.isInstance(value)) { "Invalid service value for '${key.name}'" }
        publish(key, value)
    }
    fun effect(dispose: () -> Unit): AutoCloseable = scope.effect(dispose)
}

/**
 * Synchronous trusted-code kernel. Host lifecycle operations are serialized. Hooks must not
 * recursively mutate the kernel or wait for another thread to mutate it. Service publication
 * is allowed only during start; consumers must declare every service they use.
 *
 * Does not replace PluginLifecycleManager's durable install/consent records. The host enables
 * an instance only AFTER authorization, and disables it before withdrawing authorization.
 */
class PluginKernel(
    // Optional host projection. It must not acquire the kernel recursively or perform I/O.
    private val onPublicationChanged: () -> Unit = {},
) {
    private class Entry(val id: PluginInstanceId, val plugin: ContextPlugin) {
        val requirements = plugin.requiredServices.toSet()
        var desired = false
        var state = PluginInstanceState.DISABLED
        var scope: PluginScope? = null
        var publication: ContributionPublication? = null
        var error: String? = null
        val dependencies = linkedMapOf<ServiceKey<*>, Binding>()
    }
    private class ContributionPublication {
        @Volatile var active = false
    }
    private class Binding(val owner: Entry, val value: Any)
    private data class Address(val workspace: String?, val session: String?, val name: String)
    private val entries = linkedMapOf<PluginInstanceId, Entry>()
    private val bindings = linkedMapOf<Address, Pair<ServiceKey<*>, Binding>>()
    private val started = mutableListOf<Entry>()
    private var transitioning = false

    @Synchronized fun register(id: PluginInstanceId, plugin: ContextPlugin) {
        check(!transitioning) { "Reentrant kernel mutation" }
        check(id !in entries) { "Instance already registered: $id" }
        entries[id] = Entry(id, plugin)
    }

    @Synchronized fun enable(id: PluginInstanceId) = transition {
        val entry = requireEntry(id)
        if (entry.desired && entry.state == PluginInstanceState.ACTIVE) return@transition
        check(entry.scope == null) { "Instance must be disabled/cleaned before retry: $id" }
        entry.desired = true
        entry.error = null
        entry.state = PluginInstanceState.WAITING
    }

    @Synchronized fun disable(id: PluginInstanceId) = transition {
        requireEntry(id).desired = false
    }

    @Synchronized fun unregister(id: PluginInstanceId) {
        disable(id)
        val entry = requireEntry(id)
        check(entry.scope == null) { "Instance cleanup incomplete: $id" }
        entries.remove(id)
    }

    /** Restart only this instance and its dependency closure, preserving consumer enable intent. */
    @Synchronized fun restart(id: PluginInstanceId) {
        check(!transitioning) { "Reentrant kernel mutation" }
        check(requireEntry(id).desired) { "Cannot restart a disabled instance: $id" }
        disable(id)
        enable(id)
    }

    /** Reverse dependency disposal; failed cleanup remains owned and shutdown may be retried. */
    @Synchronized fun shutdown() = transition {
        entries.values.forEach { it.desired = false }
    }

    @Synchronized fun statuses(): List<PluginInstanceStatus> = entries.keys.map(::status)

    @Synchronized fun status(id: PluginInstanceId): PluginInstanceStatus {
        val entry = requireEntry(id)
        return PluginInstanceStatus(id, entry.state,
            entry.requirements.filter { binding(entry, it) == null }.map { it.name }.toSet(), entry.error)
    }

    private fun requireEntry(id: PluginInstanceId) = checkNotNull(entries[id]) { "Unknown instance: $id" }
    private fun address(entry: Entry, key: ServiceKey<*>) = Address(entry.id.workspaceId, entry.id.sessionId, key.name)
    private fun binding(entry: Entry, key: ServiceKey<*>): Binding? = bindings[address(entry, key)]
        ?.takeIf { it.first == key && it.second.owner.state == PluginInstanceState.ACTIVE }?.second

    private fun transition(change: () -> Unit) {
        check(!transitioning) { "Reentrant kernel mutation" }
        transitioning = true
        try { change(); reconcile() } finally { transitioning = false }
    }

    private fun reconcile() {
        // Compute the full invalidation closure before disposal. Consumers stop before providers.
        val invalid = entries.values.filter { !it.desired }.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            for (entry in started) {
                if (entry !in invalid && entry.dependencies.any { (key, old) ->
                        old.owner in invalid || binding(entry, key) !== old
                    }) { invalid.add(entry); changed = true }
            }
        } while (changed)
        // Stop admitting new calls for the full closure before any disposer can block.
        val withdrew = invalid.any { it.publication?.active == true }
        invalid.forEach { it.publication?.active = false }
        if (withdrew) publicationChanged()
        var cleanupFailure: Exception? = null
        for (entry in started.toList().asReversed()) {
            if (entry in invalid) {
                try { stop(entry) } catch (failure: Exception) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
                }
            }
        }
        // Also retry cleanup of partial starts and previously failed disposal.
        for (entry in entries.values.filter { !it.desired && it !in started }) {
            try { stop(entry) } catch (failure: Exception) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
            }
        }
        cleanupFailure?.let { throw it } // fail closed: no new instance starts after incomplete cleanup
        check(entries.values.none { it.state == PluginInstanceState.FAILED && it.scope != null }) {
            "Plugin cleanup pending; disable the failed instance before starting more plugins"
        }
        do {
            changed = false
            for (entry in entries.values) {
                if (!entry.desired || entry.state != PluginInstanceState.WAITING) continue
                val dependencies = entry.requirements.associateWith { binding(entry, it) }
                if (dependencies.values.any { it == null }) continue
                entry.dependencies.clear()
                dependencies.forEach { (key, value) -> entry.dependencies[key] = checkNotNull(value) }
                start(entry)
                changed = true
            }
        } while (changed)
    }

    private fun start(entry: Entry) {
        val scope = PluginScope()
        entry.scope = scope
        entry.state = PluginInstanceState.STARTING
        val publication = ContributionPublication()
        entry.publication = publication
        val context = PluginContext(entry.id, scope, lookup = { key -> synchronized(this) {
            check(key in entry.requirements) { "Undeclared service dependency '${key.name}'" }
            binding(entry, key)?.value
        } }, publish = { key, value -> synchronized(this) {
            check(entry.state == PluginInstanceState.STARTING) { "Publish services only during start" }
            val address = address(entry, key)
            check(address !in bindings) { "Service '${key.name}' already provided in this scope" }
            val published = Binding(entry, value)
            bindings[address] = key to published
            scope.effect { synchronized(this) {
                if (bindings[address]?.second === published) bindings.remove(address)
            } }
        } }, publicationActive = { publication.active })
        try {
            entry.plugin.start(context)
            scope.requireOpen()
            entry.state = PluginInstanceState.ACTIVE
            entry.error = null
            started.add(entry)
            publication.active = true
            publicationChanged()
        } catch (failure: Exception) {
            entry.state = PluginInstanceState.FAILED
            entry.error = failure.message ?: failure.javaClass.name
            // Withdraw even if an earlier plugin cleanup fails; retain failed effects for retry.
            bindings.entries.removeAll { it.value.second.owner === entry }
            try { scope.close(); entry.scope = null } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            entry.dependencies.clear()
            if (entry.scope != null) throw IllegalStateException("Partial plugin start cleanup failed: ${entry.id}", failure)
        }
    }

    private fun publicationChanged() {
        // Rendering projection cannot change kernel admission or strand a disposer.
        try { onPublicationChanged() } catch (_: Exception) { /* Live admission still governs actions. */ }
    }

    private fun stop(entry: Entry) {
        entry.publication?.active = false
        entry.publication = null
        bindings.entries.removeAll { it.value.second.owner === entry }
        try {
            entry.scope?.close()
            entry.scope = null
            started.remove(entry)
            entry.dependencies.clear()
            entry.state = if (entry.desired) PluginInstanceState.WAITING else PluginInstanceState.DISABLED
            entry.error = null
        } catch (failure: Exception) {
            entry.state = PluginInstanceState.FAILED
            entry.error = failure.message ?: failure.javaClass.name
            throw failure
        }
    }
}
