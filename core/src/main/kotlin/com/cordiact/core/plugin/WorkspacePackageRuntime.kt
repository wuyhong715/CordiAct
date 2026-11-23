package com.cordiact.core.plugin

import java.util.concurrent.ConcurrentHashMap

/** In-process instances of explicitly selected workspace packages. Never restores on construction. */
class WorkspacePackageRuntime(private val create: (ExtensionPackageRecord, () -> Boolean) -> RuntimeAwarePlugin) {
    private data class Key(val workspace: String, val owner: String)
    private class Admission {
        @Volatile var open = false
    }
    private class Instance(val generation: String, val plugin: RuntimeAwarePlugin, val admission: Admission) {
        @Volatile var phase = Status.STARTING
    }

    enum class Status { INACTIVE, STARTING, ACTIVE, FAILED, CLEANUP_PENDING }

    private val instances = ConcurrentHashMap<Key, Instance>()
    private val failures = ConcurrentHashMap<Key, String>()

    fun isActive(workspaceId: String, owner: String): Boolean = instances[Key(workspaceId, owner)]?.admission?.open == true

    fun status(record: ExtensionPackageRecord): Status {
        val key = key(record)
        val instance = instances[key]
        return when {
            instance?.phase == Status.CLEANUP_PENDING -> Status.CLEANUP_PENDING
            instance != null && instance.generation == record.installationId -> instance.phase
            failures[key] == record.installationId -> Status.FAILED
            else -> Status.INACTIVE
        }
    }

    /** Serialized by the workspace service. Only a matching selected installation is ever started. */
    internal fun start(record: ExtensionPackageRecord) {
        check(record.enableIntent)
        val key = key(record)
        if (status(record) == Status.ACTIVE) return
        stop(key)
        if (instances.containsKey(key)) return
        val admission = Admission()
        val instance = try { Instance(record.installationId, create(record) { admission.open }, admission) }
        catch (_: Exception) { failures[key] = record.installationId; return }
        instances[key] = instance
        try {
            instance.plugin.onRegister()
            instance.plugin.onEnable()
            check(instance.plugin.runtimeStatus()?.state == PluginInstanceState.ACTIVE)
            instance.admission.open = true
            instance.phase = Status.ACTIVE
            failures.remove(key)
        } catch (_: Exception) {
            failures[key] = record.installationId
            stop(key)
        }
    }

    /** A failed retry must refer to the same installed generation and a saved enable intent. */
    internal fun canRetry(record: ExtensionPackageRecord): Boolean = record.enableIntent &&
        status(record) == Status.FAILED

    internal fun stop(record: ExtensionPackageRecord) {
        val key = key(record)
        stop(key)
        failures.remove(key)
    }

    internal fun stopWorkspace(workspaceId: String) {
        instances.keys.filter { it.workspace == workspaceId }.forEach(::stop)
        failures.keys.removeIf { it.workspace == workspaceId }
    }

    private fun stop(key: Key) {
        val instance = instances[key] ?: return
        instance.admission.open = false
        instance.phase = Status.CLEANUP_PENDING
        try { instance.plugin.onUninstall(); instances.remove(key, instance) }
        catch (_: Exception) { /* Keep failed cleanup visible and retriable; never readmit it. */ }
    }

    private fun key(record: ExtensionPackageRecord) = Key(record.workspaceId, "pkg.${record.manifest.id}")
}
