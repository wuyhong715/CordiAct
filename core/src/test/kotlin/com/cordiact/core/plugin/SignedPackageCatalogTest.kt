package com.cordiact.core.plugin

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SignedPackageCatalogTest {
    private val root = keyPair()
    private val alpha = keyPair()
    private val beta = keyPair()
    private var now = Instant.ofEpochSecond(1_000)
    private val a = CatalogSource("alpha", URI("https://alpha.example/releases/"), "alpha-")
    private val b = CatalogSource("beta", URI("https://beta.example/"), "beta-")
    private val catalog = SignedPackageCatalog(root.public, listOf(a, b)) { now }
    private val alphaBytes = artifact("alpha-note", "1.2.3")
    private val betaBytes = artifact("beta-note", "2.0.0")

    @Test fun `two publishers authorize only their exact versions and derive fixed artifact URLs`() {
        acceptTrust()
        acceptIndex()
        acceptIndex(source = "beta", publisher = "publisher-b", keyId = "beta-key", key = beta,
            rows = release("beta-note", "2.0.0", hash(betaBytes)))
        val selected = catalog.select("alpha", "alpha-note", "1.2.3")
        assertEquals(URI("https://alpha.example/releases/packages/alpha-note/1.2.3.json"), selected.artifactUri)
        val verified = catalog.verify(selected, alphaBytes)
        assertEquals("publisher-a", verified.publisherId)
        assertEquals("alpha", verified.sourceId)
        assertContentEquals(alphaBytes, verified.artifact.copySource())
        assertEquals("beta-note", catalog.verify(catalog.select("beta", "beta-note", "2.0.0"), betaBytes).manifest.id)
        for (version in listOf("latest", "^1.2.3", "01.2.3")) assertFails { catalog.select("alpha", "alpha-note", version) }
        assertFails { catalog.select("alpha", "alpha-note", "1.2.4") }
        assertFails { catalog.select("alpha", "beta-note", "2.0.0") }
    }

    @Test fun `local artifact verification binds reserved namespace to active index digest`() {
        acceptTrust()
        acceptIndex()
        val verified = catalog.verifyLocalArtifact(ExtensionPackageArtifact.decode(alphaBytes))
        assertEquals("alpha-note", verified.artifact.manifest.id)
        assertEquals("alpha-key", verified.sourceReceipt.keyId)
        val changed = artifact("alpha-note", "1.2.3").decodeToString().replace(
            "literal", "changed",
        ).encodeToByteArray()
        assertFails { catalog.verifyLocalArtifact(ExtensionPackageArtifact.decode(changed)) }
        acceptIndex(revision = 2, rows = release("alpha-note", "1.2.4", hash(alphaBytes)))
        assertFails { catalog.verifyLocalArtifact(ExtensionPackageArtifact.decode(alphaBytes)) }
    }

    @Test fun `tampering wrong signing key wrong namespace and unrecognized fields cannot replace a valid index`() {
        acceptTrust()
        acceptIndex()
        val original = catalog.select("alpha", "alpha-note", "1.2.3")
        val valid = index(revision = 2)
        assertFails { catalog.acceptIndex("alpha", "alpha-key", valid + ' '.code.toByte(), sign(alpha, INDEX_DOMAIN, valid)) }
        assertFails { catalog.acceptIndex("alpha", "alpha-key", valid, sign(beta, INDEX_DOMAIN, valid)) }
        assertFails { catalog.acceptIndex("alpha", "beta-key", valid, sign(beta, INDEX_DOMAIN, valid)) }
        assertFails { catalog.acceptIndex("alpha", "alpha-key", valid, sign(alpha, TRUST_DOMAIN, valid)) }
        assertFails { acceptIndex(revision = 2, rows = release("beta-note", "2.0.0", hash(betaBytes))) }
        val duplicate = "${release()},${release()}"
        assertFails { acceptIndex(revision = 2, rows = duplicate) }
        val unknown = valid.decodeToString().replace("\"schema\":1", "\"schema\":1,\"url\":\"https://other.example\"").toByteArray()
        assertFails { catalog.acceptIndex("alpha", "alpha-key", unknown, sign(alpha, INDEX_DOMAIN, unknown)) }
        val signedDuplicateField = valid.decodeToString().replace("\"schema\":1", "\"schema\":1,\"schema\":1").toByteArray()
        assertFails { catalog.acceptIndex("alpha", "alpha-key", signedDuplicateField, sign(alpha, INDEX_DOMAIN, signedDuplicateField)) }
        assertFails { catalog.verify(original, alphaBytes + ' '.code.toByte()) }
        assertEquals("alpha-note", catalog.verify(original, alphaBytes).manifest.id)
    }

    @Test fun `digest alone cannot substitute a package identity or version`() {
        acceptTrust()
        acceptIndex(rows = release("alpha-note", "1.2.4", hash(alphaBytes)))
        assertFails { catalog.verify(catalog.select("alpha", "alpha-note", "1.2.4"), alphaBytes) }
        acceptIndex(revision = 2, rows = release("alpha-different", "1.2.3", hash(alphaBytes)))
        assertFails { catalog.verify(catalog.select("alpha", "alpha-different", "1.2.3"), alphaBytes) }
    }

    @Test fun `replay expiry revocation and stale candidate each fail closed at commit boundary`() {
        acceptTrust()
        acceptIndex(revision = 3, expires = 1_100)
        val selected = catalog.select("alpha", "alpha-note", "1.2.3")
        val verified = catalog.verify(selected, alphaBytes)
        val bytes = alphaBytes.copyOf()
        val copy = catalog.verify(selected, bytes)
        bytes.fill(0)
        assertContentEquals(alphaBytes, copy.artifact.copySource())
        assertEquals("committed", catalog.withCurrent(verified) { "committed" })
        assertFails { acceptIndex(revision = 2) }
        assertFails { acceptIndex(revision = 3, expires = 1_101) }
        now = Instant.ofEpochSecond(1_100)
        assertFails { catalog.withCurrent(verified) { fail("Expired evidence must not commit") } }
        acceptIndex(revision = 4)
        val current = catalog.verify(catalog.select("alpha", "alpha-note", "1.2.3"), alphaBytes)
        acceptTrust(revision = 2, grants = grant("beta", "publisher-b", "beta-key", beta))
        assertFails { acceptTrust() }
        assertFails { acceptIndex(revision = 5, trustRevision = 2) }
        assertFails { catalog.withCurrent(current) { fail("Revoked evidence must not commit") } }
        assertFails { catalog.verify(selected, alphaBytes) }
        acceptIndex(source = "beta", publisher = "publisher-b", keyId = "beta-key", key = beta,
            trustRevision = 2, rows = release("beta-note", "2.0.0", hash(betaBytes)))
        assertEquals("beta", catalog.select("beta", "beta-note", "2.0.0").sourceId)
        now = Instant.ofEpochSecond(2_000)
        assertFails { catalog.select("beta", "beta-note", "2.0.0") }
        now = Instant.ofEpochSecond(1_100)
        assertFails { catalog.select("beta", "beta-note", "2.0.0") } // A backwards wall clock does not revive evidence in this process.
    }

    @Test fun `trust refresh retires old indexes even when the publisher remains trusted`() {
        acceptTrust()
        acceptIndex()
        val old = catalog.select("alpha", "alpha-note", "1.2.3")
        acceptTrust(revision = 2)
        assertFails { acceptIndex(revision = 2) }
        assertFails { catalog.select("alpha", "alpha-note", "1.2.3") }
        acceptIndex(revision = 2, trustRevision = 2)
        assertFails { catalog.verify(old, alphaBytes) }
        assertEquals("1.2.3", catalog.select("alpha", "alpha-note", "1.2.3").version)
    }

    @Test fun `passive diagnostics distinguish absent expired and replaced signed evidence`() {
        val receipt = ExtensionPackageSourceReceipt("alpha", "publisher-a", "alpha-key", 1, 1)
        val record = diagnosticRecord(receipt)

        val initial = catalog.diagnosticObservation()
        assertEquals(SignedCatalogDiagnosticState.NOT_REFRESHED, initial.state)
        assertNull(initial.rootRevision)
        assertEquals(SignedReleaseDiagnostic.ROOT_NOT_REFRESHED, initial.release(record))
        assertNull(initial.release(record.copy(sourceReceipt = null)))

        acceptTrust()
        val rootOnly = catalog.diagnosticObservation()
        assertEquals(SignedCatalogDiagnosticState.OBSERVED, rootOnly.state)
        assertEquals(1L, rootOnly.rootRevision)
        assertEquals(SignedReleaseDiagnostic.INDEX_NOT_REFRESHED, rootOnly.release(record))
        assertEquals(SignedReleaseDiagnostic.PUBLISHER_NOT_GRANTED,
            rootOnly.release(record.copy(sourceReceipt = receipt.copy(sourceId = "beta"))))

        acceptIndex(expires = 1_100)
        val listed = catalog.diagnosticObservation()
        assertEquals(SignedReleaseDiagnostic.RELEASE_MATCHES, listed.release(record))
        assertNull(listed.release(record.copy(sourceReceipt = null)))
        now = Instant.ofEpochSecond(1_100)
        val expiredIndex = catalog.diagnosticObservation()
        assertEquals(SignedCatalogDiagnosticState.OBSERVED, expiredIndex.state)
        assertEquals(SignedReleaseDiagnostic.INDEX_EXPIRED, expiredIndex.release(record))
        assertEquals(SignedReleaseDiagnostic.RELEASE_MATCHES, listed.release(record))

        acceptTrust(revision = 2)
        val replacedRoot = catalog.diagnosticObservation()
        assertEquals(2L, replacedRoot.rootRevision)
        assertEquals(SignedReleaseDiagnostic.INDEX_NOT_REFRESHED, replacedRoot.release(record))
        acceptTrust(revision = 3, grants = "")
        assertEquals(SignedReleaseDiagnostic.PUBLISHER_NOT_GRANTED, catalog.diagnosticObservation().release(record))

        now = Instant.ofEpochSecond(2_000)
        val expiredRoot = catalog.diagnosticObservation()
        assertEquals(SignedCatalogDiagnosticState.EXPIRED, expiredRoot.state)
        assertEquals(3L, expiredRoot.rootRevision)
        assertEquals(SignedReleaseDiagnostic.ROOT_EXPIRED, expiredRoot.release(record))
    }

    @Test fun `passive release match uses historical revision floors and exact artifact across publisher key rotation`() {
        acceptTrust()
        acceptIndex(revision = 3)
        val verified = catalog.verify(catalog.select("alpha", "alpha-note", "1.2.3"), alphaBytes)
        val receipt = verified.sourceReceipt
        val record = diagnosticRecord(receipt)

        val rotated = grant("alpha", "publisher-a", "rotated-key", beta)
        val secondPublisher = grant("alpha", "other-publisher", "alpha-key", alpha)
        acceptTrust(revision = 2, grants = "$rotated,$secondPublisher")
        acceptIndex(revision = 4, trustRevision = 2, keyId = "rotated-key", key = beta)
        assertEquals("alpha-key", receipt.keyId)
        assertEquals("rotated-key", catalog.versions("alpha").single().keyId)
        val observed = catalog.diagnosticObservation()
        assertEquals(SignedReleaseDiagnostic.RELEASE_MATCHES, observed.release(record))
        assertEquals("installed", catalog.withInstalled(record.artifact, receipt) { "installed" })
        assertEquals(SignedReleaseDiagnostic.INDEX_NOT_REFRESHED,
            observed.release(record.copy(sourceReceipt = receipt.copy(publisherId = "other-publisher"))))

        val nonMatches = listOf(
            record.copy(sourceReceipt = receipt.copy(trustRevision = 3)),
            record.copy(sourceReceipt = receipt.copy(indexRevision = 5)),
            diagnosticRecord(receipt, alphaBytes + ' '.code.toByte()),
        )
        nonMatches.forEach { candidate ->
            assertEquals(SignedReleaseDiagnostic.RELEASE_NOT_LISTED, observed.release(candidate))
            assertFailsWith<IllegalStateException> {
                catalog.withInstalled(candidate.artifact, requireNotNull(candidate.sourceReceipt)) { fail("Not installed evidence") }
            }
        }
        val otherIdentity = "${release(id = "alpha-other")},${release(version = "1.2.4")}"
        acceptIndex(revision = 5, trustRevision = 2, keyId = "rotated-key", key = beta, rows = otherIdentity)
        assertEquals(SignedReleaseDiagnostic.RELEASE_NOT_LISTED, catalog.diagnosticObservation().release(record))
        assertEquals(SignedReleaseDiagnostic.RELEASE_MATCHES, observed.release(record))
    }

    @Test fun `diagnostic observation does not change the checkpoint or the live time floor`() = withCheckpoint { path ->
        val opened = open(path)
        acceptTrust(target = opened)
        acceptIndex(expires = 1_100, target = opened)
        val record = diagnosticRecord(ExtensionPackageSourceReceipt("alpha", "publisher-a", "alpha-key", 1, 1))
        val checkpoint = Files.readAllBytes(path)
        val reopened = open(path)
        val noPublishedRoot = reopened.diagnosticObservation()
        assertEquals(SignedCatalogDiagnosticState.NOT_REFRESHED, noPublishedRoot.state)
        assertNull(noPublishedRoot.rootRevision)
        assertEquals(SignedReleaseDiagnostic.ROOT_NOT_REFRESHED, noPublishedRoot.release(record))

        now = Instant.ofEpochSecond(1_100)
        assertEquals(SignedReleaseDiagnostic.INDEX_EXPIRED, opened.diagnosticObservation().release(record))
        assertContentEquals(checkpoint, Files.readAllBytes(path))
        now = Instant.ofEpochSecond(1_000)
        assertEquals(SignedReleaseDiagnostic.RELEASE_MATCHES, opened.diagnosticObservation().release(record))
        assertEquals("alpha-note", opened.versions("alpha").single().packageId)
        assertContentEquals(checkpoint, Files.readAllBytes(path))

        now = Instant.ofEpochSecond(1_100)
        opened.pollRuntimeTrust() // An explicit trust check advances the floor, unlike diagnostics.
        val advanced = Files.readAllBytes(path)
        assertFalse(checkpoint.contentEquals(advanced))
        now = Instant.ofEpochSecond(1_000)
        assertEquals(SignedReleaseDiagnostic.INDEX_EXPIRED, opened.diagnosticObservation().release(record))
        assertContentEquals(advanced, Files.readAllBytes(path))
    }

    @Test fun `diagnostic observation of an opened catalog neither requires nor repairs its checkpoint`() = withCheckpoint { path ->
        val opened = open(path)
        acceptTrust(target = opened)
        acceptIndex(target = opened)
        val record = diagnosticRecord(ExtensionPackageSourceReceipt("alpha", "publisher-a", "alpha-key", 1, 1))
        val lock = path.resolveSibling("${path.fileName}.lock")
        val corrupt = byteArrayOf(0, 1, 2)
        Files.write(path, corrupt)
        Files.delete(lock)

        val observed = opened.diagnosticObservation()
        assertEquals(SignedCatalogDiagnosticState.OBSERVED, observed.state)
        assertEquals(SignedReleaseDiagnostic.RELEASE_MATCHES, observed.release(record))
        assertContentEquals(corrupt, Files.readAllBytes(path))
        assertTrue(Files.notExists(lock))

        assertFailsWith<IllegalStateException> { opened.pollRuntimeTrust() }
        val retired = opened.diagnosticObservation()
        assertEquals(SignedCatalogDiagnosticState.RETIRED, retired.state)
        assertNull(retired.rootRevision)
        assertEquals(SignedReleaseDiagnostic.CATALOG_UNAVAILABLE, retired.release(record))
        assertContentEquals(corrupt, Files.readAllBytes(path))
        assertTrue(Files.notExists(lock))
    }

    @Test fun `explicit download uses only the exact selected URI and consumes the operation once`() {
        acceptTrust()
        acceptIndex()
        val selection = catalog.select("alpha", "alpha-note", "1.2.3")
        val targets = mutableListOf<URI>()
        val download = SignedPackageDownload(catalog, CatalogArtifactTransport { uri, _, beforeSend ->
            beforeSend()
            targets += uri
            alphaBytes
        })
        val operation = download.prepare(selection) { true }
        assertTrue(targets.isEmpty())
        val candidate = operation.fetch()
        assertEquals("1.2.3", candidate.manifest.version)
        assertFails { operation.fetch() }
        assertEquals(listOf(selection.artifactUri), targets)
    }

    @Test fun `cancelled stale in flight and wrong scope downloads never expose candidates`() {
        acceptTrust()
        acceptIndex()
        val selection = catalog.select("alpha", "alpha-note", "1.2.3")
        var scope = Any()
        val captured = scope
        var sends = 0
        val download = SignedPackageDownload(catalog, CatalogArtifactTransport { _, _, beforeSend ->
            beforeSend()
            sends++
            // Simulate departure and reopening the same logical workspace with a new host identity.
            scope = Any()
            alphaBytes
        })
        val cancelled = download.prepare(selection) { true }
        cancelled.cancel()
        assertFailsWith<java.util.concurrent.CancellationException> { cancelled.fetch() }
        assertEquals(0, sends)
        val stale = download.prepare(selection) { scope === captured }
        assertFailsWith<java.util.concurrent.CancellationException> { stale.fetch() }
        assertFails { stale.fetch() }
        assertFails { download.prepare(selection) { scope === captured } }
        assertEquals(1, sends)
    }

    @Test fun `signed directory revocation while downloading rejects bytes and tampering cannot become a candidate`() {
        acceptTrust()
        acceptIndex()
        val selection = catalog.select("alpha", "alpha-note", "1.2.3")
        val corrupted = SignedPackageDownload(catalog, CatalogArtifactTransport { _, _, beforeSend ->
            beforeSend(); alphaBytes + 1.toByte()
        }).prepare(selection) { true }
        assertFails { corrupted.fetch() }
        val retired = SignedPackageDownload(catalog, CatalogArtifactTransport { _, _, beforeSend ->
            beforeSend()
            acceptTrust(revision = 2, grants = "")
            alphaBytes
        }).prepare(selection) { true }
        assertFails { retired.fetch() }
        assertFails { retired.fetch() }
    }

    @Test fun `signed preview installs and upgrades without enabling and rollback preserves the historical receipt`() {
        acceptTrust()
        acceptIndex()
        val store = InMemoryExtensionPackageStore()
        val app = packageService(store)
        val aPage = app.openPage("a")
        val bPage = app.openPage("b")
        val aPreview = aPage.downloadPreview("1.2.3", alphaBytes)
        assertEquals("alpha-key", aPreview.sourceReceipt?.keyId)
        assertTrue(aPage.list().isEmpty())
        assertFails { bPage.approveSignedInstall(aPreview) }
        assertFails { aPage.approveInstall(aPreview) }
        val approved = aPage.approveSignedInstall(aPreview)
        val first = assertIs<ExtensionPackageChange.Applied>(approved.commit()).record
        assertFails { approved.commit() }
        assertEquals(false, first.enableIntent)
        assertNull(first.previous)
        assertTrue(bPage.list().isEmpty())
        assertFails { aPage.requireExportDestinationSelection(first) }
        assertFails { aPage.previewExport(first) }

        val next = artifact("alpha-note", "1.3.0")
        acceptIndex(revision = 2, rows = "${release()},${release("alpha-note", "1.3.0", hash(next))}")
        val updated = assertIs<ExtensionPackageChange.Applied>(aPage.approveSignedInstall(aPage.downloadPreview("1.3.0", next)).commit()).record
        assertEquals(2L, updated.sourceReceipt?.indexRevision)
        assertEquals(first.sourceReceipt, updated.previousSourceReceipt)
        assertEquals(false, updated.enableIntent)
        val rolled = assertIs<ExtensionPackageChange.Applied>(aPage.approveRollback(updated).commit()).record
        assertEquals(first.sourceReceipt, rolled.sourceReceipt)
        assertContentEquals(alphaBytes, rolled.artifact.copySource())
        assertEquals(false, rolled.enableIntent)
        assertNull(rolled.previousSourceReceipt)
    }

    @Test fun `reserved namespaces and source conflicts cannot be bypassed with equal bytes`() {
        acceptTrust()
        acceptIndex()
        val store = InMemoryExtensionPackageStore()
        val signedInstaller = ExtensionPackageInstaller(store, signedCatalog = catalog)
        val localInstaller = ExtensionPackageInstaller(store)
        val artifact = localInstaller.inspect(alphaBytes)
        val local = assertIs<ExtensionPackageChange.Applied>(localInstaller.install("legacy", artifact, null, artifact.manifest.capabilities)).record
        val signed = catalog.verify(catalog.select("alpha", "alpha-note", "1.2.3"), alphaBytes)
        val forged = SignedPackageCatalog.Verified(signed.selection, signed.artifact, signed.sourceReceipt.copy(publisherId = "other"))
        assertEquals(ExtensionPackageChange.Code.TRUST_UNAVAILABLE, assertIs<ExtensionPackageChange.Rejected>(
            signedInstaller.installVerified("forged", forged, null, artifact.manifest.capabilities) { it() }).code)
        assertTrue(store.list("forged").isEmpty())
        assertEquals(ExtensionPackageChange.Code.SOURCE_CONFLICT, assertIs<ExtensionPackageChange.Rejected>(
            signedInstaller.installVerified("legacy", signed, local.installationId, artifact.manifest.capabilities) { it() }).code)
        assertEquals(ExtensionPackageChange.Code.SIGNED_SOURCE_REQUIRED, assertIs<ExtensionPackageChange.Rejected>(
            signedInstaller.install("empty", artifact, null, artifact.manifest.capabilities)).code)
        val offline = ExtensionPackageInstaller(store, reservedSourceId = { it.startsWith("alpha-") })
        assertEquals(ExtensionPackageChange.Code.SIGNED_SOURCE_REQUIRED, assertIs<ExtensionPackageChange.Rejected>(
            offline.install("empty", artifact, null, artifact.manifest.capabilities)).code)
        val installed = assertIs<ExtensionPackageChange.Applied>(signedInstaller.installVerified("signed", signed, null, artifact.manifest.capabilities) { it() }).record
        assertEquals(ExtensionPackageChange.Code.SOURCE_CONFLICT, assertIs<ExtensionPackageChange.Rejected>(
            localInstaller.install("signed", artifact, installed.installationId, artifact.manifest.capabilities)).code)
        assertEquals(ExtensionPackageChange.Code.TRUST_UNAVAILABLE, assertIs<ExtensionPackageChange.Rejected>(
            offline.setEnableIntent("legacy", artifact.manifest.id, local.installationId, true)).code)
        acceptTrust(revision = 2, grants = grant("alpha", "replacement-publisher", "new-key", beta))
        acceptIndex(revision = 2, trustRevision = 2, publisher = "replacement-publisher", keyId = "new-key", key = beta)
        val replacement = catalog.verify(catalog.select("alpha", "alpha-note", "1.2.3"), alphaBytes)
        assertEquals(ExtensionPackageChange.Code.SOURCE_CONFLICT, assertIs<ExtensionPackageChange.Rejected>(
            signedInstaller.installVerified("signed", replacement, installed.installationId, artifact.manifest.capabilities) { it() }).code)
    }

    @Test fun `approval rechecks trust at the same publication point and late downloads do not reenter`() {
        acceptTrust()
        acceptIndex()
        val backing = InMemoryExtensionPackageStore()
        val store = object : ExtensionPackageStore by backing {
            override fun compareAndSet(workspaceId: String, packageId: String, expectedInstallationId: String?,
                replacement: ExtensionPackageRecord?, publish: (() -> Unit) -> Unit): ExtensionPackageStoreWrite {
                acceptTrust(revision = 2, grants = "")
                return backing.compareAndSet(workspaceId, packageId, expectedInstallationId, replacement, publish)
            }
        }
        val service = packageService(store)
        val page = service.openPage("a")
        val selection = catalog.select("alpha", "alpha-note", "1.2.3")
        val late = page.prepareSignedDownload(selection, SignedPackageDownload(catalog, CatalogArtifactTransport { _, _, admit ->
            admit(); page.close(); alphaBytes
        }))
        assertFailsWith<java.util.concurrent.CancellationException> { late.fetchPreview() }
        val newPage = service.openPage("a")
        val approval = newPage.approveSignedInstall(newPage.downloadPreview("1.2.3", alphaBytes))
        assertEquals(ExtensionPackageChange.Code.TRUST_UNAVAILABLE, assertIs<ExtensionPackageChange.Rejected>(approval.commit()).code)
        assertTrue(backing.list("a").isEmpty())
    }

    @Test fun `historical receipt does not authorize rollback or enabling after the exact release disappears`() {
        acceptTrust()
        acceptIndex()
        val store = InMemoryExtensionPackageStore()
        val page = packageService(store).openPage("a")
        val one = assertIs<ExtensionPackageChange.Applied>(page.approveSignedInstall(page.downloadPreview("1.2.3", alphaBytes)).commit()).record
        val next = artifact("alpha-note", "1.3.0")
        acceptIndex(revision = 2, rows = release("alpha-note", "1.3.0", hash(next)))
        val updated = assertIs<ExtensionPackageChange.Applied>(page.approveSignedInstall(page.downloadPreview("1.3.0", next)).commit()).record
        assertEquals(one.sourceReceipt, updated.previousSourceReceipt)
        assertEquals(ExtensionPackageChange.Code.TRUST_UNAVAILABLE, assertIs<ExtensionPackageChange.Rejected>(page.approveRollback(updated).commit()).code)
        acceptTrust(revision = 2, grants = "")
        val installer = ExtensionPackageInstaller(store, signedCatalog = catalog)
        assertEquals(ExtensionPackageChange.Code.TRUST_UNAVAILABLE, assertIs<ExtensionPackageChange.Rejected>(
            installer.setEnableIntent("a", "alpha-note", updated.installationId, true)).code)
        assertEquals(updated.installationId, page.list().single().installationId)
    }

    @Test fun `runtime lease withdraws immediately and only an explicit start can open a new grant`() {
        acceptTrust()
        acceptIndex()
        val store = InMemoryExtensionPackageStore()
        val gates = mutableListOf<() -> Boolean>()
        var cleanup = 0
        val runtime = WorkspacePackageRuntime { record, admitted ->
            gates += admitted
            object : RuntimeAwarePlugin {
                override val spec = extensionPackageSpec(record.manifest)
                override fun runtimeStatus() = PluginInstanceStatus(PluginInstanceId(spec.id, record.workspaceId),
                    PluginInstanceState.ACTIVE, emptySet(), null)
                override fun onUninstall() {
                    assertFalse(admitted())
                    cleanup++
                }
            }
        }
        val service = packageService(store, runtime)
        val page = service.openPage("a")
        val pending = page.downloadPreview("1.2.3", alphaBytes)
        val record = assertIs<ExtensionPackageChange.Applied>(page.approveSignedInstall(pending).commit()).record
        val enabled = assertIs<ExtensionPackageChange.Applied>(page.approveEnable(record, true).commit()).record
        assertTrue(gates.single()())
        acceptIndex() // Idempotent replay does not retire the runtime.
        assertTrue(gates.single()())
        acceptIndex(source = "beta", key = beta, keyId = "beta-key", publisher = "publisher-b",
            rows = release("beta-note", "2.0.0", hash(betaBytes)))
        assertTrue(gates.single()())
        acceptIndex(revision = 2, rows = "")
        assertFalse(gates.single()())
        assertFalse(runtime.isActive("a", "pkg.alpha-note"))
        assertTrue(page.status(enabled) in setOf(WorkspacePackageRuntime.Status.CLEANUP_PENDING, WorkspacePackageRuntime.Status.TRUST_WITHDRAWN))
        acceptIndex(revision = 3)
        assertFalse(gates.single()())
        service.reconcileSignedRuntime()
        assertEquals(1, cleanup)
        assertEquals(WorkspacePackageRuntime.Status.TRUST_WITHDRAWN, page.status(enabled))
        assertEquals(enabled.installationId, page.list().single().installationId)
        service.enterWorkspace("a")
        assertEquals(1, gates.size)
        assertIs<ExtensionPackageChange.Unchanged>(page.approveRetryStart(enabled).commit())
        assertEquals(2, gates.size)
        assertTrue(gates.last()())
        assertFalse(gates.first()())
        page.approveEnable(enabled, false).commit()
        service.reconcileSignedRuntime()
    }

    @Test fun `signed enable intent does not auto restore after process reconstruction`() {
        acceptTrust()
        acceptIndex()
        val store = InMemoryExtensionPackageStore()
        val installer = ExtensionPackageInstaller(store, signedCatalog = catalog)
        val verified = catalog.verify(catalog.select("alpha", "alpha-note", "1.2.3"), alphaBytes)
        val saved = assertIs<ExtensionPackageChange.Applied>(installer.installVerified("a", verified, null, verified.manifest.capabilities) { it() }).record
        val enabled = assertIs<ExtensionPackageChange.Applied>(installer.setEnableIntent("a", saved.manifest.id, saved.installationId, true)).record
        val app = packageService(store, WorkspacePackageRuntime { _, _ -> fail("Implicit start") })
        app.enterWorkspace("a")
        assertEquals(WorkspacePackageRuntime.Status.INACTIVE, app.openPage("a").status(enabled))
    }

    @Test fun `runtime diagnostic status reports published phase without checking or retiring a signed lease`() = withCheckpoint { path ->
        val opened = open(path)
        acceptTrust(target = opened)
        acceptIndex(expires = 1_100, target = opened)
        val record = diagnosticRecord(ExtensionPackageSourceReceipt("alpha", "publisher-a", "alpha-key", 1, 1))
            .copy(enableIntent = true)
        val lease = opened.runtimeLease(record.artifact, requireNotNull(record.sourceReceipt))
        var cleanups = 0
        val runtime = WorkspacePackageRuntime { installed, _ ->
            object : RuntimeAwarePlugin {
                override val spec = extensionPackageSpec(installed.manifest)
                override fun runtimeStatus() = PluginInstanceStatus(PluginInstanceId(spec.id, installed.workspaceId),
                    PluginInstanceState.ACTIVE, emptySet(), null)
                override fun onUninstall() { cleanups++ }
            }
        }
        assertEquals(WorkspacePackageRuntime.Status.INACTIVE, runtime.diagnosticStatus(record))
        runtime.start(record, lease)
        assertEquals(WorkspacePackageRuntime.Status.ACTIVE, runtime.status(record))
        val checkpoint = Files.readAllBytes(path)

        now = Instant.ofEpochSecond(1_100)
        assertEquals(WorkspacePackageRuntime.Status.ACTIVE, runtime.diagnosticStatus(record))
        assertEquals(WorkspacePackageRuntime.Status.INACTIVE,
            runtime.diagnosticStatus(record.copy(installationId = "another-generation")))
        assertEquals(0, cleanups)
        assertContentEquals(checkpoint, Files.readAllBytes(path))
        now = Instant.ofEpochSecond(1_000)
        assertEquals(WorkspacePackageRuntime.Status.ACTIVE, runtime.status(record))

        now = Instant.ofEpochSecond(1_100)
        assertEquals(WorkspacePackageRuntime.Status.CLEANUP_PENDING, runtime.status(record))
        assertEquals(WorkspacePackageRuntime.Status.ACTIVE, runtime.diagnosticStatus(record))
        runtime.retireUntrusted()
        assertEquals(1, cleanups)
        assertEquals(WorkspacePackageRuntime.Status.TRUST_WITHDRAWN, runtime.diagnosticStatus(record))
        assertContentEquals(checkpoint, Files.readAllBytes(path))
    }

    @Test fun `a lease fails closed at expiry or detected durable trust change without wall clock revival`() = withCheckpoint { path ->
        val first = open(path)
        acceptTrust(target = first)
        acceptIndex(expires = 1_100, target = first)
        val verified = first.verify(first.select("alpha", "alpha-note", "1.2.3"), alphaBytes)
        val lease = first.runtimeLease(verified.artifact, verified.sourceReceipt)
        assertTrue(lease.isCurrent())
        now = Instant.ofEpochSecond(1_100)
        assertFalse(lease.isCurrent())
        now = Instant.ofEpochSecond(1_001)
        assertFalse(lease.isCurrent())
        acceptIndex(revision = 2, target = first)
        val live = first.runtimeLease(verified.artifact, verified.sourceReceipt)
        assertTrue(live.isCurrent())
        val other = open(path)
        acceptTrust(revision = 2, grants = "", target = other)
        assertFails { first.pollRuntimeTrust() }
        assertFalse(live.isCurrent())
    }

    private fun packageService(store: ExtensionPackageStore, runtime: WorkspacePackageRuntime? = null) = WorkspaceExtensionPackages(
        ExtensionPackageInstaller(store, signedCatalog = catalog), { com.cordiact.core.security.EffectiveSecurityProfile.fullAccess(it) },
        runtime = runtime,
    ).also { it.resume(Any()) }

    private fun WorkspaceExtensionPackages.Page.downloadPreview(version: String, bytes: ByteArray) = prepareSignedDownload(
        catalog.select("alpha", "alpha-note", version), SignedPackageDownload(catalog, CatalogArtifactTransport { _, _, admit -> admit(); bytes }),
    ).fetchPreview()

    @Test fun `separate root and index refresh use fixed endpoints and never refresh implicitly`() {
        val requests = mutableListOf<URI>()
        val transport = CatalogDocumentTransport { uri, _, admit ->
            admit()
            requests += uri
            if (uri.path.endsWith("trust.cdx")) frame(trustBody(1), root, TRUST_DOMAIN)
            else frame(index(), alpha, INDEX_DOMAIN)
        }
        val refresh = SignedCatalogRefresh(catalog, "alpha", transport)
        assertFails { refresh.prepareIndex("alpha", "alpha-key") }
        val trust = refresh.prepareTrust()
        assertTrue(requests.isEmpty())
        trust.refresh()
        assertFails { trust.refresh() }
        assertEquals(listOf("alpha", "beta"), catalog.publishers().map { it.sourceId })
        assertFails { refresh.prepareIndex("beta", "alpha-key") }
        refresh.prepareIndex("alpha", "alpha-key").refresh()
        assertEquals(listOf("alpha-note"), catalog.versions("alpha").map { it.packageId })
        assertFails { catalog.versions("beta") }
        assertEquals(listOf(URI("https://alpha.example/releases/catalog/trust.cdx"),
            URI("https://alpha.example/releases/catalog/indexes/alpha-key.cdx")), requests)
    }

    @Test fun `a late index cannot rebind to a newer root even with its correct signature`() {
        acceptTrust()
        acceptIndex()
        val next = trustBody(2)
        val returned = index(revision = 9, trustRevision = 2, trustDigest = hash(next))
        val refresh = SignedCatalogRefresh(catalog, "alpha", CatalogDocumentTransport { _, _, admit ->
            admit()
            catalog.acceptTrust(next, sign(root, TRUST_DOMAIN, next))
            frame(returned, alpha, INDEX_DOMAIN)
        })
        assertFails { refresh.prepareIndex("alpha", "alpha-key").refresh() }
        assertFails { catalog.versions("alpha") }
        val wrongRoot = index(revision = 2, trustRevision = 2, trustDigest = hash(trustBody(2, grants = "")))
        assertFails { catalog.acceptIndex("alpha", "alpha-key", wrongRoot, sign(alpha, INDEX_DOMAIN, wrongRoot)) }
        val fresh = index(revision = 2, trustRevision = 2, trustDigest = hash(next))
        SignedCatalogRefresh(catalog, "alpha", CatalogDocumentTransport { _, _, admit -> admit(); frame(fresh, alpha, INDEX_DOMAIN) })
            .prepareIndex("alpha", "alpha-key").refresh()
        assertEquals("1.2.3", catalog.versions("alpha").single().version)
    }

    @Test fun `root revocation persists independently of unavailable indexes and roots advance monotonically`() = withCheckpoint { path ->
        val current = open(path)
        acceptTrust(target = current)
        acceptIndex(target = current)
        val prior = current.verify(current.select("alpha", "alpha-note", "1.2.3"), alphaBytes)
        var response = frame(trustBody(2), root, TRUST_DOMAIN)
        val refresh = SignedCatalogRefresh(current, "alpha", CatalogDocumentTransport { _, _, admit -> admit(); response })
        val intermediate = refresh.prepareTrust()
        val latest = refresh.prepareTrust()
        intermediate.refresh()
        response = frame(trustBody(3, grants = ""), root, TRUST_DOMAIN)
        latest.refresh()
        assertFails { refresh.prepareIndex("alpha", "alpha-key") }
        assertFails { current.withCurrent(prior) { fail("revoked selection must not commit") } }
        val reopened = open(path)
        assertFails { acceptTrust(revision = 2, target = reopened) }
        acceptTrust(revision = 3, grants = "", target = reopened)
        assertTrue(reopened.publishers().isEmpty())
    }

    @Test fun `cancel and page retirement win before refresh publication`() {
        val service = packageService(InMemoryExtensionPackageStore())
        val page = service.openPage("a")
        var sends = 0
        lateinit var operation: WorkspaceExtensionPackages.SignedRefresh
        val cancel = SignedCatalogRefresh(catalog, "alpha", CatalogDocumentTransport { _, _, admit ->
            admit(); sends++; operation.cancel(); frame(trustBody(9), root, TRUST_DOMAIN)
        })
        operation = page.prepareSignedRefresh(cancel::prepareTrust)
        assertFailsWith<java.util.concurrent.CancellationException> { operation.refresh() }
        assertFails { operation.refresh() }
        val retire = SignedCatalogRefresh(catalog, "alpha", CatalogDocumentTransport { _, _, admit ->
            admit(); sends++; page.close(); frame(trustBody(9), root, TRUST_DOMAIN)
        })
        assertFails { page.prepareSignedRefresh(retire::prepareTrust).refresh() }
        val beforeSend = service.openPage("a")
        val pending = beforeSend.prepareSignedRefresh(retire::prepareTrust)
        beforeSend.close()
        assertFails { pending.refresh() }
        assertEquals(2, sends)
        acceptTrust() // A rejected refresh cannot advance revision 9.
        assertEquals(2, catalog.publishers().size)
    }

    @Test fun `invalid or expired envelopes preserve the last accepted revision`() = withCheckpoint { path ->
        val current = open(path)
        acceptTrust(target = current)
        val expired = trustBody(9, expires = 1_001)
        val good = frame(expired, root, TRUST_DOMAIN)
        var response = good + 0.toByte()
        val refresh = SignedCatalogRefresh(current, "alpha", CatalogDocumentTransport { _, _, admit -> admit(); response })
        assertFails { refresh.prepareTrust().refresh() }
        response = good
        now = Instant.ofEpochSecond(1_001)
        assertFails { refresh.prepareTrust().refresh() }
        acceptTrust(revision = 2, target = current)
        assertEquals(2, current.publishers().size)
        val reopen = open(path)
        assertFails { acceptTrust(revision = 1, target = reopen) }
        acceptTrust(revision = 2, target = reopen)
    }

    private fun frame(body: ByteArray, key: KeyPair, domain: String): ByteArray {
        val signature = sign(key, domain, body)
        return java.nio.ByteBuffer.allocate(10 + body.size + signature.size)
            .putInt(0x43445831).putInt(body.size).putShort(signature.size.toShort()).put(body).put(signature).array()
    }

    @Test fun `allowlist refuses invalid paths endpoints and overlapping namespaces`() {
        for (url in listOf("http://alpha.example/", "https://alpha.example/one/../", "https://alpha.example/%2e/", "https://alpha.example/?x=1", "https://secret@alpha.example/")) {
            assertFails { CatalogSource("alpha", URI(url), "alpha-") }
        }
        assertFails { SignedPackageCatalog(root.public, listOf(a, b.copy(packagePrefix = "alpha-extra-"))) { now } }
        assertFails { acceptTrust(grants = grant("alpha", "publisher-a", "root", root)) }
        assertFails { acceptTrust(grants = grant("alpha", "publisher-a", "alpha-key", alpha) + "," + grant("beta", "publisher-b", "beta-key", alpha)) }
    }

    @Test fun `restart needs signed evidence and retains root and independent source floors`() = withCheckpoint { path ->
        val first = open(path)
        acceptTrust(revision = 3, target = first)
        acceptIndex(revision = 5, trustRevision = 3, target = first)
        acceptIndex(revision = 2, trustRevision = 3, source = "beta", publisher = "publisher-b", keyId = "beta-key", key = beta,
            rows = release("beta-note", "2.0.0", hash(betaBytes)), target = first)
        val restarted = open(path, listOf(b, a))
        assertFails { restarted.select("alpha", "alpha-note", "1.2.3") }
        assertFails { acceptTrust(revision = 2, target = restarted) }
        assertFails { acceptTrust(revision = 3, grants = "", target = restarted) }
        acceptTrust(revision = 3, target = restarted)
        assertFails { acceptIndex(revision = 4, trustRevision = 3, target = restarted) }
        assertFails { acceptIndex(revision = 5, trustRevision = 3, expires = 1_900, target = restarted) }
        acceptIndex(revision = 5, trustRevision = 3, target = restarted)
        assertEquals("alpha-note", restarted.select("alpha", "alpha-note", "1.2.3").packageId)
        assertFails { acceptIndex(revision = 1, trustRevision = 3, source = "beta", publisher = "publisher-b", keyId = "beta-key", key = beta,
            rows = release("beta-note", "2.0.0", hash(betaBytes)), target = restarted) }
    }

    @Test fun `observed expiry persists and wall clock rollback cannot revive a signed index after reopen`() = withCheckpoint { path ->
        val first = open(path)
        acceptTrust(target = first)
        acceptIndex(expires = 1_100, target = first)
        val verified = first.verify(first.select("alpha", "alpha-note", "1.2.3"), alphaBytes)
        now = Instant.ofEpochSecond(1_100)
        assertFails { first.withCurrent(verified) { fail("expired candidate") } }
        now = Instant.ofEpochSecond(1_001)
        val restarted = open(path)
        acceptTrust(target = restarted)
        assertFails { acceptIndex(expires = 1_100, target = restarted) }
        acceptIndex(revision = 2, expires = 1_200, target = restarted)
        assertEquals("alpha", restarted.select("alpha", "alpha-note", "1.2.3").sourceId)
    }

    @Test fun `stale catalog instances cannot admit a candidate after another instance revokes its source`() = withCheckpoint { path ->
        val first = open(path)
        acceptTrust(target = first)
        acceptIndex(revision = 4, target = first)
        val candidate = first.verify(first.select("alpha", "alpha-note", "1.2.3"), alphaBytes)
        val other = open(path)
        acceptTrust(revision = 2, grants = "", target = other)
        assertFails { first.withCurrent(candidate) { fail("stale evidence must not commit") } }
        assertFails { acceptTrust(revision = 3, target = first) } // Retired objects cannot recover or retry.
        val newest = open(path)
        acceptTrust(revision = 3, target = newest)
        assertFails { acceptIndex(revision = 3, trustRevision = 3, target = newest) }
        acceptIndex(revision = 5, trustRevision = 3, target = newest)
        assertEquals("alpha", newest.select("alpha", "alpha-note", "1.2.3").sourceId)
    }

    @Test fun `concurrent writers compare one checkpoint and only one valid fork wins`() = withCheckpoint { path ->
        val first = open(path)
        val second = open(path)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val trials = listOf(first to "", second to grant("alpha", "publisher-a", "alpha-key", alpha)).map { (target, grants) ->
                workers.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    runCatching { acceptTrust(revision = 2, grants = grants, target = target) }.isSuccess
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, trials.count { it.get(5, TimeUnit.SECONDS) })
            val reopened = open(path)
            assertFails { acceptTrust(revision = 1, target = reopened) }
        } finally {
            start.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun `missing corrupt and configuration changes never initialize a fresh checkpoint`() = withCheckpoint { path ->
        assertFails { open(path, listOf(a.copy(base = URI("https://other.example/releases/")), b)) }
        assertFails { open(path, listOf(a)) }
        assertFails { SignedPackageCatalog.open(beta.public, listOf(a, b), path) { now } }
        val first = open(path)
        acceptTrust(target = first)
        acceptIndex(target = first)
        val complete = Files.readAllBytes(path)
        Files.write(path, complete.copyOf().apply { this[10] = (this[10].toInt() xor 1).toByte() })
        assertFails { open(path) }
        assertFails { first.select("alpha", "alpha-note", "1.2.3") }
        Files.delete(path)
        assertFails { open(path) }
        assertFalse(Files.exists(path))
        assertFails { SignedPackageCatalog.provision(root.public, listOf(a, b), path) { now } }
        assertFalse(Files.exists(path))
    }

    @Test fun `shipped floor pins the minimum root digest and newer app policy cannot open a weaker checkpoint`() {
        val directory = Files.createTempDirectory("cordiact-catalog-floor-")
        try {
            val path = directory.resolve("catalog.state")
            val digest = hash(trustBody(5))
            SignedPackageCatalog.provisionWithRootFloor(root.public, listOf(a, b), path, 5, digest) { now }
            val boot = SignedPackageCatalog.openWithRootFloor(root.public, listOf(a, b), path, 5, digest) { now }
            assertFails { boot.publishers() }
            assertFails { acceptTrust(revision = 4, target = boot) }
            assertFails { acceptTrust(revision = 5, grants = "", target = boot) }
            assertFails { SignedPackageCatalog.openWithRootFloor(root.public, listOf(a, b), path, 6, hash(trustBody(6))) { now } }
            acceptTrust(revision = 5, target = boot)
            assertEquals(2, boot.publishers().size)
            acceptTrust(revision = 6, target = boot)
            assertFails { SignedPackageCatalog.openWithRootFloor(root.public, listOf(a, b), path, 6, "0".repeat(64)) { now } }
            assertIs<SignedPackageCatalog>(SignedPackageCatalog.openWithRootFloor(root.public, listOf(a, b), path, 5, digest) { now })
        } finally {
            Files.newDirectoryStream(directory).use { entries -> entries.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    private fun open(path: Path, sources: List<CatalogSource> = listOf(a, b)) =
        SignedPackageCatalog.open(root.public, sources, path) { now }

    private fun withCheckpoint(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("cordiact-catalog-")
        try {
            val path = directory.resolve("catalog.state")
            assertFails { open(path) }
            assertFalse(Files.exists(path))
            SignedPackageCatalog.provision(root.public, listOf(a, b), path) { now }
            test(path)
        } finally {
            Files.newDirectoryStream(directory).use { entries -> entries.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    private val trustDigests = mutableMapOf<Int, String>()
    private fun trustBody(revision: Int, expires: Int = 2_000,
        grants: String = grant("alpha", "publisher-a", "alpha-key", alpha) + "," + grant("beta", "publisher-b", "beta-key", beta)) =
        """{"schema":1,"revision":$revision,"expires":$expires,"grants":[$grants]}""".toByteArray()

    private fun acceptTrust(revision: Int = 1, grants: String = grant("alpha", "publisher-a", "alpha-key", alpha) + "," + grant("beta", "publisher-b", "beta-key", beta),
        target: SignedPackageCatalog = catalog) {
        val raw = trustBody(revision, grants = grants)
        target.acceptTrust(raw, sign(root, TRUST_DOMAIN, raw))
        synchronized(trustDigests) { trustDigests[revision] = hash(raw) }
    }

    private fun acceptIndex(revision: Int = 1, expires: Int = 1_999, trustRevision: Int = 1, source: String = "alpha",
        publisher: String = "publisher-a", keyId: String = "alpha-key", key: KeyPair = alpha, rows: String = release(),
        target: SignedPackageCatalog = catalog) {
        val raw = index(revision, expires, trustRevision, source, publisher, keyId, rows)
        target.acceptIndex(source, keyId, raw, sign(key, INDEX_DOMAIN, raw))
    }

    private fun index(revision: Int = 1, expires: Int = 1_999, trustRevision: Int = 1, source: String = "alpha",
        publisher: String = "publisher-a", keyId: String = "alpha-key", rows: String = release(),
        trustDigest: String = synchronized(trustDigests) { trustDigests[trustRevision] } ?: hash(trustBody(trustRevision))) =
        """{"schema":1,"revision":$revision,"expires":$expires,"trustRevision":$trustRevision,"trustSha256":"$trustDigest","source":"$source","publisher":"$publisher","keyId":"$keyId","releases":[$rows]}""".toByteArray()

    private fun diagnosticRecord(receipt: ExtensionPackageSourceReceipt, bytes: ByteArray = alphaBytes) =
        ExtensionPackageRecord("a", ExtensionPackageArtifact.decode(bytes), "generation", 1, sourceReceipt = receipt)

    private fun release(id: String = "alpha-note", version: String = "1.2.3", digest: String = hash(alphaBytes)) =
        """{"id":"$id","version":"$version","sha256":"$digest"}"""

    private fun grant(source: String, publisher: String, keyId: String, key: KeyPair) =
        """{"source":"$source","publisher":"$publisher","keyId":"$keyId","spki":"${Base64.getEncoder().encodeToString(key.public.encoded)}"}"""

    private fun artifact(id: String, version: String) = """
        {"schemaVersion":1,"id":"$id","version":"$version","title":"Fixture","description":"Fixture declaration",
         "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["tool.publish"],"commands":[],
         "tools":[{"name":"show","description":"Show note","kind":"static_text","text":"literal"}]}
    """.trimIndent().toByteArray()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    private fun sign(key: KeyPair, domain: String, data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private); update(domain.toByteArray(Charsets.US_ASCII)); update(data); sign()
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
