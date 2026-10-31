package com.cordiact.core.sandbox

import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import java.security.MessageDigest
import java.util.UUID

const val SANDBOX_MANAGER_PLUGIN_ID = "core.sandbox-manager"
const val SANDBOX_MANAGER_VERSION = "0.1.0"
fun sandboxManagerPluginSpec() = PluginSpec(SANDBOX_MANAGER_PLUGIN_ID, SANDBOX_MANAGER_VERSION, PluginTarget.ANDROID_NATIVE)

enum class SandboxTarget { LOCAL_CORDIS_JS }
enum class SandboxInstanceState { STARTING, RUNNING, STOPPING, STOPPED, CRASHED, FAILED }
enum class SandboxIsolationKind { ANDROID_ISOLATED_SERVICE, IN_PROCESS, REMOTE }

data class BundledPluginIdentity(val pluginId: String, val version: String, val sha256: String)
data class SandboxLimits(
    val maxWallClockMillis: Long,
    val maxInboundFrameBytes: Int,
    val maxOutboundFrameBytes: Int,
    val maxConcurrentCalls: Int,
)
data class SandboxLimitCeilings(
    val maxWallClockMillis: Long = 60_000,
    val maxInboundFrameBytes: Int = 64 * 1024,
    val maxOutboundFrameBytes: Int = 256 * 1024,
    val maxConcurrentCalls: Int = 1,
    val maxInstancesPerPlugin: Int = 1,
)
data class SandboxLaunchRequest(
    val plugin: BundledPluginIdentity,
    val workspaceId: String,
    val requestedCapabilities: Set<String>,
    val limits: SandboxLimits,
    val target: SandboxTarget = SandboxTarget.LOCAL_CORDIS_JS,
)
data class SandboxProfile(
    val sessionId: String,
    val plugin: BundledPluginIdentity,
    val workspaceId: String,
    val grantedCapabilities: Set<String>,
    val limits: SandboxLimits,
    val target: SandboxTarget,
)
data class SandboxInstance(
    val sessionId: String,
    val pluginId: String,
    val workspaceId: String,
    val state: SandboxInstanceState,
    val processUid: Int? = null,
)
data class SandboxTermination(val processStopped: Boolean)

fun interface BundledPluginVerifier { fun isBundledAndVerified(identity: BundledPluginIdentity): Boolean }
/** Runs outside the isolated process and must not grant unrequested capabilities. */
fun interface SandboxCapabilityPolicy { fun resolve(request: SandboxLaunchRequest): Set<String> }
interface SandboxRuntimeSession {
    val isolationKind: SandboxIsolationKind
    val processUid: Int
    val isAlive: Boolean
    fun terminate(gracePeriodMillis: Long): SandboxTermination
    fun kill()
}
/** Android adapter must bind a non-exported isolated service; an in-process implementation is rejected. */
interface IsolatedSandboxRuntime {
    val isolationKind: SandboxIsolationKind
    /** Must be called off the main thread and return/throw within the host startup deadline. */
    fun start(profile: SandboxProfile): SandboxRuntimeSession
}
fun interface SandboxSessionRevoker { fun revoke(sessionId: String) }
interface SandboxManagerService {
    fun launch(request: SandboxLaunchRequest): SandboxInstance
    fun terminate(sessionId: String, gracePeriodMillis: Long = 1_000): SandboxInstance
    fun instances(): List<SandboxInstance>
}

/**
 * Protected system plugin coordinating local sandbox profiles. This is orchestration, NOT the OS
 * security boundary. Android execution remains deliberately unavailable until the isolated-service
 * proof of concept passes its acceptance gates.
 */
