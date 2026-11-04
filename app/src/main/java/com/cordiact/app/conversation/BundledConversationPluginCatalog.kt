package com.cordiact.app.conversation

import android.content.Context
import com.cordiact.app.skill.AndroidSkillFileSource
import com.cordiact.app.skill.registerSkillTools
import com.cordiact.core.skill.SkillPlugin
import com.cordiact.core.skill.skillPluginSpec
import com.cordiact.app.model.AndroidDeepSeekApiKeyStore
import com.cordiact.app.model.AndroidModelProviderConfigStore
import com.cordiact.app.model.AndroidProviderApiKeyStore
import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.conversation.modelAdapterPluginSpec
import com.cordiact.model.providers.ApiKeyProvider
import com.cordiact.model.providers.ConfigurableModelBackend
import com.cordiact.model.providers.DeepSeekBackendPlugin
import com.cordiact.model.providers.DeepSeekPreset
import com.cordiact.model.providers.deepSeekBackendPluginSpec
import com.cordiact.model.providers.ModelProviderConfigStore
import com.cordiact.model.providers.InMemoryModelProviderConfigStore
import com.cordiact.model.providers.ModelProviderRegistryPlugin
import com.cordiact.model.providers.modelProviderRegistryPluginSpec
import com.cordiact.core.conversation.sessionManagerPluginSpec
import com.cordiact.core.plugin.BundledPluginVerifier
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginManagerService
import com.cordiact.core.sandbox.SandboxManagerPlugin
import com.cordiact.core.sandbox.sandboxManagerPluginSpec
import com.cordiact.core.workspace.WorkspaceManagerPlugin
import com.cordiact.core.workspace.WorkspaceManagerService
import com.cordiact.core.workspace.workspaceManagerPluginSpec
import com.cordiact.core.subagent.subagentPluginSpec
import com.cordiact.core.subagent.orchestrationPluginSpec
import com.cordiact.core.file.FileManagerPlugin
import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.file.fileManagerPluginSpec
import com.cordiact.core.plugin.PluginState
import com.cordiact.app.plugin.registerPluginManagerTools
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolDefinitionRegistry
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
import com.cordiact.app.search.AndroidSearchApiKeyStore
import com.cordiact.app.search.AndroidSearchPolicyStore
import com.cordiact.app.search.AndroidSearchProviderSettings
import com.cordiact.app.search.AndroidSearchUsageStore
import com.cordiact.app.search.SearchPlugin
import com.cordiact.app.search.registerSearchTools
import com.cordiact.app.search.searchPluginSpec
import com.cordiact.app.search.searchRoute
import com.cordiact.core.search.FETCH_TOOL_ID
import com.cordiact.core.search.SEARCH_TOOL_ID
import com.cordiact.core.search.SearchProviderRegistry
import com.cordiact.core.search.SearchService
import com.cordiact.search.providers.DeepSeekFetchApiProvider
import com.cordiact.search.providers.DeepSeekNativeSearchProvider
import com.cordiact.search.providers.DeepSeekSearchApiProvider
import com.cordiact.search.providers.DeepSeekWebConfig
import com.cordiact.search.providers.DefaultSearchService
import com.cordiact.search.providers.AnthropicNativeSearchProvider
import com.cordiact.search.providers.GeminiNativeSearchProvider
import com.cordiact.search.providers.OpenAiNativeSearchProvider
import com.cordiact.search.providers.BochaConfig
import com.cordiact.search.providers.BochaSearchProvider
import com.cordiact.search.providers.BraveConfig
import com.cordiact.search.providers.BraveSearchProvider
import com.cordiact.search.providers.ExaConfig
import com.cordiact.search.providers.ExaSearchProvider
import com.cordiact.search.providers.SearxngConfig
import com.cordiact.search.providers.SearxngSearchProvider
import com.cordiact.search.providers.TavilyConfig
import com.cordiact.search.providers.TavilySearchProvider
import com.cordiact.search.providers.LocalFetchConfig
import com.cordiact.search.providers.LocalFetchProvider
import com.cordiact.search.providers.NativeSearchOptions
import com.cordiact.app.interaction.AndroidFillValueStore
import com.cordiact.app.interaction.AndroidInteractionDirectory
import com.cordiact.app.interaction.WorkspaceInteractionAuthorization
import com.cordiact.interaction.CommandDescriptor
import com.cordiact.interaction.CommandExecutionResult
import com.cordiact.interaction.InteractionCommandExecutor
import com.cordiact.interaction.InteractionCommandRegistry
import com.cordiact.interaction.InteractionPlugin
import com.cordiact.interaction.InteractionService
import com.cordiact.interaction.INTERACTION_PLUGIN_ID
import com.cordiact.interaction.MutableFillValueStore
import com.cordiact.interaction.InMemoryFillValueStore
import com.cordiact.interaction.MentionType
import com.cordiact.interaction.interactionPluginSpec
import java.io.File

