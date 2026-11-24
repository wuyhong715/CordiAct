package com.cordiact.core.plugin

import java.util.concurrent.ConcurrentHashMap

/** In-process instances of explicitly selected workspace packages. Never restores on construction. */
class WorkspacePackageRuntime(
    // Host-only observer: reproject published data, without mutating the runtime or doing I/O.
    private val admissionChanged: () -> Unit = {},
    private val create: (ExtensionPackageRecord, () -> Boolean) -> RuntimeAwarePlugin,
) {
    private data class Key(val workspace: String, val owner: String)
    private class Admission(val lease: SignedPackageCatalog.RuntimeLease?) {
        @Volatile var open = false
        fun isOpen(): Boolean = open && lease?.isCurrent() != false && open
    }
    private class Instance(val generation: String, val plugin: RuntimeAwarePlugin, val admission: Admission) {
        @Volatile var phase = Status.STARTING
    }

    enum class Status { INACTIVE, STARTING, ACTIVE, FAILED, CLEANUP_PENDING, TRUST_WITHDRAWN }

    private val instances = ConcurrentHashMap<Key, Instance>()
    private val failures = ConcurrentHashMap<Key, String>()
    private val trustWithdrawals = ConcurrentHashMap<Key, String>()

    fun isActive(workspaceId: String, owner: String): Boolean = instances[Key(workspaceId, owner)]?.admission?.isOpen() == true

    internal fun hasSignedInstances(): Boolean = instances.values.any { it.admission.lease != null && it.admission.open }

    /** The host calls this under its operation serializer. Never starts or rebinds an instance. */
    internal fun retireUntrusted() {
        val invalid = instances.entries.filter { (_, value) -> value.admission.open && !value.admission.isOpen() }
        // Withdraw the full batch before any plugin-specific cleanup can block.
        invalid.forEach { (key, value) ->
            trustWithdrawals[key] = value.generation
            withdraw(value)
        }
        if (invalid.isNotEmpty()) notifyAdmissionChanged()
        invalid.forEach { (key, _) -> stop(key) }
    }

    fun status(record: ExtensionPackageRecord): Status = status(record, checkAdmission = true)

    /** Last published process status, not a check of the live admission or signed trust. */
    fun diagnosticStatus(record: ExtensionPackageRecord): Status = status(record, checkAdmission = false)

    private fun status(record: ExtensionPackageRecord, checkAdmission: Boolean): Status {
        val key = key(record)
        val instance = instances[key]
        if (instance != null) {
            val phase = instance.phase
            if (phase == Status.CLEANUP_PENDING) return phase
            if (instance.generation == record.installationId) {
                return if (checkAdmission && phase == Status.ACTIVE && !instance.admission.isOpen()) Status.CLEANUP_PENDING
                else phase
            }
        }
        return when {
            trustWithdrawals[key] == record.installationId -> Status.TRUST_WITHDRAWN
            failures[key] == record.installationId -> Status.FAILED
            else -> Status.INACTIVE
        }
    }

    /** Serialized by the workspace service. Only a matching selected installation is ever started. */
    internal fun start(record: ExtensionPackageRecord, lease: SignedPackageCatalog.RuntimeLease? = null,
        publishAdmission: (() -> Unit) -> Unit = { it() }) {
        check(record.enableIntent)
        val key = key(record)
        if (status(record) == Status.ACTIVE) return
        try {
            publishAdmission { } // Do not start or retry cleanup using an expired explicit action.
        } catch (_: Exception) {
            failures[key] = record.installationId
            return
        }
        stop(key)
        if (instances.containsKey(key)) return
        val admission = Admission(lease)
        val instance = try {
            Instance(record.installationId, create(record, admission::isOpen), admission)
        } catch (_: Exception) {
            failures[key] = record.installationId
            return
        }
        instances[key] = instance
        try {
            instance.plugin.onRegister()
            instance.plugin.onEnable()
            check(instance.plugin.runtimeStatus()?.state == PluginInstanceState.ACTIVE)
            publishAdmission {
                check(lease?.isCurrent() != false) { "CATALOG_UNTRUSTED" }
                instance.admission.open = true
                instance.phase = Status.ACTIVE
            }
            check(instance.admission.isOpen()) { "Host did not open package admission" }
            failures.remove(key)
            trustWithdrawals.remove(key)
            admissionChanged()
        } catch (_: Exception) {
            failures[key] = record.installationId
            stop(key)
        }
    }

    internal fun noteTrustUnavailable(record: ExtensionPackageRecord) {
        if (record.sourceReceipt != null) trustWithdrawals[key(record)] = record.installationId
    }

    /** A failed retry must refer to the same installed generation and a saved enable intent. */
    internal fun canRetry(record: ExtensionPackageRecord): Boolean {
        if (!record.enableIntent) return false
        return when (status(record)) {
            Status.FAILED, Status.TRUST_WITHDRAWN -> true
            Status.INACTIVE -> record.sourceReceipt != null
            else -> false
        }
    }

    internal fun stop(record: ExtensionPackageRecord) {
        val key = key(record)
        stop(key)
        failures.remove(key)
        trustWithdrawals.remove(key)
    }

    internal fun stopWorkspace(workspaceId: String) {
        instances.keys.filter { it.workspace == workspaceId }.forEach(::stop)
        failures.keys.removeIf { it.workspace == workspaceId }
    }

    private fun stop(key: Key) {
        val instance = instances[key] ?: return
        if (withdraw(instance)) notifyAdmissionChanged()
        try { instance.plugin.onUninstall(); instances.remove(key, instance) }
        catch (_: Exception) { /* Keep failed cleanup visible and retriable; never readmit it. */ }
    }

    private fun withdraw(instance: Instance): Boolean {
        val wasAdmitted = instance.admission.open
        instance.admission.open = false
        instance.phase = Status.CLEANUP_PENDING
        return wasAdmitted
    }

    private fun notifyAdmissionChanged() {
        try { admissionChanged() } catch (_: Exception) { /* Withdrawal never depends on reprojection. */ }
    }

    private fun key(record: ExtensionPackageRecord) = Key(record.workspaceId, "pkg.${record.manifest.id}")
}
