package com.cordiact.app.conversation

import android.content.Context
import com.cordiact.app.model.AndroidDeepSeekApiKeyStore
import com.cordiact.app.model.AndroidModelProviderConfigStore
import com.cordiact.app.model.AndroidProviderApiKeyStore
import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.conversation.modelAdapterPluginSpec
import com.cordiact.model.deepseek.ApiKeyProvider
import com.cordiact.model.deepseek.DeepSeekBackend
import com.cordiact.model.deepseek.DeepSeekBackendPlugin
import com.cordiact.model.deepseek.deepSeekBackendPluginSpec
import com.cordiact.model.deepseek.ModelProviderConfigStore
import com.cordiact.model.deepseek.InMemoryModelProviderConfigStore
import com.cordiact.model.deepseek.ModelProviderRegistryPlugin
import com.cordiact.model.deepseek.modelProviderRegistryPluginSpec
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
import com.cordiact.app.interaction.AndroidFillValueStore
import com.cordiact.app.interaction.AndroidInteractionDirectory
import com.cordiact.app.interaction.WorkspaceInteractionAuthorization
import com.cordiact.integration.interaction.CommandDescriptor
import com.cordiact.integration.interaction.CommandExecutionResult
import com.cordiact.integration.interaction.InteractionCommandExecutor
import com.cordiact.integration.interaction.InteractionCommandRegistry
import com.cordiact.integration.interaction.InteractionIntegrationPlugin
import com.cordiact.integration.interaction.InteractionIntegrationService
import com.cordiact.integration.interaction.INTERACTION_INTEGRATION_PLUGIN_ID
import com.cordiact.integration.interaction.MutableFillValueStore
import com.cordiact.integration.interaction.InMemoryFillValueStore
import com.cordiact.integration.interaction.MentionType
import com.cordiact.integration.interaction.interactionIntegrationPluginSpec
import java.io.File

/** Host-owned allowlist for protected built-in plugins. */
object BundledConversationPluginCatalog {
    private val trustedSpecs = setOf(
        workspaceManagerPluginSpec(),
        toolManagerPluginSpec(),
        fileManagerPluginSpec(),
        modelAdapterPluginSpec(),
        sessionManagerPluginSpec(),
        deepSeekBackendPluginSpec(),
        modelProviderRegistryPluginSpec(),
        sandboxManagerPluginSpec(),
        interactionIntegrationPluginSpec(),
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
    val apiKeyProvider: ApiKeyProvider,
    private val providerConfigStore: ModelProviderConfigStore,
    private val providerCredentialFor: (String) -> ApiKeyProvider,
    private val forgetProviderCredential: (String) -> Unit,
    private val fillValues: MutableFillValueStore,
) {
    val workspaceManager: WorkspaceManagerService get() = workspacePlugin
    val fillValueStore: MutableFillValueStore get() = fillValues
    lateinit var interactionPlugin: InteractionIntegrationPlugin
        private set
    lateinit var interactionDirectory: AndroidInteractionDirectory
        private set
    val interactionService: InteractionIntegrationService get() = interactionPlugin.service
    val fileManager: WorkspaceFileService get() = fileManagerPlugin
    lateinit var toolManagerPlugin: ToolManagerPlugin
        private set
    val toolManagerService: ToolManagerService get() = toolManagerPlugin
    lateinit var modelProviderRegistryPlugin: ModelProviderRegistryPlugin
        private set

    fun create(stateLookup: ToolPluginStateLookup): List<ManagedPlugin> {
        registerWorkspaceFileTools(registry, fileManager)
        toolManagerPlugin = ToolManagerPlugin(
            registry = registry,
            preferences = conversationStore,
            workspaces = workspaceManager,
            pluginStates = stateLookup,
        )
        val modelAdapter = ModelAdapterPlugin()
        modelProviderRegistryPlugin = ModelProviderRegistryPlugin(
            modelAdapter,
            providerCredentialFor,
            providerConfigStore,
            forgetProviderCredential,
        )
        val commandRegistry = InteractionCommandRegistry()
        val sessionManager = SessionManagerPlugin(conversationStore, workspaceManager)
        interactionDirectory = AndroidInteractionDirectory(workspaceManager, fileManager, sessionManager)
        val interactionAuthorization = WorkspaceInteractionAuthorization(
            sessions = sessionManager,
            workspaces = workspaceManager,
            directory = interactionDirectory,
            commands = commandRegistry,
        )
        val interactionService = InteractionIntegrationService(
            commands = commandRegistry,
            mentions = interactionDirectory,
            fills = fillValues,
            authorization = interactionAuthorization,
        )
        interactionPlugin = InteractionIntegrationPlugin(interactionService, commandRegistry)
        commandRegistry.register(
            CommandDescriptor(
                id = "core.interaction.target",
                name = "目标",
                ownerPluginId = INTERACTION_INTEGRATION_PLUGIN_ID,
                allowedMentionTypes = MentionType.entries.toSet(),
                requiredCapabilities = setOf("interaction.execute"),
                requiresMention = true,
                executor = InteractionCommandExecutor { envelope, modelId, submitter, onEvent ->
                    CommandExecutionResult(
                        completed = false,
                        modelCall = submitter.submit(envelope.copy(submittedText = envelope.commandInput), modelId, onEvent),
                    )
                },
            ),
        )
        return listOf(
            workspacePlugin,
            fileManagerPlugin,
            toolManagerPlugin,
            modelAdapter,
            sessionManager,
            DeepSeekBackendPlugin(modelAdapter, DeepSeekBackend(apiKeyProvider)),
            modelProviderRegistryPlugin,
            interactionPlugin,
        )
    }

    companion object {
        fun create(
            context: Context,
            apiKeyProvider: ApiKeyProvider = AndroidDeepSeekApiKeyStore(context),
        ): BundledConversationPlugins {
            val store = AndroidConversationSessionStore(context)
            val privateRoot = File(context.noBackupFilesDir, "cordiact")
            val providerKeys = AndroidProviderApiKeyStore(context)
            return create(
                store,
                privateRoot,
                apiKeyProvider,
                AndroidModelProviderConfigStore(context),
                { providerId -> if (providerId == "deepseek") apiKeyProvider else providerKeys.provider(providerId) },
                providerKeys::clear,
                AndroidFillValueStore(context),
            )
        }

        fun create(
            store: AndroidConversationSessionStore,
            privateRoot: File,
            apiKeyProvider: ApiKeyProvider = ApiKeyProvider { null },
            providerConfigStore: ModelProviderConfigStore = InMemoryModelProviderConfigStore(),
            providerCredentialFor: ((String) -> ApiKeyProvider)? = null,
            forgetProviderCredential: (String) -> Unit = {},
            fillValues: MutableFillValueStore = InMemoryFillValueStore(),
        ): BundledConversationPlugins {
            val workspace = WorkspaceManagerPlugin(store, privateRoot)
            val fileManager = FileManagerPlugin(workspace, AndroidWorkspaceFileBackend())
            return BundledConversationPlugins(
                store,
                workspace,
                fileManager,
                InMemoryToolDefinitionRegistry(),
                apiKeyProvider,
                providerConfigStore,
                providerCredentialFor ?: { providerId ->
                    if (providerId == "deepseek") apiKeyProvider else ApiKeyProvider { null }
                },
                forgetProviderCredential,
                fillValues,
            )
        }
    }
}
