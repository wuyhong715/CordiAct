package com.cordiact.core.plugin

import java.util.concurrent.CancellationException
import kotlin.test.*

class PluginDiagnosticsTest {
    private val artifact = ExtensionPackageArtifact.decode("""
        {"schemaVersion":2,"id":"notes","version":"1.0.0","title":"PRIVATE_TITLE","description":"PRIVATE_DESCRIPTION",
         "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["ui.settings.publish"],"tools":[],"commands":[],
         "client":{"settingsPage":{"title":"Notes","description":"LOCAL_TEXT"}}}
    """.trimIndent().toByteArray())
    private val record = ExtensionPackageRecord("a", artifact, "PRIVATE_GENERATION", 1, previous = artifact, enableIntent = true)

    @Test
    fun `diagnostics project only stable metadata without calling lifecycle or constructing a runtime`() {
        val observed = object : RuntimeAwarePlugin {
            override val spec = PluginSpec("core.observed", "1.0.0", PluginTarget.ANDROID_NATIVE, config = "PRIVATE_CONFIG")
            override fun onRegister() = error("No register")
            override fun onEnable() = error("No enable")
            override fun onDisable() = error("No disable")
            override fun onUninstall() = error("No uninstall")
            override fun runtimeStatus() = PluginInstanceStatus(PluginInstanceId(spec.id), PluginInstanceState.WAITING,
                setOf("PRIVATE_SERVICE"), "PRIVATE_ERROR")
        }
        val unobserved = object : ManagedPlugin {
            override val spec = PluginSpec("core.unobserved", "0.1.0", PluginTarget.ANDROID_NATIVE)
            override fun onRegister() = error("No register")
        }
        val snapshot = PluginDiagnostics({ it == "a" }, { assertEquals("a", it); listOf(record) }, {
            assertSame(record, it)
            WorkspacePackageRuntime.Status.INACTIVE
        }, listOf(unobserved, observed)).snapshot("a")
        val row = assertIs<WorkspacePackageDiagnostics.Entries>(snapshot.packages).items.single()
        assertEquals(WorkspacePackageDiagnostic("notes", "1.0.0", ExtensionPackageArtifact.Format.DECLARATIVE,
            true, WorkspacePackageRuntime.Status.INACTIVE, listOf(ExtensionPackageCapability.UI_SETTINGS_PUBLISH), true), row)
        assertEquals(listOf(BundledPluginDiagnostic("core.observed", "1.0.0", PluginInstanceState.WAITING),
            BundledPluginDiagnostic("core.unobserved", "0.1.0", null)), snapshot.bundled)
        assertFalse(snapshot.toString().contains("PRIVATE"))
        assertFalse(snapshot.toString().contains("LOCAL_TEXT"))
    }

    @Test
    fun `missing unreadable and genuinely empty remain distinguishable`() {
        fun read(reader: (String) -> List<ExtensionPackageRecord>?) = PluginDiagnostics({ true }, reader,
            { error("No records") }, emptyList()).snapshot("a").packages
        assertEquals(WorkspacePackageDiagnostics.StorageNotCreated, read { null })
        assertEquals(WorkspacePackageDiagnostics.StorageUnavailable, read { throw ExtensionPackageStoreException() })
        assertEquals(WorkspacePackageDiagnostics.Entries(emptyList()), read { emptyList() })
        assertEquals(WorkspacePackageDiagnostics.StorageUnavailable, read { listOf(record.copy(workspaceId = "b")) })
        assertEquals(WorkspacePackageDiagnostics.StorageUnavailable, read { listOf(record, record) })
    }

