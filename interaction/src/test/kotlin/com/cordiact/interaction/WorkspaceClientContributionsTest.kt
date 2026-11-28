package com.cordiact.interaction

import com.cordiact.core.conversation.ToolHistoryFormat
import com.cordiact.core.conversation.ToolHistoryProvenance
import com.cordiact.core.conversation.ToolHistorySource
import com.cordiact.core.plugin.ExtensionPackageMessageEvent
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
    @Test
    fun `slot components publish to their host and withdraw their draft action with the package`() {
        val f = Fixture()
        val json = """{
            "schemaVersion":14,"id":"slot-pack","version":"1.0.0","title":"Slots","description":"Assistant controls",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["command.publish","ui.session-action.publish","ui.components.publish"],
            "tools":[],"commands":[{"name":"review-now","aliases":[],"description":"Review","text":"Review ready"}],
            "client":{"sessionActions":[{"id":"show-review","label":"Add review","command":"review-now"}],"components":[
                {"id":"header-help","slot":"session.toolbar","label":"Help","nodes":[{"text":"Review conversation"}]},
                {"id":"draft-help","slot":"composer.accessories","label":"Review",
                    "nodes":[{"label":"Commands","children":[{"action":"show-review"}]}]}
            ]}
        }""".trimIndent()
        val page = f.packages.openPage("a")
        val installed = f.install(page, json.byteInputStream())
        assertTrue(f.components.isEmpty())
        val enabled = assertIs<ExtensionPackageChange.Applied>(page.approveEnable(installed, true).commit()).record
        assertEquals(listOf("session.toolbar", "composer.accessories"), f.components.map { it.description.slot.wireName })
        val draft = f.components.last()
        assertNull(draft.offer("show-review", f.b))
        val offer = assertNotNull(draft.offer("show-review", f.a))
        assertEquals("/review-now", f.catalog.draftInsertion(offer, f.a, "", false))
        page.approveEnable(enabled, false).commit()
        assertNull(draft.offer("show-review", f.a))
        assertNull(f.catalog.draftInsertion(offer, f.a, "", false))
    }

    @Test
    fun `panel state is isolated and a retired panel cannot change state or offer its command`() {
        val f = Fixture()
        val json = """{
            "schemaVersion":13,"id":"panel-pack","version":"1.0.0","title":"Panel","description":"Panel actions",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["command.publish","ui.session-action.publish",
                "ui.workbench-panel.publish","ui.workbench-controls.publish"],
            "tools":[],"commands":[{"name":"review-now","aliases":[],"description":"Review","text":"Review ready"}],
            "client":{"sessionActions":[{"id":"show-review","label":"Add review","command":"review-now"}],
                "workbenchPanel":{"title":"Panel","description":"Checklist",
                "controls":[{"id":"review-step","label":"Review","content":"Check release","action":"show-review"}]}}
        }""".trimIndent()
        for (invalid in listOf(
            json.replace("\"schemaVersion\":13", "\"schemaVersion\":12"),
            json.replace("\"action\":\"show-review\"", "\"action\":\"wrong-action\""),
        )) {
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                com.cordiact.core.plugin.ExtensionPackageSource.compile(invalid.encodeToByteArray())
            }
        }
        val a = f.packages.openPage("a")
        val b = f.packages.openPage("b")
        val installedA = f.install(a, json.byteInputStream())
        val installedB = f.install(b, json.byteInputStream())
        val enabled = assertIs<ExtensionPackageChange.Applied>(a.approveEnable(installedA, true).commit()).record
        b.approveEnable(installedB, true).commit()
        val panel = f.panels.getValue("a")
        assertNull(panel.action("review-step", f.a))
        panel.setExpanded("review-step", true)
        assertTrue(f.panels.getValue("b").expanded.value.isEmpty())
        assertNull(panel.action("review-step", f.b))
        val offer = assertNotNull(panel.action("review-step", f.a))
        assertEquals("/review-now", f.catalog.draftInsertion(offer, f.a, "", false))
        val disabled = assertIs<ExtensionPackageChange.Applied>(a.approveEnable(enabled, false).commit()).record
        panel.setExpanded("review-step", false)
        assertEquals(setOf("review-step"), panel.expanded.value)
        assertNull(panel.action("review-step", f.a))
        a.approveEnable(disabled, true).commit()
        assertTrue(f.panels.getValue("a").expanded.value.isEmpty())
        assertNull(f.catalog.draftInsertion(offer, f.a, "", false))
    }

    @Test
    fun `message nodes and result cards are scoped and old tool results retain their own release identity`() {
        val f = Fixture()
        val page = f.packages.openPage("a")
        fun source(version: String) = """{
            "schemaVersion":11,"id":"views-pack","version":"$version","title":"Views","description":"Session views",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["ui.conversation.publish","tool.publish"],
            "tools":[{"name":"test-text","description":"Test text","kind":"static_text","text":"check"}],"commands":[],
            "client":{"conversation":{"messages":[{"event":"assistant.message","title":"Reply","lines":["Review the answer"]}],
                "toolResults":[{"tool":"test-text","title":"Output $version","lines":["A result was observed"]}]}}
        }""".trimIndent().byteInputStream()
        val installed = f.install(page, source("1.0.0"))
        val initial = f.catalog.snapshot.value
        assertTrue(initial.messageNodes("a", ExtensionPackageMessageEvent.ASSISTANT).isEmpty())
        val enabled = assertIs<ExtensionPackageChange.Applied>(page.approveEnable(installed, true).commit()).record
        val active = f.catalog.snapshot.value
        val provenance = ToolHistoryProvenance("pkg.views-pack", "extension.pkg.views-pack.test-text", ToolHistorySource.HOST_REGISTRATION,
            ToolHistoryFormat.BOUNDED_SUMMARY_V1, installed.manifest.sha256)
        assertEquals("views-pack@1.0.0", active.messageNodes("a", ExtensionPackageMessageEvent.ASSISTANT).single().source)
        assertTrue(active.messageNodes("a", ExtensionPackageMessageEvent.USER).isEmpty())
        assertTrue(active.messageNodes("b", ExtensionPackageMessageEvent.ASSISTANT).isEmpty())
        assertEquals("Output 1.0.0", active.toolResultCard("a", provenance)?.title)
        assertNull(active.toolResultCard("b", provenance))
        assertNull(active.toolResultCard("a", provenance.copy(packageSha256 = null)))

        page.approveEnable(enabled, false).commit()
        assertNull(active.toolResultCard("a", provenance))
        assertTrue(active.messageNodes("a", ExtensionPackageMessageEvent.ASSISTANT).isEmpty())
        val upgraded = f.install(page, source("1.0.1"))
        page.approveEnable(upgraded, true).commit()
        val replacement = f.catalog.snapshot.value
        assertNull(replacement.toolResultCard("a", provenance))
        assertEquals("Output 1.0.1", replacement.toolResultCard("a", provenance.copy(packageSha256 = upgraded.manifest.sha256))?.title)
    }

    private class Fixture {
        val a = InteractionContext("a-1", "a")
        val secondSession = InteractionContext("a-2", "a")
        val b = InteractionContext("b-1", "b")
        val commands = InteractionCommandRegistry { it in setOf(a, secondSession, b) }
        val tabs = TrustedWorkbenchTabs<String>()
        val panels = mutableMapOf<String, WorkspaceClientContributions.WorkbenchPanel>()
        val components = mutableListOf<WorkspaceClientContributions.SlotComponent>()
        val catalog = WorkspaceClientContributions(commands, publishComponent = { _, component, _ -> components += component }) { context, panel, admitted ->
            panels[requireNotNull(context.id.workspaceId)] = panel
            tabs.register(context, context.id.pluginId, panel.description.title, admitted = admitted)
        }
        val tools = InMemoryToolDefinitionRegistry()
        val kernel = PluginKernel(tabs::refresh)
        var readOnly = false
        var checkBeforeCleanup: (() -> Unit)? = null
        val runtime = WorkspacePackageRuntime(admissionChanged = { catalog.refreshAdmission(); tabs.refresh() }) { record, gate ->
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

    @Test
    fun `the release panel follows the exact workspace installation and withdraws before cleanup`() {
        val f = Fixture()
        val page = f.packages.openPage("a")
        val record = f.install(page, requireNotNull(javaClass.getResourceAsStream("/release-panel.json")))
        assertTrue(f.tabs.snapshot.value.visible("a", "a-1").isEmpty())

        val enabled = assertIs<ExtensionPackageChange.Applied>(page.approveEnable(record, true).commit()).record
        val old = f.tabs.snapshot.value
        val panel = old.visible("a", "a-2").single()
        assertEquals("pkg.release-panel", panel.id)
        assertEquals("发布工作台", panel.content)
        assertTrue(old.visible("b", "b-1").isEmpty())

        f.checkBeforeCleanup = {
            assertTrue(old.visible("a", "a-1").isEmpty())
            assertTrue(!f.tabs.isCurrent(panel, "a", "a-1"))
        }
        val disabled = assertIs<ExtensionPackageChange.Applied>(page.approveEnable(enabled, false).commit()).record
        f.checkBeforeCleanup = null
        page.approveEnable(disabled, true).commit()
        assertNotSame(panel, f.tabs.snapshot.value.visible("a", "a-1").single())
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
