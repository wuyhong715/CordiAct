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
import com.cordiact.core.file.FileManagerPlugin
import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.file.fileManagerPluginSpec
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolManagerPlugin
import com.cordiact.core.tool.ToolManagerService
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolPluginStateLookup
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType
import com.cordiact.core.tool.ToolExecutor
import com.cordiact.core.tool.toolManagerPluginSpec
import com.cordiact.app.file.AndroidWorkspaceFileBackend
import com.cordiact.app.file.registerWorkspaceFileTools
import java.io.File

/** Host-owned allowlist for protected built-in plugins. */
object BundledConversationPluginCatalog {
    private val trustedSpecs = setOf(
        workspaceManagerPluginSpec(),
        toolManagerPluginSpec(),
        fileManagerPluginSpec(),
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
    val fileManagerPlugin: FileManagerPlugin,
    private val registry: InMemoryToolDefinitionRegistry,
) {
    val workspaceManager: WorkspaceManagerService get() = workspacePlugin
    val fileManager: WorkspaceFileService get() = fileManagerPlugin
    lateinit var toolManagerPlugin: ToolManagerPlugin
        private set
    val toolManagerService: ToolManagerService get() = toolManagerPlugin

    fun create(stateLookup: ToolPluginStateLookup): List<ManagedPlugin> {
        registerWorkspaceFileTools(registry, fileManager)
        toolManagerPlugin = ToolManagerPlugin(
            registry = registry,
            preferences = conversationStore,
            workspaces = workspaceManager,
            pluginStates = stateLookup,
        )
        return listOf(
            workspacePlugin,
            fileManagerPlugin,
            toolManagerPlugin,
            ModelAdapterPlugin(),
            SessionManagerPlugin(conversationStore, workspaceManager),
        )
    }

    companion object {
        fun create(context: Context): BundledConversationPlugins {
            val store = AndroidConversationSessionStore(context)
            val privateRoot = File(context.noBackupFilesDir, "cordiact")
            return create(store, privateRoot)
        }

        fun create(store: AndroidConversationSessionStore, privateRoot: File): BundledConversationPlugins {
            val workspace = WorkspaceManagerPlugin(store, privateRoot)
            val fileManager = FileManagerPlugin(workspace, AndroidWorkspaceFileBackend())
            return BundledConversationPlugins(store, workspace, fileManager, InMemoryToolDefinitionRegistry())
        }
    }
}