/** Host-owned allowlist for protected built-in plugins. */
object BundledConversationPluginCatalog {
    /**
     * Capabilities this build grants to its own tool adapters.
     *
     * A tool that declares a capability is refused by the tool manager unless the gate admits it,
     * so the list is the only thing standing between a shipped adapter and an invisible tool.
     */
    private val BUNDLED_GRANTED_CAPABILITIES = setOf(
        com.cordiact.core.subagent.SUBAGENT_DELEGATE_CAPABILITY,
        com.cordiact.core.subagent.ORCHESTRATION_CAPABILITY,
    )

    private val trustedSpecs = setOf(
        workspaceManagerPluginSpec(),
        toolManagerPluginSpec(),
        fileManagerPluginSpec(),
        skillPluginSpec(),
        modelAdapterPluginSpec(),
        sessionManagerPluginSpec(),
        deepSeekBackendPluginSpec(),
        modelProviderRegistryPluginSpec(),
        sandboxManagerPluginSpec(),
        interactionPluginSpec(),
        searchPluginSpec(),
        subagentPluginSpec(),
        orchestrationPluginSpec(),
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
    private val configuredSearchProviderSettings: AndroidSearchProviderSettings? = null,
    private val configuredSearchApiKeys: AndroidSearchApiKeyStore? = null,
) {
    val workspaceManager: WorkspaceManagerService get() = workspacePlugin
    val fillValueStore: MutableFillValueStore get() = fillValues
    lateinit var interactionPlugin: InteractionPlugin
        private set
    lateinit var interactionDirectory: AndroidInteractionDirectory
        private set
    val interactionService: InteractionService get() = interactionPlugin.service
    val fileManager: WorkspaceFileService get() = fileManagerPlugin
    lateinit var skillPlugin: SkillPlugin
        private set
    lateinit var toolManagerPlugin: ToolManagerPlugin
        private set
    val toolManagerService: ToolManagerService get() = toolManagerPlugin
    lateinit var searchPlugin: SearchPlugin
        private set
    val searchService: SearchService get() = searchPlugin.service
    lateinit var searchPolicyStore: AndroidSearchPolicyStore
        private set
    lateinit var searchUsageStore: AndroidSearchUsageStore
        private set
    var searchRegistry: SearchProviderRegistry? = null
        private set

    /**
     * Set by the app host once the lifecycle manager exists.
     *
     * Null in unit tests and in any composition without a lifecycle manager, in which case the
     * plugin_manager tool is simply not offered.
     */
    var pluginManagerService: PluginManagerService? = null

    /** Persists an MCP auth header without putting it in the plugin config. */
    var mcpCredentialWriter: ((serverName: String, headerName: String, headerValue: String) -> Unit)? = null
    val searchApiKeyStore: AndroidSearchApiKeyStore? get() = configuredSearchApiKeys
    val searchProviderSettings: AndroidSearchProviderSettings? get() = configuredSearchProviderSettings
    lateinit var modelProviderRegistryPlugin: ModelProviderRegistryPlugin
        private set

    /**
     * The registry user-installed plugins publish into.
     *
     * Same instance the bundled tools use, so a model sees one flat tool list regardless of
     * where a tool came from.
     */
    val toolRegistry: ToolDefinitionRegistry get() = registry

    fun create(stateLookup: ToolPluginStateLookup): List<ManagedPlugin> {
        registerWorkspaceFileTools(registry, fileManager)
        skillPlugin = SkillPlugin(AndroidSkillFileSource(fileManager))
        registerSkillTools(registry, skillPlugin)
        toolManagerPlugin = ToolManagerPlugin(
            registry = registry,
            preferences = conversationStore,
            workspaces = workspaceManager,
            pluginStates = stateLookup,
            // The default gate admits only tools that require nothing, so a declared capability
            // would make the tool permanently undispatable. Delegation is a host-shipped
            // capability: this build carries the runtime that enforces depth, Workspace equality
            // and the grant copy, so it is granted here rather than through a user consent flow.
            capabilities = com.cordiact.core.tool.ToolCapabilityGate { _, required ->
                required.all { it in BUNDLED_GRANTED_CAPABILITIES }
            },
        )

        val searchPolicies = AndroidSearchPolicyStore(conversationStore)
        val searchUsage = AndroidSearchUsageStore(conversationStore)
        val providerSettings = configuredSearchProviderSettings
        val searchKeys = configuredSearchApiKeys
        fun key(providerId: String): () -> String? = {
            when (providerId) {
                // The bundled DeepSeek route reuses the model key so one credential covers both.
                "deepseek" -> apiKeyProvider.apiKey()
                else -> searchKeys?.get(providerId)
            }
        }
        val deepSeekWeb = DeepSeekWebConfig(
            baseUrl = providerSettings?.baseUrl("deepseek") ?: "https://api.deepseek.com/v1/tools",
            apiKey = key("deepseek"),
        )
        val tavilyConfig = TavilyConfig(
            baseUrl = providerSettings?.baseUrl("tavily") ?: "https://api.tavily.com",
            apiKey = key("tavily"),
            searchDepth = providerSettings?.searchDepth() ?: "basic",
            includeAnswer = providerSettings?.includeAnswer() ?: true,
        )
        val braveConfig = BraveConfig(
            baseUrl = providerSettings?.baseUrl("brave") ?: "https://api.search.brave.com/res/v1/web/search",
            apiKey = key("brave"),
            country = providerSettings?.braveCountry(),
        )
        val exaConfig = ExaConfig(
            baseUrl = providerSettings?.baseUrl("exa") ?: "https://api.exa.ai",
            apiKey = key("exa"),
            type = providerSettings?.exaType() ?: "auto",
            includeContents = providerSettings?.exaContents() ?: true,
        )
        val bochaConfig = BochaConfig(
            baseUrl = providerSettings?.baseUrl("bocha") ?: "https://api.bochaai.com/v1/web-search",
            apiKey = key("bocha"),
            freshness = providerSettings?.bochaFreshness() ?: "noLimit",
        )
        val searxngConfig = SearxngConfig(
            baseUrl = providerSettings?.baseUrl("searxng") ?: "",
            engines = providerSettings?.searxngEngines(),
        )
        // Read once per call so a settings change applies without re-registering providers.
        val nativeOptions: () -> NativeSearchOptions = { providerSettings?.nativeOptions() ?: NativeSearchOptions.DEFAULT }
        val searchProviders = SearchProviderRegistry().apply {
            registerSearch(DeepSeekSearchApiProvider(deepSeekWeb))
            registerFetch(DeepSeekFetchApiProvider(deepSeekWeb))
            registerSearch(
                DeepSeekNativeSearchProvider(
                    apiKey = key("deepseek"),
                    // The route's model id wins; this fallback is only for a pinned route-less call.
                    model = { null as String? },
                    options = nativeOptions,
                ),
            )
            // Native providers for the other routes: same auxiliary-call shape, different protocol.
            registerSearch(AnthropicNativeSearchProvider(apiKey = key("anthropic-native"), options = nativeOptions))
            registerSearch(OpenAiNativeSearchProvider(apiKey = key("openai-native"), options = nativeOptions))
            registerSearch(GeminiNativeSearchProvider(apiKey = key("gemini-native"), options = nativeOptions))
            registerSearch(TavilySearchProvider(tavilyConfig))
            registerSearch(BraveSearchProvider(braveConfig))
            registerSearch(ExaSearchProvider(exaConfig))
            registerSearch(BochaSearchProvider(bochaConfig))
            registerSearch(SearxngSearchProvider(searxngConfig))
            // Off unless enabled: a device-side fetch is an opt-in network capability, and
            // leaving it off keeps fetch-provider auto-selection unambiguous.
            registerFetch(
                LocalFetchProvider(
                    LocalFetchConfig(enabled = { providerSettings?.localFetchEnabled() == true }),
                ),
            )
        }
        val searchService = DefaultSearchService(
            registry = searchProviders,
            policies = searchPolicies,
            usage = searchUsage,
            maxQueries = 4,
            maxResults = 8,
            summaryMaxChars = 800,
            searchTimeoutMs = 30_000,
            fetchTimeoutMs = 30_000,
        )
        searchPolicyStore = searchPolicies
        searchUsageStore = searchUsage
        searchRegistry = searchProviders
        searchPlugin = SearchPlugin(searchService)
        registerSearchTools(registry, searchService)
        registry.registerVisibility(SEARCH_TOOL_ID) { context ->
            searchService.shouldExposeSearch(context.workspaceId, context.searchRoute())
        }
        registry.registerVisibility(FETCH_TOOL_ID) { context ->
            searchService.shouldExposeFetch(context.workspaceId, context.searchRoute())
        }

        // The plugin manager tool is registered with the other tools but wired in the app host,
        // because only the host owns the lifecycle manager and the plugin factories.
        pluginManagerService?.let { service ->
            registerPluginManagerTools(registry, service, registerCredential = { name, header, value ->
                mcpCredentialWriter?.invoke(name, header, value)
            })
        }

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
        val interactionService = InteractionService(
            commands = commandRegistry,
            mentions = interactionDirectory,
            fills = fillValues,
            authorization = interactionAuthorization,
        )
        interactionPlugin = InteractionPlugin(interactionService, commandRegistry)
        commandRegistry.register(
            CommandDescriptor(
                id = "core.interaction.target",
                name = "目标",
                ownerPluginId = INTERACTION_PLUGIN_ID,
                allowedMentionTypes = MentionType.entries.toSet(),
                requiredCapabilities = setOf("interaction.execute"),
                requiresMention = true,
                executor = InteractionCommandExecutor { envelope, modelId, submitter, onEvent ->
                    CommandExecutionResult(
                        completed = false,
                        modelCall = submitter.submit(envelope.copy(submittedText = envelope.commandInput), modelId, parameters = null, onEvent = onEvent),
                    )
                },
            ),
        )
        return listOf(
            workspacePlugin,
            fileManagerPlugin,
            skillPlugin,
            toolManagerPlugin,
            searchPlugin,
            modelAdapter,
            sessionManager,
            DeepSeekBackendPlugin(modelAdapter, ConfigurableModelBackend(DeepSeekPreset.config(), apiKeyProvider)),
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
                AndroidSearchProviderSettings(context),
                AndroidSearchApiKeyStore(context),
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
            searchProviderSettings: AndroidSearchProviderSettings? = null,
            searchApiKeys: AndroidSearchApiKeyStore? = null,
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
                searchProviderSettings,
                searchApiKeys,
            )
        }
    }
}
