package com.cordiact.app.plugin

import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cordiact.app.conversation.AndroidConversationSessionStore
import com.cordiact.app.conversation.BundledConversationPlugins
import com.cordiact.app.experience.ClientComponentSlot
import com.cordiact.app.sandbox.AndroidPackageScriptRuntime
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.jobs.JobState
import com.cordiact.core.plugin.ExtensionPackageChange
import com.cordiact.core.plugin.ExtensionPackageInstaller
import com.cordiact.core.plugin.PackageTurnRejected
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import com.cordiact.core.plugin.WorkspacePackageRuntime
import com.cordiact.core.security.EffectiveSecurityProfile
import com.cordiact.core.security.Principal
import com.cordiact.core.security.SecurityContext
import com.cordiact.core.tool.ToolActivationContext
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolManagerException
import com.cordiact.core.tool.ToolPluginStateLookup
import com.cordiact.core.tool.ToolValue
import com.cordiact.interaction.InteractionContext
import com.cordiact.interaction.WorkspaceClientContributions.SessionActionOffer
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspacePluginAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun installedPackagePublishesExecutableAndClientContributionsAndWithdrawsThemTogether() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "workspace-plugin-test").toFile()
        val packageContext = object : ContextWrapper(context) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = File(root, "no_backup")
        }
        try {
            AndroidConversationSessionStore(context, File(root, "conversations.sqlite").absolutePath).use { sessionsStore ->
                AndroidExtensionPackageStore(packageContext).use { packagesStore ->
                    val catalog = BundledConversationPlugins.create(sessionsStore, File(root, "workspace"))
                    val packageService = WorkspaceExtensionPackages(
                        ExtensionPackageInstaller(packagesStore),
                        { id -> catalog.workspaceManager.findSessionWorkspace(id)?.let { EffectiveSecurityProfile.fullAccess(id) } },
                        runtime = catalog.workspacePackageRuntime(AndroidPackageScriptRuntime(context)),
                    )
                    val active = mutableSetOf<String>()
                    val plugins = catalog.create(stateLookup = object : ToolPluginStateLookup {
                        override fun state(pluginId: String) = PluginState.ENABLED.takeIf { pluginId in active }
                        override fun scopedState(pluginId: String, workspaceId: String, sessionId: String?) =
                            if (pluginId == OWNER) packageService.scopedOwnerState(pluginId, workspaceId, sessionId) else state(pluginId)
                    })
                    try {
                        plugins.forEach { plugin -> plugin.onRegister(); plugin.onEnable(); active += plugin.spec.id }
                        val foreground = Any()
                        packageService.resume(foreground)
                        try {
                            verifyPackage(catalog, plugins.filterIsInstance<SessionManagerService>().single(), packageService)
                        } finally {
                            packageService.pause(foreground)
                        }
                    } finally {
                        plugins.asReversed().forEach { it.onDisable() }
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun verifyPackage(
        catalog: BundledConversationPlugins, sessions: SessionManagerService, packages: WorkspaceExtensionPackages,
    ) {
        val workspace = catalog.workspaceManager.create("Plugin instrumentation")
        val other = catalog.workspaceManager.create("Independent workspace")
        val session = sessions.createSession(workspace.id, "Plugin session")
        val otherSession = sessions.createSession(other.id, "Other session")
        val selected = mutableStateOf(InteractionContext(session.id, workspace.id))
        val contributions = catalog.workspaceClientContributions
        val offers = mutableListOf<SessionActionOffer>()
        val inserted = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column {
                    val owner = selected.value
                    catalog.clientComponentSlots.Mount(ClientComponentSlot.COMPOSER_ACCESSORIES, owner.workspaceId, owner.sessionId, false,
                        canInsertAction = true, onInsertAction = { offer, current, complete ->
                            if (current()) {
                                offers += offer
                                contributions.draftInsertion(offer, owner, "", false)?.let {
                                    inserted += it
                                    complete()
                                }
                            }
                        })
                }
            }
        }
        packages.openPage(workspace.id).use { page ->
            val installed = (page.approveInstall(page.preview(MANIFEST.byteInputStream())).commit() as ExtensionPackageChange.Applied).record
            compose.onNodeWithContentDescription(COMPONENT).assertDoesNotExist()
            val enabled = (page.approveEnable(installed, true).commit() as ExtensionPackageChange.Applied).record
            assertEquals(WorkspacePackageRuntime.Status.ACTIVE, page.status(enabled))

            val tools = catalog.toolManagerPlugin
            val registration = tools.resolveForTurn(ToolActivationContext(workspace.id, sessionId = session.id))
                .single { it.descriptor.id == TOOL }.registration!!
            val invocation = ToolInvocationContext(workspace.id, sessionId = session.id, toolRegistrations = mapOf(TOOL to registration),
                securityContext = SecurityContext(workspace.id, session.id, "run", "turn", "call", listOf(Principal("main")),
                    EffectiveSecurityProfile.fullAccess(workspace.id)))
            val arguments = ToolArguments(mapOf("text" to ToolValue.StringValue(" test ")))
            assertEquals(ToolValue.StringValue("TEST"), tools.invoke(invocation, TOOL, arguments).values["text"])
            assertThrows(PackageTurnRejected::class.java) { catalog.packageTurnPolicies.check(workspace.id, false, 1) }
            catalog.packageTurnPolicies.check(other.id, false, 1)

            val workflow = catalog.packageWorkflows.offers(workspace.id, OWNER).single()
            val job = catalog.packageWorkflows.start(workflow, session.id, "  Mixed CASE  ") { true }
            compose.waitUntil(timeoutMillis = 15_000) {
                catalog.jobRegistry.get(job.id)?.state in setOf(JobState.SUCCEEDED, JobState.FAILED, JobState.KILLED, JobState.ABORTED)
            }
            val output = requireNotNull(catalog.jobRegistry.read(job.id))
            assertEquals(JobState.SUCCEEDED, output.snapshot.state)
            assertEquals("mixed case", output.text)

            compose.onNodeWithContentDescription(COMPONENT).assertIsDisplayed().performClick()
            compose.onNodeWithText("Review group").performClick()
            compose.onNodeWithText("Add review command").performClick()
            compose.runOnIdle { assertEquals(listOf("/fixture-review"), inserted.toList()) }
            compose.runOnIdle { selected.value = InteractionContext(otherSession.id, other.id) }
            compose.onNodeWithContentDescription(COMPONENT).assertDoesNotExist()
            compose.runOnIdle { selected.value = InteractionContext(session.id, workspace.id) }
            compose.onNodeWithContentDescription(COMPONENT).assertIsDisplayed()

            val disabled = (page.approveEnable(enabled, false).commit() as ExtensionPackageChange.Applied).record
            compose.onNodeWithContentDescription(COMPONENT).assertDoesNotExist()
            assertNull(contributions.draftInsertion(offers.single(), selected.value, "", false))
            assertThrows(ToolManagerException::class.java) { tools.invoke(invocation, TOOL, arguments) }
            assertThrows(IllegalStateException::class.java) { catalog.packageWorkflows.start(workflow, session.id, "old") { true } }
            catalog.packageTurnPolicies.check(workspace.id, false, 1)
            assertTrue(page.approveRemoval(disabled).commit() is ExtensionPackageChange.Removed)
        }
    }

    private companion object {
        const val COMPONENT = "Package review"
        const val OWNER = "pkg.device-review"
        const val TOOL = "extension.pkg.device-review.uppercase-text"
        val MANIFEST = """
            {
              "schemaVersion":14,"id":"device-review","version":"1.0.0","title":"Device review","description":"Local validation fixture",
              "host":{"api":1,"minVersion":"0.1.0"},
              "capabilities":["tool.publish","tool.script.execute","command.publish","ui.session-action.publish","ui.components.publish",
                "agent.turn-policy.publish","workflow.publish"],
              "tools":[{"name":"uppercase-text","description":"Uppercase","kind":"text_script","source":"s => s.trim().toUpperCase()"}],
              "commands":[{"name":"fixture-review","aliases":[],"description":"Review","text":"Check the fixture."}],
              "agent":{"beforeTurn":{"name":"require-text","source":"s => JSON.parse(s).hasText ? 'continue' : 'block'"}},
              "workflows":[{"name":"normalize-text","title":"Normalize","steps":[
                {"title":"Trim","source":"s => s.trim()"},{"title":"Lowercase","source":"s => s.toLowerCase()"}]}],
              "client":{"sessionActions":[{"id":"insert-review","label":"Add review command","command":"fixture-review"}],
                "components":[{"id":"review-menu","slot":"composer.accessories","label":"Package review","nodes":[
                  {"label":"Review group","children":[{"action":"insert-review"}]}]}]}
            }
        """.trimIndent()
    }
}
