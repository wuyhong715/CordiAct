package com.cordiact.app.experience.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cordiact.app.experience.ExperienceTheme
import com.cordiact.core.plugin.ExtensionPackageArtifact
import com.cordiact.core.plugin.ExtensionPackageChange
import com.cordiact.core.plugin.ExtensionPackageSourceReceipt
import com.cordiact.core.plugin.PluginDiagnosticSnapshot
import com.cordiact.core.plugin.SignedCatalogDiagnosticState
import com.cordiact.core.plugin.SignedReleaseDiagnostic
import com.cordiact.core.plugin.WorkspacePackageDiagnostic
import com.cordiact.core.plugin.WorkspacePackageDiagnostics
import com.cordiact.core.plugin.ExtensionPackageInstaller
import com.cordiact.core.plugin.InMemoryExtensionPackageStore
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.plugin.PluginToolContributions
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import com.cordiact.core.plugin.WorkspacePackageRuntime
import com.cordiact.core.security.EffectiveSecurityProfile
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.interaction.DeclarativePackagePlugin
import com.cordiact.interaction.InteractionCommandRegistry
import com.cordiact.interaction.PluginCommandContributions
import com.cordiact.interaction.WorkspaceClientContributions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h892dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspacePackageSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun descriptionTracksWithdrawalAndCannotFollowSameOwnerAcrossScopes() {
        val fixture = Fixture()
        val alpha = fixture.packages.openPage("a")
        val bravo = fixture.packages.openPage("b")
        val installedA = fixture.install(alpha, title = "工作区插件包", description = "Alpha help")
        val enabledB = bravo.approveEnable(fixture.install(bravo, title = "Bravo page", description = "Bravo help"), true).commit()
        check(enabledB is ExtensionPackageChange.Applied)
        val vm = SettingsViewModel(fixture.facade)
        compose.setContent { ExperienceTheme { SettingsHost(vm) { error("Unexpected exit from Settings") } } }
        compose.onNode(hasText("插件与连接") and hasClickAction()).performClick()
        builtIn().assertIsDisplayed()
        compose.onNodeWithText("第三方插件说明").assertDoesNotExist()
        compose.onNodeWithText("Bravo page").assertDoesNotExist()

        val enabledA = fixture.enable(alpha, installedA)
        compose.onNode(hasText("pkg.notes") and hasClickAction()).performClick()
        compose.onNodeWithText("Alpha help").assertIsDisplayed()
        compose.onNodeWithText("settings://plugins/packages").assertHasNoClickAction()
        compose.onNodeWithText("/not-a-command").assertHasNoClickAction()
        compose.runOnIdle {
            // A conflated observer can skip the withdrawn snapshot; same owner must still close the old detail.
            val disabledA = (alpha.approveEnable(enabledA, false).commit() as ExtensionPackageChange.Applied).record
            fixture.enable(alpha, disabledA)
        }
        compose.onNodeWithText("Alpha help").assertDoesNotExist()
        compose.onNodeWithText("插件说明").assertDoesNotExist()
        builtIn().assertIsDisplayed()

        compose.onNode(hasText("pkg.notes") and hasClickAction()).performClick()
        compose.onNode(hasText("作用于：工作区甲 ▾") and hasClickAction()).performClick()
        compose.onNode(hasText("工作区乙") and hasClickAction()).performClick()
        compose.onNodeWithText("Alpha help").assertDoesNotExist()
        compose.onNodeWithText("Bravo help").assertDoesNotExist()
        builtIn().assertIsDisplayed()
        compose.onNode(hasText("Bravo page") and hasClickAction()).performClick()
        compose.onNodeWithText("Bravo help").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("b"), fixture.facade.selections)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        builtIn().assertIsDisplayed()
        compose.onNode(hasText("Bravo page") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        builtIn().assertIsDisplayed()
    }

    @Test fun diagnosticsIsReadOnlyAndDropsLateResultsFromThePreviouslySelectedWorkspace() {
        val fixture = Fixture()
        val lateA = kotlinx.coroutines.CompletableDeferred<com.cordiact.core.plugin.PluginDiagnosticSnapshot?>()
        fixture.facade.diagnosticRead = { workspaceId ->
            if (workspaceId == "a") {
                // Deliberately ignore cancellation like a blocking SQLite read returning late.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { lateA.await() }
            } else com.cordiact.core.plugin.PluginDiagnosticSnapshot("b",
                com.cordiact.core.plugin.WorkspacePackageDiagnostics.Entries(emptyList()),
                listOf(com.cordiact.core.plugin.BundledPluginDiagnostic("core.bravo", "1.0.0", null)))
        }
        val vm = SettingsViewModel(fixture.facade)
        compose.setContent { ExperienceTheme { SettingsHost(vm) { error("Unexpected exit from Settings") } } }
        compose.onNode(hasText("插件与连接") and hasClickAction()).performClick()
        compose.onNode(hasText("插件诊断") and hasClickAction()).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { fixture.facade.diagnosticRequests.contains("a") }
        compose.onNode(hasText("作用于：工作区甲 ▾") and hasClickAction()).performClick()
        compose.onNode(hasText("工作区乙") and hasClickAction()).performClick()
        compose.onNodeWithText("此工作区没有已安装插件包。").assertExists()
        compose.onNodeWithText("core.bravo").assertHasNoClickAction()
        compose.runOnIdle {
            lateA.complete(com.cordiact.core.plugin.PluginDiagnosticSnapshot("a",
                com.cordiact.core.plugin.WorkspacePackageDiagnostics.StorageNotCreated,
                listOf(com.cordiact.core.plugin.BundledPluginDiagnostic("core.alpha", "1.0.0", null))))
        }
        compose.onNodeWithText("core.alpha").assertDoesNotExist()
        compose.onNodeWithText("core.bravo").assertHasNoClickAction()
        compose.onNodeWithText("本页不读取会话试用或 MCP。").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        builtIn().assertIsDisplayed()
    }

    @Test fun signedDiagnosticsSeparatesHistoricalReceiptFromObservedRelease() {
        val fixture = Fixture()
        val receipt = ExtensionPackageSourceReceipt("alpha", "publisher-a", "key-a", 3, 5)
        fixture.facade.diagnosticRead = { workspaceId ->
            PluginDiagnosticSnapshot(workspaceId, WorkspacePackageDiagnostics.Entries(listOf(
                WorkspacePackageDiagnostic("alpha-notes", "1.0.0", ExtensionPackageArtifact.Format.DECLARATIVE,
                    enableIntent = true, runtime = WorkspacePackageRuntime.Status.TRUST_WITHDRAWN,
                    approvedContributions = emptyList(), hasRollback = false, sourceReceipt = receipt,
                    signedRelease = SignedReleaseDiagnostic.RELEASE_MATCHES),
            )), emptyList(), signedCatalog = SignedCatalogDiagnosticState.OBSERVED, observedRootRevision = 7)
        }
        compose.setContent {
            ExperienceTheme { Column { PluginDiagnosticsPage(fixture.facade, SecurityWorkspace("a", "工作区甲"), scopeBusy = false) } }
        }
        compose.onNodeWithText("签名目录观测：有已验签的根清单快照").assertHasNoClickAction()
        compose.onNodeWithText("已观测根代数：7").assertHasNoClickAction()
        compose.onNodeWithText("安装来源（历史）：alpha · publisher-a").assertHasNoClickAction()
        compose.onNodeWithText("已安装版本的目录观测：版本和摘要与快照匹配").assertHasNoClickAction()
        compose.onNodeWithText("进程最近状态：签名依据已失效，已撤回").assertHasNoClickAction()
        compose.runOnIdle { assertEquals(listOf("a"), fixture.facade.diagnosticRequests.toList()) }
    }

    private fun builtIn() = compose.onNode(hasText("导入、启停和管理已安装的声明包") and hasClickAction())

    private class Fixture {
        private val commands = InteractionCommandRegistry()
        private val contributions = WorkspaceClientContributions(commands)
        private val kernel = PluginKernel()
        private val tools = PluginToolContributions(InMemoryToolDefinitionRegistry())
        private val runtime = WorkspacePackageRuntime(admissionChanged = contributions::refreshAdmission) { record, admitted ->
            DeclarativePackagePlugin(record.manifest, kernel, tools, PluginCommandContributions(commands), record.grantedCapabilities,
                workspaceId = record.workspaceId, clientContributions = contributions, admitted = admitted)
        }
        val packages = WorkspaceExtensionPackages(ExtensionPackageInstaller(InMemoryExtensionPackageStore()),
            { workspaceId -> EffectiveSecurityProfile.fullAccess(workspaceId) }, runtime = runtime)
        val facade = Facade(contributions)
        init { packages.resume(Any()) }

        fun install(page: WorkspaceExtensionPackages.Page, title: String, description: String): com.cordiact.core.plugin.ExtensionPackageRecord {
            val preview = page.preview("""
                {"schemaVersion":2,"id":"notes","version":"1.0.0","title":"Notes","description":"Local package",
                 "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["ui.settings.publish"],"commands":[],"tools":[],
                 "client":{"settingsPage":{"title":"$title","description":"$description","rows":[
                   {"label":"Route","value":"settings://plugins/packages"},{"label":"Command","value":"/not-a-command"}]}}}
            """.trimIndent().byteInputStream())
            return (page.approveInstall(preview).commit() as ExtensionPackageChange.Applied).record
        }

        fun enable(page: WorkspaceExtensionPackages.Page, record: com.cordiact.core.plugin.ExtensionPackageRecord) =
            (page.approveEnable(record, true).commit() as ExtensionPackageChange.Applied).record
    }

    private class Facade(contributions: WorkspaceClientContributions) : SettingsFacade {
        override val workspaceClientContributions = contributions.snapshot
        val selections = mutableListOf<String>()
        val diagnosticRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
        var diagnosticRead: suspend (String) -> com.cordiact.core.plugin.PluginDiagnosticSnapshot? = { null }
        override suspend fun pluginDiagnostics(workspaceId: String): com.cordiact.core.plugin.PluginDiagnosticSnapshot? {
            diagnosticRequests += workspaceId
            return diagnosticRead(workspaceId)
        }
        private var selected = "a"
        override suspend fun selectWorkspace(id: String) { selections += id; selected = id }
        override suspend fun snapshot() = SettingsSnapshot(capabilityWorkspaceId = selected,
            workspaces = listOf(SecurityWorkspace("a", "工作区甲"), SecurityWorkspace("b", "工作区乙")))
        override suspend fun discover(draft: ProviderDraft, key: String): List<String> = error("Only reading Settings")
        override suspend fun commit(draft: ProviderDraft, key: String, discovered: List<String>, selected: Set<String>) = error("Only reading Settings")
        override suspend fun refreshProvider(id: String): ModelsDiff = error("Only reading Settings")
        override suspend fun setEnabledModels(id: String, selected: Set<String>) = error("Only reading Settings")
        override suspend fun updateProvider(id: String, name: String, endpoint: String) = error("Only reading Settings")
        override suspend fun setKey(id: String, key: String?) = error("Only reading Settings")
        override suspend fun removeProvider(id: String) = error("Only reading Settings")
        override suspend fun setModelDefaults(id: String, values: Map<String, com.cordiact.core.conversation.GenerationParameters>) = error("Only reading Settings")
        override suspend fun setRole(role: SettingsModelRole, selection: ModelRoleSelection) = error("Only reading Settings")
    }
}
