package com.cordiact.app.conversation

import android.content.Context
import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.conversation.modelAdapterPluginSpec
import com.cordiact.core.conversation.sessionManagerPluginSpec
import com.cordiact.core.plugin.BundledPluginVerifier
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.sandbox.SandboxManagerPlugin
import com.cordiact.core.sandbox.sandboxManagerPluginSpec
import com.cordiact.core.workspace.WorkspaceManagerPlugin
import com.cordiact.core.workspace.WorkspaceManagerService
import com.cordiact.core.workspace.workspaceManagerPluginSpec
import java.io.File

/** Host-owned allowlist for protected built-in plugins. */
object BundledConversationPluginCatalog {
    private val trustedSpecs = setOf(
        workspaceManagerPluginSpec(),
        modelAdapterPluginSpec(),
        sessionManagerPluginSpec(),
        sandboxManagerPluginSpec(),
    )

    val verifier = BundledPluginVerifier { candidate -> candidate in trustedSpecs }

    fun createSandboxManager(appUid: Int): SandboxManagerPlugin = SandboxManagerPlugin(
        appUid = appUid,
        bundledVerifier = com.cordiact.core.sandbox.BundledPluginVerifier { false },
        capabilityPolicy = com.cordiact.core.sandbox.SandboxCapabilityPolicy { emptySet() },
        sessionRevoker = com.cordiact.core.sandbox.SandboxSessionRevoker {},
    )
}

/** Creates one shared Workspace/session database and injects the same Workspace service into Session Manager. */
class BundledConversationPlugins private constructor(
    val conversationStore: AndroidConversationSessionStore,
    val workspacePlugin: WorkspaceManagerPlugin,
) {
    val workspaceManager: WorkspaceManagerService get() = workspacePlugin

    fun create(): List<ManagedPlugin> = listOf(
        workspacePlugin,
        ModelAdapterPlugin(),
        SessionManagerPlugin(conversationStore, workspaceManager),
    )

    companion object {
        fun create(context: Context): BundledConversationPlugins {
            val store = AndroidConversationSessionStore(context)
            val privateRoot = File(context.noBackupFilesDir, "cordiact")
            return create(store, privateRoot)
        }

        fun create(store: AndroidConversationSessionStore, privateRoot: File): BundledConversationPlugins {
            val workspace = WorkspaceManagerPlugin(store, privateRoot)
            return BundledConversationPlugins(store, workspace)
        }
    }
}
