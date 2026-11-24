package com.cordiact.interaction

import com.cordiact.core.plugin.ExtensionPackageChange
import com.cordiact.core.plugin.ExtensionPackageInstaller
import com.cordiact.core.plugin.InMemoryExtensionPackageStore
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.plugin.PluginToolContributions
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import com.cordiact.core.plugin.WorkspacePackageRuntime
import com.cordiact.core.security.EffectiveSecurityProfile
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceClientContributionsTest {
    private class Fixture {
        val a = InteractionContext("a-1", "a")
        val secondSession = InteractionContext("a-2", "a")
        val b = InteractionContext("b-1", "b")
        val commands = InteractionCommandRegistry { it in setOf(a, secondSession, b) }
        val catalog = WorkspaceClientContributions(commands)
        val tools = InMemoryToolDefinitionRegistry()
        val kernel = PluginKernel()
        var readOnly = false
        var checkBeforeCleanup: (() -> Unit)? = null
        val runtime = WorkspacePackageRuntime(admissionChanged = catalog::refreshAdmission) { record, gate ->
            val plugin = DeclarativePackagePlugin(record.manifest, kernel, PluginToolContributions(tools),
                PluginCommandContributions(commands), record.grantedCapabilities, workspaceId = record.workspaceId,
                clientContributions = catalog, admitted = gate)
            object : com.cordiact.core.plugin.RuntimeAwarePlugin by plugin {
                override fun onUninstall() {
                    checkBeforeCleanup?.invoke()
                    plugin.onUninstall()
                }
            }
        }
        val packages = WorkspaceExtensionPackages(ExtensionPackageInstaller(InMemoryExtensionPackageStore()), { id ->
            if (id == "a" && readOnly) EffectiveSecurityProfile.readOnly(id) else EffectiveSecurityProfile.fullAccess(id)
        }, runtime = runtime)
        init { packages.resume(Any()) }
        fun install(page: WorkspaceExtensionPackages.Page, id: String = "checklist", name: String = "release-checklist") =
            install(page, source(id, name).byteInputStream())
        fun install(page: WorkspaceExtensionPackages.Page, input: java.io.InputStream) = input.use {
            assertIs<ExtensionPackageChange.Applied>(page.approveInstall(page.preview(it)).commit()).record
        }
        fun offer(context: InteractionContext = a) = catalog.snapshot.value.sessionActions(context).single()
    }

    @Test
    fun `explicit enable projects a workspace only and stale offer cannot enter a replacement`() {
        val f = Fixture()
        val pageA = f.packages.openPage("a")
        val pageB = f.packages.openPage("b")
        val first = f.install(pageA)
        val second = f.install(pageB)
        assertTrue(f.catalog.snapshot.value.settingsPages("a").isEmpty())
        assertTrue(f.catalog.snapshot.value.sessionActions(f.a).isEmpty())
        assertNull(f.commands.find("release-checklist", f.a))

        val enabled = assertIs<ExtensionPackageChange.Applied>(pageA.approveEnable(first, true).commit()).record
        assertEquals("Release checklist", f.catalog.snapshot.value.settingsPages("a").single().description.title)
        assertTrue(f.catalog.snapshot.value.settingsPages("b").isEmpty())
        assertTrue(f.catalog.snapshot.value.sessionActions(f.b).isEmpty())
        val offer = f.offer()
        assertEquals("/release-checklist", f.catalog.draftInsertion(offer, f.a, "", false))
        assertTrue(f.catalog.isInsertionCurrent(offer))
        assertNull(f.catalog.draftInsertion(offer, f.secondSession, "", false))
        assertNull(f.catalog.draftInsertion(offer, f.b, "", false))
        assertNull(f.catalog.draftInsertion(offer, f.a, " ", false))
        assertNull(f.catalog.draftInsertion(offer, f.a, "", true))
        assertEquals("/release-checklist", f.catalog.draftInsertion(f.offer(f.secondSession), f.secondSession, "", false))
        val forged = f.a.copy(sessionId = "unknown")
        assertNull(f.catalog.draftInsertion(f.offer(forged), forged, "", false))

        pageB.approveEnable(second, true).commit()
        val otherOffer = f.offer(f.b)
        f.checkBeforeCleanup = {
            assertTrue(f.catalog.snapshot.value.settingsPages("a").isEmpty())
            assertTrue(f.catalog.snapshot.value.sessionActions(f.a).isEmpty())
            assertNull(f.catalog.draftInsertion(offer, f.a, "", false))
            assertNull(f.commands.find("release-checklist", f.a))
            assertEquals("/release-checklist", f.catalog.draftInsertion(otherOffer, f.b, "", false))
        }
        val disabled = assertIs<ExtensionPackageChange.Applied>(pageA.approveEnable(enabled, false).commit()).record
        f.checkBeforeCleanup = null
        pageA.approveEnable(disabled, true).commit()
        assertNotSame(offer.action, f.offer().action)
        assertNull(f.catalog.draftInsertion(offer, f.a, "", false))
        assertTrue(!f.catalog.isInsertionCurrent(offer))
        assertEquals("/release-checklist", f.catalog.draftInsertion(f.offer(), f.a, "", false))
        val current = f.offer()
        f.packages.whileUpdatingPolicy("a") { f.readOnly = true }
        assertNull(f.catalog.draftInsertion(current, f.a, "", false))
        assertTrue(f.catalog.snapshot.value.settingsPages("a").isEmpty())
        assertNotNull(f.commands.find("release-checklist", f.b))
    }

    @Test
    fun `name conflict never exposes a partial UI and a second package needs no host wiring`() {
        val f = Fixture()
        val page = f.packages.openPage("a")
        val blocker = f.commands.register(CommandDescriptor("host.block", "release-checklist", "host",
            executor = InteractionCommandExecutor { _, _, _, _ -> error("an insert must not execute anything") }))
        val installed = f.install(page)
        val failed = assertIs<ExtensionPackageChange.Applied>(page.approveEnable(installed, true).commit()).record
        assertEquals(WorkspacePackageRuntime.Status.FAILED, page.status(failed))
        assertTrue(f.catalog.snapshot.value.settingsPages("a").isEmpty())
        assertTrue(f.catalog.snapshot.value.sessionActions(f.a).isEmpty())
        f.commands.unregisterRegistration(blocker)
        page.approveRetryStart(failed).commit()
        val digest = f.install(page, "daily-notes", "daily-digest")
        page.approveEnable(digest, true).commit()
        val snapshot = f.catalog.snapshot.value
        assertEquals(listOf("pkg.checklist", "pkg.daily-notes"), snapshot.settingsPages("a").map { it.ownerPluginId })
        assertEquals(listOf("/release-checklist", "/daily-digest"), snapshot.sessionActions(f.a).map {
            f.catalog.draftInsertion(it, f.a, "", false)
        })
    }

    @Test
    fun `old snapshot and command offer check live admission before asynchronous cleanup`() {
        val a = InteractionContext("a-1", "a")
        val commands = InteractionCommandRegistry { it == a }
        val catalog = WorkspaceClientContributions(commands)
        val tools = InMemoryToolDefinitionRegistry()
        val manifest = com.cordiact.core.plugin.ExtensionPackageSource.compile(source("sample", "release-checklist").toByteArray())
        var valid = true
        val plugin = DeclarativePackagePlugin(manifest, PluginKernel(), PluginToolContributions(tools),
            PluginCommandContributions(commands), manifest.capabilities, workspaceId = "a",
            clientContributions = catalog, admitted = { valid })
        plugin.onRegister()
        plugin.onEnable()
        catalog.refreshAdmission()
        val old = catalog.snapshot.value
        val offer = old.sessionActions(a).single()
        assertEquals(1, old.settingsPages("a").size)
        assertNotNull(commands.find("release-checklist", a))
        valid = false
        assertTrue(old.settingsPages("a").isEmpty())
        assertTrue(old.sessionActions(a).isEmpty())
        assertNull(commands.find("release-checklist", a))
        assertNull(catalog.draftInsertion(offer, a, "", false))
        plugin.onUninstall()
    }

    @Test
    fun `the two distributed samples use the same catalog without host changes`() {
        val f = Fixture()
        val page = f.packages.openPage("a")
        val records = listOf("release-preflight.json", "session-notes.json").map { name ->
            f.install(page, requireNotNull(javaClass.getResourceAsStream("/$name")))
        }
        assertTrue(f.catalog.snapshot.value.settingsPages("a").isEmpty())
        records.forEach { page.approveEnable(it, true).commit() }
        val snapshot = f.catalog.snapshot.value
        assertEquals(listOf("发布前检查说明", "会话交接说明"), snapshot.settingsPages("a").map { it.description.title })
        assertEquals(listOf("/release-preflight", "/handoff-notes"), snapshot.sessionActions(f.a).map {
            f.catalog.draftInsertion(it, f.a, "", false)
        })
        assertTrue(snapshot.sessionActions(f.b).isEmpty())
    }

    companion object {
        private fun source(id: String, name: String) = """
            {"schemaVersion":2,"id":"$id","version":"1.0.0","title":"Release checklist","description":"Local release notes",
             "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["command.publish","ui.settings.publish","ui.session-action.publish"],
             "tools":[],"commands":[{"name":"$name","aliases":["$id-short"],"description":"Show notes","text":"Review the change"}],
             "client":{"settingsPage":{"title":"Release checklist","description":"No account required"},
                      "sessionActions":[{"id":"show","label":"Insert checklist command","command":"$name"}]}}
        """.trimIndent()
    }
}