class SandboxManagerPlugin(
    private val appUid: Int,
    private val bundledVerifier: BundledPluginVerifier,
    private val capabilityPolicy: SandboxCapabilityPolicy,
    private val sessionRevoker: SandboxSessionRevoker,
    private val runtime: IsolatedSandboxRuntime = UnavailableIsolatedSandboxRuntime,
    private val ceilings: SandboxLimitCeilings = SandboxLimitCeilings(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : ManagedPlugin, SandboxManagerService {
    override val spec = sandboxManagerPluginSpec()
    private val lock = Any()
    private val instances = linkedMapOf<String, ActiveInstance>()
    @Volatile private var enabled = false
    private data class ActiveInstance(val profile: SandboxProfile, val runtime: SandboxRuntimeSession, var state: SandboxInstanceState)

    @Synchronized
    override fun onEnable() { enabled = true }

    @Synchronized
    override fun onDisable() { enabled = false; terminateAll(1_000) }
    override fun onUninstall() = onDisable()

    @Synchronized
    override fun launch(request: SandboxLaunchRequest): SandboxInstance {
        check(enabled) { "Sandbox Manager is not enabled" }
        // Snapshot caller-owned collections before validation so mutable Set aliases cannot widen
        // the capability request after the authorization decision.
        val safeRequest = request.copy(requestedCapabilities = request.requestedCapabilities.toSet())
        validateRequest(safeRequest)
        check(bundledVerifier.isBundledAndVerified(safeRequest.plugin)) {
            "Local sandbox launch is restricted to verified APK-bundled Cordis plugins in v1"
        }
        check(runtime.isolationKind == SandboxIsolationKind.ANDROID_ISOLATED_SERVICE) {
            "A verified Android isolated-service runtime is unavailable; refusing in-process fallback"
        }
        val granted = capabilityPolicy.resolve(safeRequest).toSet()
        check(granted.all { it in safeRequest.requestedCapabilities }) { "Sandbox policy returned an unrequested capability" }
        synchronized(lock) {
            check(instances.values.count { it.profile.plugin.pluginId == safeRequest.plugin.pluginId } < ceilings.maxInstancesPerPlugin) {
                "Sandbox instance limit reached for '${safeRequest.plugin.pluginId}'"
            }
        }
        val sessionId = idFactory()
        require(sessionId.isNotBlank()) { "Sandbox session id factory returned a blank id" }
        val profile = SandboxProfile(sessionId, safeRequest.plugin, safeRequest.workspaceId, granted, safeRequest.limits, safeRequest.target)
        val session = runtime.start(profile)
        if (session.isolationKind != SandboxIsolationKind.ANDROID_ISOLATED_SERVICE || session.processUid == appUid) {
            runCatching { session.kill() }
            throw IllegalStateException("Sandbox runtime attestation failed; isolated process required")
        }
        synchronized(lock) {
            if (!enabled || instances.containsKey(sessionId)) {
                runCatching { session.kill() }
                throw IllegalStateException(if (!enabled) "Sandbox Manager was disabled while starting" else "Duplicate sandbox session id")
            }
            instances[sessionId] = ActiveInstance(profile, session, SandboxInstanceState.RUNNING)
            return instances.getValue(sessionId).toPublicInstance()
        }
    }

    override fun terminate(sessionId: String, gracePeriodMillis: Long): SandboxInstance {
        require(gracePeriodMillis >= 0) { "Grace period must not be negative" }
        val active = synchronized(lock) {
            check(enabled || instances.isNotEmpty()) { "Sandbox Manager is not enabled" }
            instances[sessionId] ?: throw NoSuchElementException("Sandbox session '$sessionId' not found")
        }
        synchronized(lock) { active.state = SandboxInstanceState.STOPPING }
        var termination: SandboxTermination? = null
        var failure: Exception? = null
        try { termination = active.runtime.terminate(gracePeriodMillis) } catch (caught: Exception) { failure = caught }
        if (termination?.processStopped != true || active.runtime.isAlive) {
            try { active.runtime.kill() } catch (caught: Exception) {
                val previous = failure
                if (previous == null) failure = caught else previous.addSuppressed(caught)
            }
        }
        if (active.runtime.isAlive) {
            synchronized(lock) { active.state = SandboxInstanceState.FAILED }
            throw IllegalStateException("Sandbox process is still alive after termination/kill", failure)
        }
        try { sessionRevoker.revoke(sessionId) } catch (caught: Exception) {
            synchronized(lock) { active.state = SandboxInstanceState.FAILED }
            throw IllegalStateException("Sandbox stopped but capability revocation is incomplete", caught)
        }
        synchronized(lock) { active.state = SandboxInstanceState.STOPPED; instances.remove(sessionId) }
        return active.toPublicInstance()
    }

    override fun instances(): List<SandboxInstance> = synchronized(lock) { instances.values.map { it.toPublicInstance() } }

    private fun terminateAll(gracePeriodMillis: Long) {
        val ids = synchronized(lock) { instances.keys.toList() }
        val failures = mutableListOf<Exception>()
        ids.forEach { id -> try { terminate(id, gracePeriodMillis) } catch (failure: Exception) { failures += failure } }
        if (failures.isNotEmpty()) {
            val error = IllegalStateException("${failures.size} sandbox session(s) could not be fully terminated", failures.first())
            failures.drop(1).forEach(error::addSuppressed)
            throw error
        }
    }

    private fun validateRequest(request: SandboxLaunchRequest) {
        require(request.target == SandboxTarget.LOCAL_CORDIS_JS) { "Unsupported local sandbox target" }
        require(request.workspaceId.isNotBlank()) { "Workspace id must not be blank" }
        require(request.plugin.pluginId.matches(PLUGIN_ID_PATTERN)) { "Invalid plugin id" }
        require(request.plugin.version.isNotBlank()) { "Plugin version is required" }
        require(request.plugin.sha256.matches(SHA256_PATTERN)) { "Plugin SHA-256 must be 64 lowercase hex characters" }
        require(request.requestedCapabilities.size <= MAX_CAPABILITIES) { "Too many requested capabilities" }
        require(request.requestedCapabilities.all { it.matches(CAPABILITY_PATTERN) }) { "Invalid requested capability" }
        val limit = request.limits
        require(limit.maxWallClockMillis in 1..ceilings.maxWallClockMillis) { "Requested wall-clock limit exceeds host ceiling" }
        require(limit.maxInboundFrameBytes in 1..ceilings.maxInboundFrameBytes) { "Requested inbound frame limit exceeds host ceiling" }
        require(limit.maxOutboundFrameBytes in 1..ceilings.maxOutboundFrameBytes) { "Requested outbound frame limit exceeds host ceiling" }
        require(limit.maxConcurrentCalls in 1..ceilings.maxConcurrentCalls) { "Requested concurrency exceeds host ceiling" }
        require(ceilings.maxInstancesPerPlugin >= 1) { "Host sandbox instance ceiling is invalid" }
    }

    private fun ActiveInstance.toPublicInstance() = SandboxInstance(profile.sessionId, profile.plugin.pluginId, profile.workspaceId, state, runtime.processUid)
    private companion object {
        val PLUGIN_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{1,127}")
        val CAPABILITY_PATTERN = Regex("[a-z][a-z0-9._-]{0,127}")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        const val MAX_CAPABILITIES = 32
    }
}

/** Explicit deny until the Android isolated-service implementation is built and verified. */
object UnavailableIsolatedSandboxRuntime : IsolatedSandboxRuntime {
    override val isolationKind = SandboxIsolationKind.IN_PROCESS
    override fun start(profile: SandboxProfile): SandboxRuntimeSession =
        throw UnsupportedOperationException("Android isolated-service sandbox is not implemented")
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
