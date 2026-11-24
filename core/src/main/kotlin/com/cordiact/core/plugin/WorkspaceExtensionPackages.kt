package com.cordiact.core.plugin

import com.cordiact.core.security.AccessEnvelope
import com.cordiact.core.security.EffectiveSecurityProfile
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

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
    private val unreconciledIntent = mutableSetOf<Pair<String, String>>()
    private var foreground: Any? = null
    private var foregroundEpoch = 0L
    private val updatingPolicy = mutableSetOf<String>()
    // One lazy, idle-free sweep per app service. Local-only packages never schedule a poll.
    private var retirementTask: java.util.concurrent.ScheduledFuture<*>? = null

    private fun scheduleRetirement() {
        if (retirementTask != null || runtime?.hasSignedInstances() != true) return
        retirementTask = retirementExecutor.scheduleWithFixedDelay({
            try { reconcileSignedRuntime() } catch (_: Exception) { /* The next sweep can retry observation. */ }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    internal fun reconcileSignedRuntime() = synchronized(operations) {
        val runner = runtime ?: return@synchronized
        if (runner.hasSignedInstances()) {
            // A stale/corrupt checkpoint retires the catalog. No download or install mutation here.
            try { installer.pollRuntimeTrust() } catch (_: Exception) { }
            runner.retireUntrusted()
        }
        if (!runner.hasSignedInstances()) {
            retirementTask?.cancel(false)
            retirementTask = null
        }
    }

    private fun startRuntime(runner: WorkspacePackageRuntime, record: ExtensionPackageRecord,
        publish: (() -> Unit) -> Unit = { it() },
    ) {
        val lease = try { installer.lease(record) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runner.noteTrustUnavailable(record)
            return
        }
        runner.start(record, lease) { admit -> publish { installer.withRuntimeTrust(record, admit) } }
        scheduleRetirement()
    }

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
        records.filter {
            // Signed runtime admission is never carried across a process restart.
            it.enableIntent && it.sourceReceipt == null && it.manifest.id !in reservedPackageIds &&
                (workspaceId to it.manifest.id) !in unreconciledIntent && runner.status(it) == WorkspacePackageRuntime.Status.INACTIVE &&
                installer.canStart(it)
        }.forEach { record -> startRuntime(runner, record) }
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
        internal val epoch: Long,
        val current: ExtensionPackageRecord?,
        internal val verified: SignedPackageCatalog.Verified? = null,
    ) {
        val sourceReceipt get() = verified?.sourceReceipt
        val manifest get() = artifact.manifest
        val fromSavedTrial get() = artifact.format == ExtensionPackageArtifact.Format.LEGACY_AUTHORED
        val canInstall get() = profile.accessEnvelope != AccessEnvelope.READ_ONLY
    }

    class SignedRefresh internal constructor(private val operation: SignedCatalogRefresh.Operation, private val run: () -> Unit) {
        fun refresh() = run()
        fun cancel() = operation.cancel()
    }

    class SignedPreviewDownload internal constructor(
        private val download: SignedPackageDownload.Operation,
        private val makePreview: () -> Preview,
    ) {
        fun fetchPreview(): Preview = makePreview()
        fun cancel() = download.cancel()
    }

    /** Exact current declaration shown after the destination picker. Not a write permit. */
    class ExportPreview internal constructor(
        internal val owner: Page,
        internal val record: ExtensionPackageRecord,
        internal val profile: EffectiveSecurityProfile,
        internal val epoch: Long,
    ) {
        val manifest get() = record.manifest
        internal var approved = false // Accessed only under the owning service lock.
    }

    enum class ExportResult { WRITTEN, DESTINATION_UNCONFIRMED }

    class Export internal constructor(private val apply: (() -> OutputStream) -> ExportResult) {
        private val used = java.util.concurrent.atomic.AtomicBoolean()
        fun write(openDestination: () -> OutputStream): ExportResult {
            check(used.compareAndSet(false, true)) { "PACKAGE_CONFIRMATION_EXPIRED" }
            return apply(openDestination)
        }
    }

    inner class Page internal constructor(val workspaceId: String) : AutoCloseable {
        private var closed = false

        fun list(): List<ExtensionPackageRecord> {
            synchronized(lock) { profile() }
            return installer.list(workspaceId).also { synchronized(lock) { profile() } }
        }

        /** Read at most 32 KiB plus one byte from the one picker grant. No URI is persisted. */
        fun preview(source: InputStream): Preview {
            val (expected, epoch) = beginPreview()
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
            return previewArtifact(installer.inspect(buffer.copyOf(size)), expected, epoch)
        }

        /** The source service must revalidate the selected saved generation before approval. */
        fun previewSavedTrial(load: () -> ExtensionPackageArtifact): Preview {
            val (expected, epoch) = beginPreview()
            val artifact = load()
            require(artifact.format == ExtensionPackageArtifact.Format.LEGACY_AUTHORED)
            return previewArtifact(artifact, expected, epoch)
        }

        fun signedPublishers(): List<SignedPackageCatalog.PublisherSummary> {
            val (expected, epoch) = beginPreview()
            return installer.signedPublishers().also { requireSameScope(expected, epoch) }
        }

        fun signedVersions(sourceId: String): List<SignedPackageCatalog.VersionSummary> {
            val (expected, epoch) = beginPreview()
            return installer.signedVersions(sourceId).also { requireSameScope(expected, epoch) }
        }

        fun selectSignedVersion(sourceId: String, packageId: String, version: String): SignedPackageCatalog.Selection {
            val (expected, epoch) = beginPreview()
            return installer.selectSignedVersion(sourceId, packageId, version).also { requireSameScope(expected, epoch) }
        }

        /** The trusted host may open its private checkpoint here; authority is captured before that I/O. */
        fun prepareTrustRefresh(trustSourceId: String, openCatalog: () -> Unit = {}): SignedRefresh =
            prepareSignedRefresh { openCatalog(); installer.signedRefresh(trustSourceId).prepareTrust() }

        fun prepareIndexRefresh(sourceId: String, keyId: String): SignedRefresh =
            prepareSignedRefresh { installer.signedRefresh(sourceId).prepareIndex(sourceId, keyId) }

        internal fun prepareSignedRefresh(prepare: () -> SignedCatalogRefresh.Operation): SignedRefresh {
            val (expected, epoch) = beginPreview()
            val operation = prepare()
            return SignedRefresh(operation) {
                operation.refresh(checkScope = { requireSameScope(expected, epoch) }, scopePublish = { commit ->
                    // Match package admission order: host scope before catalog/checkpoint. Never locked during transport.
                    synchronized(lock) {
                        requireSameScope(expected, epoch)
                        commit()
                    }
                })
            }
        }

        private fun requireSameScope(expected: EffectiveSecurityProfile, epoch: Long) = synchronized(lock) {
            check(!closed && epoch == foregroundEpoch && foreground != null && workspaceId !in updatingPolicy &&
                !Thread.currentThread().isInterrupted && profile() == expected) { "PACKAGE_CONFIRMATION_EXPIRED" }
        }

        /** Capture authority before I/O; a late download cannot open a new preview epoch. */
        fun prepareSignedDownload(selection: SignedPackageCatalog.Selection): SignedPreviewDownload =
            prepareSignedDownload(selection, installer.signedDownload())

        internal fun prepareSignedDownload(selection: SignedPackageCatalog.Selection, downloader: SignedPackageDownload): SignedPreviewDownload {
            require(selection.packageId !in reservedPackageIds) { "PACKAGE_ID_RESERVED" }
            val (expected, epoch) = beginPreview()
            fun sameScope(): Boolean = synchronized(lock) {
                if (closed || epoch != foregroundEpoch || foreground == null || workspaceId in updatingPolicy) false
                else profileForLiveWorkspace(workspaceId) == expected
            }
            val download = downloader.prepare(selection, ::sameScope)
            return SignedPreviewDownload(download) {
                val verified = download.fetch()
                installer.requireCurrent(verified)
                previewArtifact(verified.artifact, expected, epoch, verified)
            }
        }

        private fun beginPreview() = synchronized(lock) {
            check(foreground != null && workspaceId !in updatingPolicy) { "PACKAGE_CONFIRMATION_EXPIRED" }
            profile() to foregroundEpoch
        }

        private fun previewArtifact(
            artifact: ExtensionPackageArtifact, expected: EffectiveSecurityProfile, epoch: Long,
            verified: SignedPackageCatalog.Verified? = null,
        ): Preview {
            val id = artifact.manifest.id
            require(id !in reservedPackageIds) { "PACKAGE_ID_RESERVED" }
            require(verified != null || !installer.requiresSignedSource(id)) { "PACKAGE_SIGNED_SOURCE_REQUIRED" }
            val current = installer.get(workspaceId, id)
            return synchronized(lock) {
                check(epoch == foregroundEpoch && profile() == expected) { "PACKAGE_CONFIRMATION_EXPIRED" }
                Preview(this, artifact, expected, epoch, current, verified)
            }
        }

        /** Capture approval synchronously at the user's click, before scheduling any I/O. */
        fun approveInstall(preview: Preview): Change {
            require(!preview.fromSavedTrial && preview.verified == null)
            return approveArtifact(preview)
        }

        fun approveSignedInstall(preview: Preview): Change {
            require(preview.owner === this)
            val verified = requireNotNull(preview.verified)
            return approve(preview.profile, requireStopped = preview.current, expectedEpoch = preview.epoch) { publish ->
                installer.installVerified(workspaceId, verified, preview.current?.installationId, preview.manifest.capabilities, publish)
            }
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
            return approve(preview.profile, requireStopped = preview.current, expectedEpoch = preview.epoch) { publish ->
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
                val key = workspaceId to record.manifest.id
                if (target != null) {
                    unreconciledIntent.remove(key)
                    if (enabled) startRuntime(runtime, target, publish)
                    else runtime.stop(target)
                } else if (result is ExtensionPackageChange.Rejected && result.code == ExtensionPackageChange.Code.COMMIT_UNKNOWN) {
                    // A read may reveal that the intent committed; it does not authorize an implicit retry.
                    unreconciledIntent.add(key)
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
                publish { installer.withRuntimeTrust(current) {} }
                startRuntime(runtime, current, publish)
                ExtensionPackageChange.Unchanged(current)
            }
        }

        /** Refuse a stale page or a read-only workspace before launching a system save dialog. */
        fun requireExportDestinationSelection(record: ExtensionPackageRecord) = synchronized(lock) {
            require(record.workspaceId == workspaceId)
            val currentProfile = profile()
            check(currentProfile.accessEnvelope != AccessEnvelope.READ_ONLY) { "PACKAGE_EXPORT_READ_ONLY" }
            check(foreground != null && workspaceId !in updatingPolicy) { "PACKAGE_CONFIRMATION_EXPIRED" }
            check(record.manifest.id !in reservedPackageIds) { "PACKAGE_ID_RESERVED" }
            check(record.artifact.format == ExtensionPackageArtifact.Format.DECLARATIVE) { "PACKAGE_EXPORT_FORMAT_UNSUPPORTED" }
            check(record.sourceReceipt == null && !installer.requiresSignedSource(record.manifest.id)) { "PACKAGE_EXPORT_SIGNED_SOURCE" }
        }

        /** Enter only after the user picked a destination and this page resumed. */
        fun previewExport(record: ExtensionPackageRecord): ExportPreview {
            require(record.workspaceId == workspaceId)
            val (expected, epoch) = synchronized(lock) {
                val profile = profile()
                check(profile.accessEnvelope != AccessEnvelope.READ_ONLY) { "PACKAGE_EXPORT_READ_ONLY" }
                check(foreground != null && workspaceId !in updatingPolicy) { "PACKAGE_CONFIRMATION_EXPIRED" }
                profile to foregroundEpoch
            }
            val current = exportRecord(record)
            return synchronized(lock) {
                requireExportConfirmation(expected, epoch)
                ExportPreview(this, current, expected, epoch)
            }
        }

        /** A second, explicit approval is bound to the displayed generation and foreground. */
        fun approveExport(preview: ExportPreview): Export = synchronized(lock) {
            check(preview.owner === this && !preview.approved) { "PACKAGE_CONFIRMATION_EXPIRED" }
            requireExportConfirmation(preview.profile, preview.epoch)
            preview.approved = true
            Export { openDestination ->
                val bytes = synchronized(operations) {
                    synchronized(lock) { requireExportConfirmation(preview.profile, preview.epoch) }
                    val exact = exportRecord(preview.record)
                    // Admission only: do not hold an app lock across an external ContentProvider call.
                    synchronized(lock) { requireExportConfirmation(preview.profile, preview.epoch) }
                    exact.artifact.copySource()
                }
                try {
                    openDestination().use { destination ->
                        // Opening a provider may block. Do not hand it bytes after the page or policy changed.
                        synchronized(operations) {
                            exportRecord(preview.record)
                            synchronized(lock) { requireExportConfirmation(preview.profile, preview.epoch) }
                        }
                        destination.write(bytes)
                    }
                    ExportResult.WRITTEN
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // An external provider may have created, truncated or partially written a file.
                    ExportResult.DESTINATION_UNCONFIRMED
                } finally {
                    bytes.fill(0)
                }
            }
        }

        private fun exportRecord(expected: ExtensionPackageRecord): ExtensionPackageRecord {
            check(expected.manifest.id !in reservedPackageIds) { "PACKAGE_ID_RESERVED" }
            check(expected.artifact.format == ExtensionPackageArtifact.Format.DECLARATIVE) { "PACKAGE_EXPORT_FORMAT_UNSUPPORTED" }
            val current = installer.get(workspaceId, expected.manifest.id)
            check(current != null && current.installationId == expected.installationId &&
                current.manifest == expected.manifest && current.artifact.format == expected.artifact.format &&
                current.sourceReceipt == expected.sourceReceipt) { "PACKAGE_CONFIRMATION_EXPIRED" }
            check(current.sourceReceipt == null && !installer.requiresSignedSource(current.manifest.id)) { "PACKAGE_EXPORT_SIGNED_SOURCE" }
            return current
        }

        private fun requireExportConfirmation(expected: EffectiveSecurityProfile, epoch: Long) {
            check(epoch == foregroundEpoch && foreground != null && workspaceId !in updatingPolicy &&
                profile() == expected && !Thread.currentThread().isInterrupted) { "PACKAGE_CONFIRMATION_EXPIRED" }
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
            expectedEpoch: Long? = null,
            apply: ((() -> Unit) -> Unit) -> ExtensionPackageChange,
        ): Change = synchronized(lock) {
            val expected = profile()
            check(expectedProfile == null || expectedProfile == expected) { "PACKAGE_CONFIRMATION_EXPIRED" }
            check(allowReadOnly || expected.accessEnvelope != AccessEnvelope.READ_ONLY) { "PACKAGE_WORKSPACE_READ_ONLY" }
            check(foreground != null && workspaceId !in updatingPolicy && (expectedEpoch == null || expectedEpoch == foregroundEpoch)) {
                "PACKAGE_CONFIRMATION_EXPIRED"
            }
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

    private companion object {
        val retirementExecutor by lazy {
            java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "cordiact-package-retirement").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
        }
    }

    class Change internal constructor(private val apply: () -> ExtensionPackageChange) {
        private val used = java.util.concurrent.atomic.AtomicBoolean()
        fun commit(): ExtensionPackageChange {
            check(used.compareAndSet(false, true)) { "PACKAGE_CONFIRMATION_EXPIRED" }
            return apply()
        }
    }
}
