package com.cordiact.core.plugin

/** Execution target is metadata for routing; it does not promise cross-target portability. */
enum class PluginTarget { ANDROID_NATIVE, CORDIS_JS, HARNESS_REMOTE }

data class PluginSpec(
    val id: String,
    val version: String,
    val target: PluginTarget,
    val requestedCapabilities: Set<String> = emptySet(),
)

enum class PluginOrigin { BUNDLED_SYSTEM, USER_INSTALLED }
enum class PluginState { REGISTERING, REGISTERED, ENABLING, ENABLED, DISABLING, DISABLED, UNINSTALLING, FAILED }

data class PluginRecord(
    val spec: PluginSpec,
    val origin: PluginOrigin,
    val state: PluginState,
    val lastError: String? = null,
)

/** Hooks must be idempotent: a process can die between a durable state update and a hook. */
interface ManagedPlugin {
    val spec: PluginSpec
    fun onRegister() = Unit
    fun onEnable() = Unit
    fun onDisable() = Unit
    fun onUninstall() = Unit
}

/** Persistence adapter. Production Android implementation must be transactional and durable. */
interface PluginRecordStore {
    fun get(id: String): PluginRecord?
    fun all(): List<PluginRecord>
    fun put(record: PluginRecord)
    fun remove(id: String)
}

/** Stops new work, then drains/cancels plugin-owned jobs and subscriptions. */
interface PluginRuntimeResources {
    fun quiesce(id: String)
    fun resume(id: String)
    fun stopAndDrain(id: String)
    fun detach(id: String)
    fun removeInstalledArtifacts(id: String)
}

/** Every operation must be scoped by plugin id; implementations must not touch other owners' data. */
interface PluginCapabilities {
    /** Re-activate only previously consented capabilities; this must not create new consent. */
    fun activateConsentedAccess(id: String)
    /** Revoke live runtime tokens while retaining user consent for a later re-enable. */
    fun suspendRuntimeAccess(id: String)
    /** Remove consent records and all live tokens permanently. */
    fun revokeAll(id: String)
}

/** Injected only by the trusted app host; verifies bundled plugin provenance/signature. */
fun interface BundledPluginVerifier {
    fun isTrustedBundled(spec: PluginSpec): Boolean
}

interface PluginOwnedData {
    /** Delete data, caches, secrets, and namespaced files owned by this plugin. Must be idempotent. */
    fun eraseAll(id: String)
}

class PluginLifecycleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Serializes lifecycle transitions. A non-removable bundled plugin is protected by host-assigned
 * origin metadata, not by a claim made in the plugin's own manifest.
 */