    @Test
    fun `migrated declarations keep their format and observation failure does not erase installation`() {
        val legacy = ExtensionPackageArtifact.decode(
            """{"schemaVersion":1,"id":"legacy-note","title":"Private","tools":[{"name":"show","description":"Show","text":"Do not show"}]}""".toByteArray(),
            ExtensionPackageArtifact.Format.LEGACY_AUTHORED, "legacy-note",
        )
        val items = assertIs<WorkspacePackageDiagnostics.Entries>(PluginDiagnostics({ true },
            { listOf(record.copy(artifact = legacy, previous = null), record) },
            { if (it.manifest.id == "legacy-note") error("No runtime observation") else WorkspacePackageRuntime.Status.FAILED },
            emptyList()).snapshot("a").packages).items
        assertEquals(listOf("legacy-note", "notes"), items.map { it.id })
        assertEquals(ExtensionPackageArtifact.Format.LEGACY_AUTHORED, items.first().format)
        assertEquals(listOf(ExtensionPackageCapability.TOOL_PUBLISH), items.first().approvedContributions)
        assertFalse(items.first().hasRollback)
        assertNull(items.first().runtime)
        assertEquals(WorkspacePackageRuntime.Status.FAILED, items.last().runtime)
    }

    @Test
    fun `one signed observation projects historical provenance separately from runtime and local packages`() {
        val current = ExtensionPackageSourceReceipt("source", "publisher", "current-key", 3, 5)
        val previous = current.copy(keyId = "previous-key", trustRevision = 2, indexRevision = 4)
        val signed = record.copy(sourceReceipt = current, previousSourceReceipt = previous)
        val local = record.copy(artifact = ExtensionPackageArtifact.decode(
            artifact.copySource().decodeToString().replace("\"id\":\"notes\"", "\"id\":\"local-notes\"").toByteArray(),
        ))
        val assessed = mutableListOf<ExtensionPackageRecord>()
        var observations = 0
        val snapshot = PluginDiagnostics({ it == "a" }, { listOf(signed, local) },
            { WorkspacePackageRuntime.Status.INACTIVE }, emptyList(), signedObservation = {
                observations++
                SignedCatalogObservation(SignedCatalogDiagnosticState.OBSERVED, 6) {
                    assessed += it
                    SignedReleaseDiagnostic.RELEASE_MATCHES
                }
            }).snapshot("a")
        assertEquals(1, observations)
        assertEquals(listOf(signed), assessed)
        assertEquals(SignedCatalogDiagnosticState.OBSERVED, snapshot.signedCatalog)
        assertEquals(6L, snapshot.observedRootRevision)
        val items = assertIs<WorkspacePackageDiagnostics.Entries>(snapshot.packages).items
        assertEquals(listOf("local-notes", "notes"), items.map { it.id })
        assertNull(items.first().sourceReceipt)
        assertNull(items.first().signedRelease)
        assertEquals(WorkspacePackageDiagnostic("notes", "1.0.0", ExtensionPackageArtifact.Format.DECLARATIVE,
            true, WorkspacePackageRuntime.Status.INACTIVE, listOf(ExtensionPackageCapability.UI_SETTINGS_PUBLISH), true,
            current, SignedReleaseDiagnostic.RELEASE_MATCHES), items.last())
        assertFalse(snapshot.toString().contains(previous.keyId))
        assertFalse(snapshot.toString().contains(signed.manifest.sha256))
    }

    @Test
    fun `unavailable or failed signed observations do not erase installation metadata`() {
        val receipt = ExtensionPackageSourceReceipt("source", "publisher", "test-key", 1, 1)
        val signed = record.copy(previous = null, sourceReceipt = receipt)
        fun read(observation: () -> SignedCatalogObservation?) = PluginDiagnostics({ true }, { listOf(signed) },
            { WorkspacePackageRuntime.Status.TRUST_WITHDRAWN }, emptyList(), observation).snapshot("a")
        val unavailable = listOf(
            SignedCatalogObservation.notConfigured() to SignedCatalogDiagnosticState.NOT_CONFIGURED,
            SignedCatalogObservation.invalidPolicy() to SignedCatalogDiagnosticState.INVALID_POLICY,
            SignedCatalogObservation.notOpened() to SignedCatalogDiagnosticState.NOT_OPENED,
        )
        for ((observation, state) in unavailable) {
            val snapshot = read { observation }
            val row = assertIs<WorkspacePackageDiagnostics.Entries>(snapshot.packages).items.single()
            assertEquals(state, snapshot.signedCatalog)
            assertNull(snapshot.observedRootRevision)
            assertEquals(receipt, row.sourceReceipt)
            assertEquals(SignedReleaseDiagnostic.CATALOG_UNAVAILABLE, row.signedRelease)
            assertEquals(WorkspacePackageRuntime.Status.TRUST_WITHDRAWN, row.runtime)
        }

        val missing = read { null }
        assertNull(missing.signedCatalog)
        assertNull(missing.observedRootRevision)
        val retained = assertIs<WorkspacePackageDiagnostics.Entries>(missing.packages).items.single()
        assertEquals(receipt, retained.sourceReceipt)
        assertNull(retained.signedRelease)
        assertEquals(missing, read { error("PRIVATE_CATALOG_ERROR") })
        val failedRelease = read {
            SignedCatalogObservation(SignedCatalogDiagnosticState.OBSERVED, 1) { error("PRIVATE_RELEASE_ERROR") }
        }
        assertEquals(missing.copy(signedCatalog = SignedCatalogDiagnosticState.OBSERVED, observedRootRevision = 1), failedRelease)
    }

