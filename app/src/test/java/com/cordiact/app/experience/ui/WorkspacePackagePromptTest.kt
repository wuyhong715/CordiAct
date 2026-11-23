package com.cordiact.app.experience.ui

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cordiact.app.experience.ExperienceTheme
import com.cordiact.app.experience.PackagePrompt
import com.cordiact.app.experience.WorkspacePackagePrompt
import com.cordiact.core.plugin.ExtensionPackageInstaller
import com.cordiact.core.plugin.InMemoryExtensionPackageStore
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import com.cordiact.core.security.EffectiveSecurityProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h892dp-420dpi")
class WorkspacePackagePromptTest {
    @get:Rule val compose = createComposeRule()
    private var approvals = 0
    private var dismissed = 0

    @Test fun previewShowsCapabilitiesAndRequiresInstallClick() {
        show(readOnly = false)
        compose.onNodeWithText("请求能力：提供模型工具").assertExists()
        assertEquals(0, approvals)
        compose.onNodeWithText("确认安装").performClick()
        assertEquals(1, approvals)
    }

    @Test fun readOnlyAllowsReadingPreviewButNotApproving() {
        show(readOnly = true)
        compose.onNodeWithText("预览待安装版本").assertExists()
        compose.onNodeWithText("确认安装").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, approvals)
    }

    @Test fun savedTrialPreviewShowsToolIdentityChangeBeforeAnyApproval() {
        val installer = ExtensionPackageInstaller(InMemoryExtensionPackageStore())
        val service = WorkspaceExtensionPackages(installer, { workspace -> EffectiveSecurityProfile.fullAccess(workspace) })
        val page = service.openPage("ws")
        val snapshot = installer.inspectLegacy("notes", """
            {"schemaVersion":1,"id":"notes","title":"Notes",
             "tools":[{"name":"show","description":"Show note","text":"literal"}]}
        """.trimIndent().toByteArray())
        val prompt = PackagePrompt.Import(page.previewSavedTrial { snapshot }) { snapshot }
        compose.setContent { ExperienceTheme { WorkspacePackagePrompt(prompt, { dismissed++ }, { approvals++ }) } }
        compose.onNodeWithText("预览试用转包").assertExists()
        compose.onNodeWithText("extension.authored.notes.show → extension.pkg.notes.show").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, approvals)
    }

    private fun show(readOnly: Boolean) {
        val service = WorkspaceExtensionPackages(ExtensionPackageInstaller(InMemoryExtensionPackageStore()), { workspace ->
            if (readOnly) EffectiveSecurityProfile.readOnly(workspace) else EffectiveSecurityProfile.fullAccess(workspace)
        })
        val prompt = PackagePrompt.Import(service.openPage("ws").preview("""
            {"schemaVersion":1,"id":"notes","version":"1.0.0","title":"Notes","description":"Notes",
             "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["tool.publish"],"commands":[],
             "tools":[{"name":"show","description":"Show note","kind":"static_text","text":"literal"}]}
        """.trimIndent().byteInputStream()))
        compose.setContent {
            ExperienceTheme { WorkspacePackagePrompt(prompt, { dismissed++ }, { approvals++ }) }
        }
    }
}