class PluginLifecycleManager(
    private val records: PluginRecordStore,
    private val runtime: PluginRuntimeResources,
    private val capabilities: PluginCapabilities,
    private val ownedData: PluginOwnedData,
    private val bundledVerifier: BundledPluginVerifier = BundledPluginVerifier { false },
) {
    private val lock = Any()
    private val bindings = mutableMapOf<String, ManagedPlugin>()

    fun register(plugin: ManagedPlugin): PluginRecord = registerInternal(plugin, PluginOrigin.USER_INSTALLED)

    fun registerBundledSystem(plugin: ManagedPlugin): PluginRecord {
        if (!bundledVerifier.isTrustedBundled(plugin.spec)) {
            throw PluginLifecycleException("Bundled plugin '${plugin.spec.id}' failed host provenance verification")
        }
        return registerInternal(plugin, PluginOrigin.BUNDLED_SYSTEM)
    }

    private fun registerInternal(plugin: ManagedPlugin, origin: PluginOrigin): PluginRecord = synchronized(lock) {
        val spec = plugin.spec
        validate(spec)
        val existing = records.get(spec.id)
        if (existing != null) {
            if (existing.spec != spec || existing.origin != origin) {
                throw PluginLifecycleException("Plugin id '${spec.id}' is already registered with different metadata")
            }
            if (existing.state == PluginState.UNINSTALLING || existing.state == PluginState.FAILED) {
                throw PluginLifecycleException("Plugin '${spec.id}' requires recovery or uninstall before registration")
            }
            bindings[spec.id] = plugin
            return@synchronized existing
        }

        val registering = PluginRecord(spec, origin, PluginState.REGISTERING)
        records.put(registering)
        bindings[spec.id] = plugin
        try {
            plugin.onRegister()
            registering.copy(state = PluginState.REGISTERED).also(records::put)
        } catch (failure: Exception) {
            records.put(registering.copy(state = PluginState.FAILED, lastError = failure.message ?: failure::class.simpleName))
            if (origin == PluginOrigin.USER_INSTALLED) {
                // Registration may have partially allocated resources. Roll back through the same
                // idempotent uninstall path; if cleanup fails, its UNINSTALLING tombstone remains.
                try {
                    uninstall(spec.id)
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
            } else {
                // Bundled code remains on disk, but a failed system plugin must not stay live.
                runCleanup("quiesce failed system registration", spec.id) { runtime.quiesce(spec.id) }
                runCleanup("drain failed system registration", spec.id) { runtime.stopAndDrain(spec.id) }
                runCleanup("revoke failed system registration", spec.id) { capabilities.revokeAll(spec.id) }
                runCleanup("detach failed system registration", spec.id) { runtime.detach(spec.id) }
                records.put(registering.copy(state = PluginState.FAILED, lastError = failure.message ?: failure::class.simpleName))
            }
            throw PluginLifecycleException("Registration failed for '${spec.id}'", failure)
        }
    }

    fun enable(id: String): PluginRecord = synchronized(lock) {
        val current = requireRecord(id)
        when (current.state) {
            PluginState.ENABLED -> return@synchronized current
            PluginState.REGISTERED, PluginState.DISABLED -> Unit
            else -> throw PluginLifecycleException("Cannot enable '$id' from ${current.state}")
        }
        val plugin = bindings[id]
            ?: throw PluginLifecycleException("Plugin '$id' must be loaded and registered before enable")
        val enabling = current.copy(state = PluginState.ENABLING, lastError = null)
        records.put(enabling)
        try {
            capabilities.activateConsentedAccess(id)
            plugin.onEnable()
            runtime.resume(id)
            enabling.copy(state = PluginState.ENABLED).also(records::put)
        } catch (failure: Exception) {
            // Fail closed: no action dispatch or capability grant survives a partial enable.
            runCleanup("quiesce after failed enable", id) { runtime.quiesce(id) }
            runCleanup("disable after failed enable", id) { bindings[id]?.onDisable() }
            runCleanup("drain after failed enable", id) { runtime.stopAndDrain(id) }
            runCleanup("detach after failed enable", id) { runtime.detach(id) }
            runCleanup("suspend capabilities after failed enable", id) { capabilities.suspendRuntimeAccess(id) }
            records.put(enabling.copy(state = PluginState.FAILED, lastError = failure.message ?: failure::class.simpleName))
            throw PluginLifecycleException("Enable failed for '$id'", failure)
        }
    }

    fun disable(id: String): PluginRecord = synchronized(lock) {
        val current = requireRecord(id)
        when (current.state) {
            PluginState.DISABLED, PluginState.REGISTERED -> return@synchronized current
            PluginState.ENABLED -> Unit
            else -> throw PluginLifecycleException("Cannot disable '$id' from ${current.state}")
        }
        val disabling = current.copy(state = PluginState.DISABLING, lastError = null)
        records.put(disabling)
        try {
            runtime.quiesce(id)
            bindings[id]?.onDisable()
            runtime.stopAndDrain(id)
            capabilities.suspendRuntimeAccess(id)
            disabling.copy(state = PluginState.DISABLED).also(records::put)
        } catch (failure: Exception) {
            // A failed disable must not leave the plugin accepting calls.
            runCleanup("quiesce after failed disable", id) { runtime.quiesce(id) }
            runCleanup("drain after failed disable", id) { runtime.stopAndDrain(id) }
            runCleanup("suspend after failed disable", id) { capabilities.suspendRuntimeAccess(id) }
            records.put(disabling.copy(state = PluginState.FAILED, lastError = failure.message ?: failure::class.simpleName))
            throw PluginLifecycleException("Disable failed for '$id'", failure)
        }
    }

    /**
     * Uninstall is a resumable tombstoned transaction. All cleanup stages are attempted even if one
     * fails; the registry record is removed only after every stage succeeds. Retrying is safe.
     */
    fun uninstall(id: String) = synchronized(lock) {
        val current = records.get(id) ?: return@synchronized
        if (current.origin == PluginOrigin.BUNDLED_SYSTEM) {
            throw PluginLifecycleException("System plugin '$id' is protected and cannot be uninstalled")
        }
        val plugin = bindings[id]
        records.put(current.copy(state = PluginState.UNINSTALLING, lastError = null))
        val failures = mutableListOf<Exception>()
        fun attempt(stage: String, operation: () -> Unit) {
            try { operation() } catch (failure: Exception) {
                failure.addSuppressedStage(stage)
                failures += failure
            }
        }

        // Block new calls first; then give the plugin an orderly shutdown before revoking access.
        attempt("quiesce") { runtime.quiesce(id) }
        attempt("disable hook") { plugin?.onDisable() }
        attempt("drain runtime") { runtime.stopAndDrain(id) }
        attempt("revoke capabilities") { capabilities.revokeAll(id) }
        attempt("uninstall hook") { plugin?.onUninstall() }
        attempt("detach runtime") { runtime.detach(id) }
        attempt("erase plugin-owned data") { ownedData.eraseAll(id) }
        attempt("remove installed artifacts") { runtime.removeInstalledArtifacts(id) }

        if (failures.isNotEmpty()) {
            val aggregate = PluginLifecycleException(
                "Uninstall of '$id' is incomplete; cleanup was attempted and can be retried",
                failures.first(),
            )
            failures.drop(1).forEach(aggregate::addSuppressed)
            records.put(current.copy(state = PluginState.UNINSTALLING, lastError = aggregate.message))
            throw aggregate
        }
        records.remove(id)
        bindings.remove(id)
    }

    /**
     * Fail-closed startup recovery. Must run before exposing the plugin registry to dispatch.
     * Returns IDs that still require repair; those records remain blocked from action routing.
     */
    fun recoverAfterProcessRestart(): List<String> = synchronized(lock) {
        val needsRepair = mutableListOf<String>()
        records.all().forEach { record ->
            val id = record.spec.id
            try {
                when {
                    record.state == PluginState.UNINSTALLING ||
                        (record.state == PluginState.REGISTERING && record.origin == PluginOrigin.USER_INSTALLED) -> uninstall(id)
                    record.state == PluginState.REGISTERING && record.origin == PluginOrigin.BUNDLED_SYSTEM -> {
                        runtime.quiesce(id)
                        runtime.stopAndDrain(id)
                        capabilities.revokeAll(id)
                        runtime.detach(id)
                        records.put(record.copy(state = PluginState.FAILED, lastError = "Interrupted system-plugin registration"))
                    }
                    record.state in setOf(PluginState.ENABLING, PluginState.ENABLED, PluginState.DISABLING) -> {
                        runtime.quiesce(id)
                        runtime.stopAndDrain(id)
                        capabilities.suspendRuntimeAccess(id)
                        runtime.detach(id)
                        records.put(record.copy(state = PluginState.DISABLED, lastError = "Recovered after process restart; re-enable required"))
                    }
                    record.state == PluginState.FAILED -> {
                        runtime.quiesce(id)
                        runtime.stopAndDrain(id)
                        capabilities.suspendRuntimeAccess(id)
                        runtime.detach(id)
                        needsRepair += id // Keep it blocked until an explicit repair or uninstall.
                    }
                    else -> Unit // REGISTERED and DISABLED are stable and non-routable until enabled.
                }
            } catch (_: Exception) {
                val latest = records.get(id)
                if (latest != null && latest.state != PluginState.UNINSTALLING) {
                    records.put(latest.copy(state = PluginState.FAILED, lastError = "Startup recovery failed; plugin remains blocked"))
                }
                needsRepair += id
            }
        }
        needsRepair
    }

    /** Resume only durable uninstall tombstones; other startup recovery is explicit and separate. */
    fun recoverPendingUninstalls(): List<String> = synchronized(lock) {
        val stillPending = mutableListOf<String>()
        records.all().filter { it.state == PluginState.UNINSTALLING }.forEach { record ->
            try { uninstall(record.spec.id) } catch (_: Exception) { stillPending += record.spec.id }
        }
        stillPending
    }

    fun get(id: String): PluginRecord? = synchronized(lock) { records.get(id) }

    private fun requireRecord(id: String): PluginRecord =
        records.get(id) ?: throw PluginLifecycleException("Plugin '$id' is not registered")

    private fun validate(spec: PluginSpec) {
        if (!spec.id.matches(Regex("[a-z0-9][a-z0-9._-]{1,127}"))) {
            throw PluginLifecycleException("Invalid plugin id '${spec.id}'")
        }
        if (spec.version.isBlank()) throw PluginLifecycleException("Plugin '${spec.id}' has no version")
        if (spec.requestedCapabilities.any { it.isBlank() }) {
            throw PluginLifecycleException("Plugin '${spec.id}' declares an empty capability")
        }
    }

    private fun runCleanup(stage: String, id: String, operation: () -> Unit) {
        try { operation() } catch (failure: Exception) {
            failure.addSuppressedStage(stage)
        }
    }

    private fun Exception.addSuppressedStage(stage: String) {
        addSuppressed(IllegalStateException("Lifecycle cleanup stage failed: $stage"))
    }
}
