package com.cordiact.core.plugin

import com.cordiact.core.security.AccessEnvelope
import com.cordiact.core.security.EffectiveSecurityProfile
import java.io.InputStream

/** Host-only install UI. Owns confirmation lifetime, not workspace selection or plugin execution. */
class WorkspaceExtensionPackages(
    private val installer: ExtensionPackageInstaller,
    private val profileForLiveWorkspace: (String) -> EffectiveSecurityProfile?,
    private val reservedPackageIds: Set<String> = emptySet(),
    private val runtime: WorkspacePackageRuntime? = null,
) {
    private val lock = Any()
    private val operations = Any()
    private val entered = mutableSetOf<String>()
    private var foreground: Any? = null
    private var foregroundEpoch = 0L
    private val updatingPolicy = mutableSetOf<String>()

    fun resume(owner: Any) = synchronized(lock) {
        if (foreground !== owner) {
            foreground = owner
            foregroundEpoch++
        }
    }

    fun pause(owner: Any) = synchronized(lock) {
        if (foreground === owner) {
            foreground = null
            foregroundEpoch++
        }
    }

    fun <T> whileUpdatingPolicy(workspaceId: String, update: () -> T): T {
        synchronized(lock) {
            check(updatingPolicy.add(workspaceId))
            foregroundEpoch++
        }
        return synchronized(operations) {
            try { update() } finally {
                // A policy change never silently starts an instance: admission is reconsidered on entry or by the user.
                try {
                    if (!mayRun(workspaceId)) runtime?.stopWorkspace(workspaceId)
                } catch (failure: Exception) {
                    runtime?.stopWorkspace(workspaceId)
                    throw failure
                } finally {
                    synchronized(lock) { updatingPolicy.remove(workspaceId) }
                }
            }
        }
    }

    /** Called after selecting the real workspace, before offering its first interactive turn. */
    fun enterWorkspace(workspaceId: String) = synchronized(operations) {
        val runner = runtime ?: return@synchronized
        if (workspaceId in entered || !mayRun(workspaceId)) return@synchronized
        val records = try { installer.list(workspaceId) } catch (_: ExtensionPackageStoreException) { return@synchronized }
        entered.add(workspaceId)
        records.filter { it.enableIntent && it.manifest.id !in reservedPackageIds }.forEach(runner::start)
    }

    fun scopedOwnerState(pluginId: String, workspaceId: String, sessionId: String?): PluginState? =
        PluginState.ENABLED.takeIf { sessionId == null && runtime?.isActive(workspaceId, pluginId) == true }

    private fun mayRun(workspaceId: String): Boolean =
        profileForLiveWorkspace(workspaceId)?.let { it.workspaceId == workspaceId && it.accessEnvelope != AccessEnvelope.READ_ONLY } == true

    fun openPage(workspaceId: String): Page = Page(workspaceId)

    class Preview internal constructor(
        internal val owner: Page,
        internal val artifact: ExtensionPackageArtifact,
        internal val profile: EffectiveSecurityProfile,
        val current: ExtensionPackageRecord?,
    ) {
        val manifest get() = artifact.manifest
        val fromSavedTrial get() = artifact.format == ExtensionPackageArtifact.Format.LEGACY_AUTHORED
        val canInstall get() = profile.accessEnvelope != AccessEnvelope.READ_ONLY
    }

    inner class Page internal constructor(val workspaceId: String) : AutoCloseable {
        private var closed = false

        fun list(): List<ExtensionPackageRecord> {
            synchronized(lock) { profile() }
            return installer.list(workspaceId).also { synchronized(lock) { profile() } }
        }

        /** Read at most 32 KiB plus one byte from the one picker grant. No URI is persisted. */
        fun preview(source: InputStream): Preview {
            val expected = synchronized(lock) { profile() }
            val buffer = ByteArray(ExtensionPackageSource.MAX_SOURCE_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = source.read(buffer, size, buffer.size - size)
                if (count < 0) break
                if (count == 0) {
                    val next = source.read()
                    if (next < 0) break
                    buffer[size++] = next.toByte()
                } else size += count
            }
            return previewArtifact(installer.inspect(buffer.copyOf(size)), expected)
        }

        /** The source service must revalidate the selected saved generation before approval. */
        fun previewSavedTrial(load: () -> ExtensionPackageArtifact): Preview {
            val expected = synchronized(lock) { profile() }
            val artifact = load()
            require(artifact.format == ExtensionPackageArtifact.Format.LEGACY_AUTHORED)
            return previewArtifact(artifact, expected)
        }

        private fun previewArtifact(artifact: ExtensionPackageArtifact, expected: EffectiveSecurityProfile): Preview {
            require(artifact.manifest.id !in reservedPackageIds) { "PACKAGE_ID_RESERVED" }
            val current = installer.list(workspaceId).singleOrNull { it.manifest.id == artifact.manifest.id }
            return synchronized(lock) {
                check(profile() == expected) { "PACKAGE_CONFIRMATION_EXPIRED" }
                Preview(this, artifact, expected, current)
            }
        }

        /** Capture approval synchronously at the user's click, before scheduling any I/O. */
        fun approveInstall(preview: Preview): Change {
            require(!preview.fromSavedTrial)
            return approveArtifact(preview)
        }

        fun approveSavedTrial(preview: Preview, readExactSaved: () -> ExtensionPackageArtifact): Change {
            require(preview.fromSavedTrial)
            return approveArtifact(preview) {
                val current = readExactSaved()
                check(current.format == ExtensionPackageArtifact.Format.LEGACY_AUTHORED && current.manifest == preview.manifest) {
                    "PACKAGE_CONFIRMATION_EXPIRED"
                }
            }
        }

        private fun approveArtifact(preview: Preview, revalidateSource: () -> Unit = {}): Change {
            require(preview.owner === this)
            return approve(preview.profile, requireStopped = preview.current) { publish ->
                revalidateSource()
                installer.install(workspaceId, preview.artifact, preview.current?.installationId, preview.manifest.capabilities, publish)
            }
        }

        fun status(record: ExtensionPackageRecord): WorkspacePackageRuntime.Status {
            require(record.workspaceId == workspaceId)
            return runtime?.status(record) ?: WorkspacePackageRuntime.Status.INACTIVE
        }

        fun approveEnable(record: ExtensionPackageRecord, enabled: Boolean): Change {
            require(record.workspaceId == workspaceId && record.manifest.id !in reservedPackageIds)
            check(runtime != null)
            return approve(expectedProfile = null, allowReadOnly = !enabled, requireStopped = null) { publish ->
                val result = installer.setEnableIntent(workspaceId, record.manifest.id, record.installationId, enabled, publish)
                val target = when (result) {
                    is ExtensionPackageChange.Applied -> result.record
                    is ExtensionPackageChange.Unchanged -> result.record
                    else -> null
                }
                if (target != null) {
                    if (enabled && mayRun(workspaceId)) runtime.start(target) else runtime.stop(target)
                } else if (result is ExtensionPackageChange.Rejected && result.code == ExtensionPackageChange.Code.COMMIT_UNKNOWN) {
                    runtime.stop(record)
                }
                result
            }
        }

        fun approveRetryStart(record: ExtensionPackageRecord): Change {
            require(record.workspaceId == workspaceId && record.manifest.id !in reservedPackageIds)
            check(runtime != null)
            return approve(expectedProfile = null, requireStopped = null) { publish ->
                val current = checkNotNull(installer.list(workspaceId).singleOrNull {
                    it.manifest.id == record.manifest.id && it.installationId == record.installationId
                }) { "PACKAGE_CONFIRMATION_EXPIRED" }
                check(runtime.canRetry(current)) { "PACKAGE_RETRY_UNAVAILABLE" }
                publish { runtime.start(current) }
                ExtensionPackageChange.Unchanged(current)
            }
        }

        fun approveRollback(record: ExtensionPackageRecord): Change {
            require(record.workspaceId == workspaceId)
            return approve(expectedProfile = null, requireStopped = record) { publish -> installer.rollback(workspaceId, record.manifest.id, record.installationId, publish) }
        }

        fun approveRemoval(record: ExtensionPackageRecord): Change {
            require(record.workspaceId == workspaceId)
            return approve(expectedProfile = null, requireStopped = record) { publish -> installer.remove(workspaceId, record.manifest.id, record.installationId, publish) }
        }

        private fun approve(
            expectedProfile: EffectiveSecurityProfile?,
            allowReadOnly: Boolean = false,
            requireStopped: ExtensionPackageRecord?,
            apply: ((() -> Unit) -> Unit) -> ExtensionPackageChange,
        ): Change = synchronized(lock) {
            val expected = profile()
            check(expectedProfile == null || expectedProfile == expected) { "PACKAGE_CONFIRMATION_EXPIRED" }
            check(allowReadOnly || expected.accessEnvelope != AccessEnvelope.READ_ONLY) { "PACKAGE_WORKSPACE_READ_ONLY" }
            check(foreground != null && workspaceId !in updatingPolicy) { "PACKAGE_CONFIRMATION_EXPIRED" }
            val epoch = foregroundEpoch
            Change {
                synchronized(operations) {
                    fun confirmStillValid() {
                        check(epoch == foregroundEpoch && foreground != null && workspaceId !in updatingPolicy && profile() == expected && !Thread.currentThread().isInterrupted) {
                            "PACKAGE_CONFIRMATION_EXPIRED"
                        }
                    }
                    synchronized(lock) { confirmStillValid() }
                    requireStopped?.let { prior ->
                        check(!prior.enableIntent && status(prior) == WorkspacePackageRuntime.Status.INACTIVE) {
                            "PACKAGE_STOP_BEFORE_CHANGE"
                        }
                    }
                    apply { commit -> synchronized(lock) { confirmStillValid(); commit() } }
                }
            }
        }

        private fun profile(): EffectiveSecurityProfile {
            check(!closed) { "PACKAGE_CONFIRMATION_EXPIRED" }
            return profileForLiveWorkspace(workspaceId)?.takeIf { it.workspaceId == workspaceId }
                ?: throw IllegalStateException("PACKAGE_WORKSPACE_UNAVAILABLE")
        }

        override fun close() = synchronized(lock) { closed = true }
    }

    class Change internal constructor(private val apply: () -> ExtensionPackageChange) {
        private val used = java.util.concurrent.atomic.AtomicBoolean()
        fun commit(): ExtensionPackageChange {
            check(used.compareAndSet(false, true)) { "PACKAGE_CONFIRMATION_EXPIRED" }
            return apply()
        }
    }
}
