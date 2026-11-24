package com.cordiact.core.plugin

import com.cordiact.core.security.EffectiveSecurityProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceExtensionPackagesTest {
    @Test
    fun `preview does not install and confirmation publishes only its selected workspace and bytes`() {
        val fixture = Fixture()
        val other = fixture.service.openPage("other")
        val bytes = source()
        val expected = bytes.copyOf()
        val preview = fixture.page.preview(bytes.inputStream())
        assertTrue(fixture.page.list().isEmpty())
        assertFailsWith<IllegalArgumentException> { other.approveInstall(preview) }
        bytes.fill(0)
        val confirmed = fixture.page.approveInstall(preview)
        assertTrue(fixture.page.list().isEmpty())
        assertIs<ExtensionPackageChange.Applied>(confirmed.commit())
        assertContentEquals(expected, fixture.page.list().single().artifact.copySource())
        assertTrue(other.list().isEmpty())
        assertFailsWith<IllegalStateException> { confirmed.commit() }
    }

    @Test
    fun `source is bounded and reserved ids are not eligible for preview`() {
        val fixture = Fixture()
        val tooLarge = ByteArray(ExtensionPackageSource.MAX_SOURCE_BYTES + 512)
        val stream = ByteArrayInputStream(tooLarge)
        assertFailsWith<IllegalArgumentException> { fixture.page.preview(stream) }
        assertEquals(511, stream.available())
        assertEquals("PACKAGE_ID_RESERVED", assertFailsWith<IllegalArgumentException> {
            fixture.page.preview(source(id = "core-notes").inputStream())
        }.message)
        assertTrue(fixture.page.list().isEmpty())
    }

    @Test
    fun `returning to foreground or closing the page retires queued confirmations`() {
        val fixture = Fixture()
        val preview = fixture.page.preview(source().inputStream())
        val first = fixture.page.approveInstall(preview)
        fixture.service.pause(fixture.activity)
        fixture.service.resume(fixture.activity)
        assertFailsWith<IllegalStateException> { first.commit() }
        assertEquals("PACKAGE_CONFIRMATION_EXPIRED", assertFailsWith<IllegalStateException> {
            fixture.page.approveInstall(preview)
        }.message)
        val second = fixture.page.approveInstall(fixture.page.preview(source().inputStream()))
        fixture.page.close()
        assertFailsWith<IllegalStateException> { second.commit() }
        assertTrue(fixture.service.openPage("ws").list().isEmpty())
    }

    @Test
    fun `rechecks policy at publication after storage prepares the change`() {
        val backing = InMemoryExtensionPackageStore()
        lateinit var fixture: Fixture
        val store = object : ExtensionPackageStore by backing {
            override fun compareAndSet(
                workspaceId: String, packageId: String, expectedInstallationId: String?, replacement: ExtensionPackageRecord?,
                publish: (() -> Unit) -> Unit,
            ): ExtensionPackageStoreWrite {
                fixture.service.whileUpdatingPolicy(workspaceId) { fixture.readOnly = true }
                return backing.compareAndSet(workspaceId, packageId, expectedInstallationId, replacement, publish)
            }
        }
        fixture = Fixture(store)
        val preview = fixture.page.preview(source().inputStream())
        assertTrue(preview.canInstall)
        val change = fixture.page.approveInstall(preview)
        assertEquals("PACKAGE_CONFIRMATION_EXPIRED", assertFailsWith<IllegalStateException> { change.commit() }.message)
        assertTrue(fixture.page.list().isEmpty())
        val readOnlyPreview = fixture.page.preview(source().inputStream())
        assertEquals(false, readOnlyPreview.canInstall)
        assertEquals("PACKAGE_WORKSPACE_READ_ONLY", assertFailsWith<IllegalStateException> {
            fixture.page.approveInstall(readOnlyPreview)
        }.message)
    }

    @Test
    fun `failed start can retry the same version without rewriting the approval`() {
        var starts = 0
        val runtime = WorkspacePackageRuntime { record, admitted ->
            object : RuntimeAwarePlugin {
                override val spec = extensionPackageSpec(record.manifest)
                override fun onEnable() {
                    assertEquals(false, admitted())
                    if (++starts == 1) error("test start failure")
                }
                override fun onDisable() = Unit
                override fun runtimeStatus() = PluginInstanceStatus(PluginInstanceId(spec.id, record.workspaceId), PluginInstanceState.ACTIVE, emptySet(), null)
            }
        }
        val fixture = Fixture(runtime = runtime)
        val installed = fixture.install()
        val selected = assertIs<ExtensionPackageChange.Applied>(fixture.page.approveEnable(installed, true).commit()).record
        assertEquals(WorkspacePackageRuntime.Status.FAILED, fixture.page.status(selected))
        assertEquals(null, fixture.service.scopedOwnerState("pkg.notes", "ws", null))
        // Settings can enable an unentered workspace. Visiting it must not silently retry a failure.
        fixture.service.enterWorkspace(selected.workspaceId)
        assertEquals(1, starts)
        assertEquals(WorkspacePackageRuntime.Status.FAILED, fixture.page.status(selected))
        val retry = fixture.page.approveRetryStart(selected)
        assertIs<ExtensionPackageChange.Unchanged>(retry.commit())
        assertEquals(WorkspacePackageRuntime.Status.ACTIVE, fixture.page.status(selected))
        assertEquals(selected.installationId, fixture.page.list().single().installationId)
        assertEquals(2, starts)
        assertFailsWith<IllegalStateException> { retry.commit() }
        assertFailsWith<IllegalStateException> { fixture.page.approveRetryStart(selected).commit() }
        assertEquals(2, starts)
    }

    @Test
    fun `failed cleanup retires admission before retry and blocks package removal`() {
        var removals = 0
        val runtime = WorkspacePackageRuntime { record, admitted ->
            object : RuntimeAwarePlugin {
                override val spec = extensionPackageSpec(record.manifest)
                override fun onEnable() = Unit
                override fun onDisable() = Unit
                override fun onUninstall() {
                    assertEquals(false, admitted())
                    if (++removals == 1) error("test cleanup failure")
                }
                override fun runtimeStatus() = PluginInstanceStatus(PluginInstanceId(spec.id, record.workspaceId), PluginInstanceState.ACTIVE, emptySet(), null)
            }
        }
        val fixture = Fixture(runtime = runtime)
        val selected = assertIs<ExtensionPackageChange.Applied>(fixture.page.approveEnable(fixture.install(), true).commit()).record
        val disabled = assertIs<ExtensionPackageChange.Applied>(fixture.page.approveEnable(selected, false).commit()).record
        assertEquals(false, disabled.enableIntent)
        assertEquals(WorkspacePackageRuntime.Status.CLEANUP_PENDING, fixture.page.status(disabled))
        assertEquals(null, fixture.service.scopedOwnerState("pkg.notes", "ws", null))
        assertFailsWith<IllegalStateException> { fixture.page.approveRemoval(disabled).commit() }
        assertIs<ExtensionPackageChange.Unchanged>(fixture.page.approveEnable(disabled, false).commit())
        assertEquals(WorkspacePackageRuntime.Status.INACTIVE, fixture.page.status(disabled))
        assertEquals(ExtensionPackageChange.Removed, fixture.page.approveRemoval(disabled).commit())
        assertEquals(2, removals)
    }

    @Test
    fun `pause during explicit startup leaves saved intent but never admits that generation`() {
        lateinit var fixture: Fixture
        var starts = 0
        var disposals = 0
        val runtime = WorkspacePackageRuntime { record, admitted ->
            object : RuntimeAwarePlugin {
                override val spec = extensionPackageSpec(record.manifest)
                override fun onEnable() {
                    if (++starts == 1) fixture.service.pause(fixture.activity)
                }
                override fun onDisable() = Unit
                override fun onUninstall() { assertEquals(false, admitted()); disposals++ }
                override fun runtimeStatus() = PluginInstanceStatus(PluginInstanceId(spec.id, record.workspaceId), PluginInstanceState.ACTIVE, emptySet(), null)
            }
        }
        fixture = Fixture(runtime = runtime)
        val selected = assertIs<ExtensionPackageChange.Applied>(fixture.page.approveEnable(fixture.install(), true).commit()).record
        assertTrue(fixture.page.list().single().enableIntent)
        assertEquals(WorkspacePackageRuntime.Status.FAILED, fixture.page.status(selected))
        assertEquals(null, fixture.service.scopedOwnerState("pkg.notes", "ws", null))
        assertEquals(1, disposals)
        fixture.service.resume(fixture.activity)
        fixture.service.enterWorkspace("ws")
        assertEquals(1, starts)
        fixture.page.approveRetryStart(selected).commit()
        assertEquals(2, starts)
        assertEquals(WorkspacePackageRuntime.Status.ACTIVE, fixture.page.status(selected))
    }

    @Test
    fun `commit unknown enable does not get replayed by first workspace entry`() {
        val backing = InMemoryExtensionPackageStore()
        var injectUnknown = true
        val store = object : ExtensionPackageStore by backing {
            override fun compareAndSet(
                workspaceId: String, packageId: String, expectedInstallationId: String?, replacement: ExtensionPackageRecord?,
                publish: (() -> Unit) -> Unit,
            ): ExtensionPackageStoreWrite {
                val result = backing.compareAndSet(workspaceId, packageId, expectedInstallationId, replacement, publish)
                if (replacement?.enableIntent == true && injectUnknown) throw ExtensionPackageStoreException(commitUnknown = true)
                return result
            }
        }
        var starts = 0
        val runtime = WorkspacePackageRuntime { record, _ ->
            object : RuntimeAwarePlugin {
                override val spec = extensionPackageSpec(record.manifest)
                override fun onEnable() { starts++ }
                override fun onDisable() = Unit
                override fun runtimeStatus() = PluginInstanceStatus(PluginInstanceId(spec.id, record.workspaceId), PluginInstanceState.ACTIVE, emptySet(), null)
            }
        }
        val fixture = Fixture(store, runtime)
        val unknown = assertIs<ExtensionPackageChange.Rejected>(fixture.page.approveEnable(fixture.install(), true).commit())
        assertEquals(ExtensionPackageChange.Code.COMMIT_UNKNOWN, unknown.code)
        val reconciled = fixture.page.list().single()
        assertTrue(reconciled.enableIntent)
        fixture.service.enterWorkspace("ws")
        assertEquals(0, starts)
        assertEquals(WorkspacePackageRuntime.Status.INACTIVE, fixture.page.status(reconciled))
        injectUnknown = false
        // This is a new, exact foreground confirmation for the reconciled row, never an automatic retry.
        assertIs<ExtensionPackageChange.Unchanged>(fixture.page.approveEnable(reconciled, true).commit())
        assertEquals(1, starts)
    }

    @Test
    fun `export preserves exact approved bytes and reimport grants nothing until its own confirmation`() {
        val fixture = Fixture()
        val bytes = source() + "\r\n  ".toByteArray()
        val record = assertIs<ExtensionPackageChange.Applied>(fixture.page.approveInstall(fixture.page.preview(bytes.inputStream())).commit()).record
        fixture.page.requireExportDestinationSelection(record)
        val preview = fixture.page.previewExport(record)
        assertEquals(record.manifest.sha256, preview.manifest.sha256)
        val export = fixture.page.approveExport(preview)
        assertFailsWith<IllegalStateException> { fixture.page.approveExport(preview) }
        val output = ByteArrayOutputStream()
        assertEquals(0, output.size())
        assertEquals(WorkspaceExtensionPackages.ExportResult.WRITTEN, export.write { output })
        assertContentEquals(bytes, output.toByteArray())
        assertFailsWith<IllegalStateException> { export.write { error("Do not reopen") } }
        assertEquals(listOf(record.installationId), fixture.page.list().map { it.installationId })
        val recipient = fixture.service.openPage("recipient")
        val received = recipient.preview(output.toByteArray().inputStream())
        assertEquals(preview.manifest, received.manifest)
        assertTrue(recipient.list().isEmpty())
        val installed = assertIs<ExtensionPackageChange.Applied>(recipient.approveInstall(received).commit()).record
        assertEquals(false, installed.enableIntent)
        assertEquals(WorkspacePackageRuntime.Status.INACTIVE, recipient.status(installed))
        assertTrue(installed.installationId != record.installationId)
    }

    @Test
    fun `export is page scoped and cannot follow a stale or replaced generation`() {
        val fixture = Fixture()
        val record = fixture.install()
        val preview = fixture.page.previewExport(record)
        assertFailsWith<IllegalStateException> { fixture.service.openPage("ws").approveExport(preview) }
        assertFailsWith<IllegalArgumentException> { fixture.service.openPage("other").previewExport(record) }
        val approved = fixture.page.approveExport(preview)
        val updated = source().decodeToString().replace("\"version\":\"1.0.0\"", "\"version\":\"1.0.1\"")
        assertIs<ExtensionPackageChange.Applied>(fixture.page.approveInstall(fixture.page.preview(updated.byteInputStream())).commit())
        assertFailsWith<IllegalStateException> { fixture.page.previewExport(record) }
        assertFailsWith<IllegalStateException> { approved.write { error("A stale record must not open a provider") } }
    }

    @Test
    fun `export refuses read only and after a foreground change neither old preview nor old permit is revived`() {
        val fixture = Fixture()
        val record = fixture.install()
        val preview = fixture.page.previewExport(record)
        val permit = fixture.page.approveExport(preview)
        fixture.service.pause(fixture.activity)
        fixture.service.resume(fixture.activity)
        assertFailsWith<IllegalStateException> { fixture.page.approveExport(preview) }
        assertFailsWith<IllegalStateException> { permit.write { error("Old permit must not open a provider") } }
        fixture.readOnly = true
        assertEquals("PACKAGE_EXPORT_READ_ONLY", assertFailsWith<IllegalStateException> {
            fixture.page.requireExportDestinationSelection(record)
        }.message)
        assertFailsWith<IllegalStateException> { fixture.page.previewExport(record) }
    }

    @Test
    fun `if provider open is slow the page and profile are checked again before sending bytes`() {
        val fixture = Fixture()
        val record = fixture.install()
        val operation = fixture.page.approveExport(fixture.page.previewExport(record))
        val output = ByteArrayOutputStream()
        assertEquals(WorkspaceExtensionPackages.ExportResult.DESTINATION_UNCONFIRMED, operation.write {
            fixture.service.whileUpdatingPolicy("ws") { fixture.readOnly = true }
            output
        })
        assertEquals(0, output.size())
        assertFailsWith<IllegalStateException> { operation.write { error("Never replay") } }
    }

    @Test
    fun `provider failures are not reported as success and legacy authored bytes are not exportable as declarations`() {
        val fixture = Fixture()
        val record = fixture.install()
        val operation = fixture.page.approveExport(fixture.page.previewExport(record))
        var opened = 0
        assertEquals(WorkspaceExtensionPackages.ExportResult.DESTINATION_UNCONFIRMED, operation.write {
            opened++
            object : ByteArrayOutputStream() { override fun close() { throw IOException("provider failure") } }
        })
        assertEquals(1, opened)
        assertFailsWith<IllegalStateException> { operation.write { error("No automatic retry") } }
        val artifact = ExtensionPackageArtifact.decode(
            """{"schemaVersion":1,"id":"legacy","title":"Legacy","tools":[{"name":"show","description":"Show","text":"Sample"}]}""".toByteArray(),
            ExtensionPackageArtifact.Format.LEGACY_AUTHORED, "legacy",
        )
        val migrated = assertIs<ExtensionPackageChange.Applied>(fixture.page.approveSavedTrial(fixture.page.previewSavedTrial { artifact }) { artifact }.commit()).record
        assertEquals("PACKAGE_EXPORT_FORMAT_UNSUPPORTED", assertFailsWith<IllegalStateException> {
            fixture.page.previewExport(migrated)
        }.message)
    }

    private fun Fixture.install(): ExtensionPackageRecord =
        assertIs<ExtensionPackageChange.Applied>(page.approveInstall(page.preview(source().inputStream())).commit()).record

    private class Fixture(store: ExtensionPackageStore = InMemoryExtensionPackageStore(), runtime: WorkspacePackageRuntime? = null) {
        var readOnly = false
        val activity = Any()
        val service = WorkspaceExtensionPackages(ExtensionPackageInstaller(store), { workspace ->
            if (readOnly) EffectiveSecurityProfile.readOnly(workspace) else EffectiveSecurityProfile.fullAccess(workspace)
        }, setOf("core-notes"), runtime)
        val page = service.openPage("ws")
        init { service.resume(activity) }
    }

    private fun source(id: String = "notes") = """
        {"schemaVersion":1,"id":"$id","version":"1.0.0","title":"Notes","description":"Notes",
         "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["tool.publish"],"commands":[],
         "tools":[{"name":"show","description":"Show note","kind":"static_text","text":"literal"}]}
    """.trimIndent().toByteArray()
}