    @Test
    fun `an unavailable package store does not erase the catalog observation`() {
        val snapshot = PluginDiagnostics({ true }, { throw ExtensionPackageStoreException() }, { fail("No record to inspect") },
            emptyList(), { SignedCatalogObservation(SignedCatalogDiagnosticState.EXPIRED, 8) }).snapshot("a")
        assertEquals(WorkspacePackageDiagnostics.StorageUnavailable, snapshot.packages)
        assertEquals(SignedCatalogDiagnosticState.EXPIRED, snapshot.signedCatalog)
        assertEquals(8L, snapshot.observedRootRevision)
    }

    @Test
    fun `cancellation from either signed observation layer is propagated`() {
        val signed = record.copy(previous = null, sourceReceipt = ExtensionPackageSourceReceipt("source", "publisher", "test-key", 1, 1))
        val cancelled = CancellationException("cancelled")
        val catalog = PluginDiagnostics({ true }, { fail("Cancellation before package read") }, { null }, emptyList(),
            { throw cancelled })
        assertSame(cancelled, assertFailsWith<CancellationException> { catalog.snapshot("a") })
        val release = PluginDiagnostics({ true }, { listOf(signed) }, { null }, emptyList(), {
            SignedCatalogObservation(SignedCatalogDiagnosticState.OBSERVED, 1) { throw cancelled }
        })
        assertSame(cancelled, assertFailsWith<CancellationException> { release.snapshot("a") })
    }

    @Test
    fun `reselecting the same workspace during signed observation does not read its package store`() {
        var scope = Any()
        val original = scope
        val service = PluginDiagnostics({ it == "a" && scope === original }, { fail("Invalidated scope must not read packages") },
            { fail("Invalidated scope must not inspect runtime") }, emptyList(), {
                scope = Any()
                SignedCatalogObservation.notOpened()
            })
        assertEquals("PLUGIN_DIAGNOSTICS_WORKSPACE_UNAVAILABLE", assertFailsWith<IllegalStateException> { service.snapshot("a") }.message)
    }

    @Test
    fun `leaving the selected workspace discards the result and does not query another`() {
        var selected = "a"
        val calls = mutableListOf<String>()
        val service = PluginDiagnostics({ it == selected }, { workspace ->
            calls += workspace
            selected = "b"
            listOf(record)
        }, { throw AssertionError("Old scope must not inspect runtime") }, emptyList())
        assertFailsWith<IllegalStateException> { service.snapshot("b") }
        assertFailsWith<IllegalStateException> { service.snapshot("a") }
        assertEquals(listOf("a"), calls)
        val local = PluginDiagnostics({ it == "b" }, { listOf(record.copy(workspaceId = "b", enableIntent = false)) },
            { WorkspacePackageRuntime.Status.CLEANUP_PENDING }, emptyList()).snapshot("b")
        val item = assertIs<WorkspacePackageDiagnostics.Entries>(local.packages).items.single()
        assertFalse(item.enableIntent)
        assertEquals(WorkspacePackageRuntime.Status.CLEANUP_PENDING, item.runtime)
    }
}
