package com.cordiact.core.plugin

import com.cordiact.core.security.EffectiveSecurityProfile
import java.io.ByteArrayInputStream
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
        val second = fixture.page.approveInstall(preview)
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
