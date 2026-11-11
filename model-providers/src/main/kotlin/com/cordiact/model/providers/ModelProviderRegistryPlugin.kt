package com.cordiact.model.providers

import com.cordiact.core.attachment.AttachmentAuditSink
import com.cordiact.core.attachment.AttachmentReader
import com.cordiact.core.attachment.NoopAttachmentAuditSink
import com.cordiact.core.conversation.MODEL_ADAPTER_PLUGIN_ID
import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

const val MODEL_PROVIDER_REGISTRY_PLUGIN_ID = "model.providers"

fun modelProviderRegistryPluginSpec() = PluginSpec(
    id = MODEL_PROVIDER_REGISTRY_PLUGIN_ID,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf(MODEL_ADAPTER_PLUGIN_ID),
)

/** Runtime management API for custom protocol/provider definitions; credentials are never part of config. */
interface ModelProviderRegistryService {
    fun providers(): List<ModelProviderConfig>
    fun addProvider(config: ModelProviderConfig)
    fun discoverAndAddProvider(connection: ModelProviderConnection, apiKey: String): ModelProviderConfig
    fun refreshModels(providerId: String): ModelProviderConfig
    fun removeProvider(providerId: String)

    /**
     * Replaces an existing provider's configuration in place, keeping its id and stored credential.
     * Used to adjust sampling defaults or any other non-secret setting.
     */
    fun updateProvider(config: ModelProviderConfig)
}

interface ModelProviderConfigStore {
    fun load(): List<ModelProviderConfig>
    fun save(config: ModelProviderConfig)
    fun remove(providerId: String)
}

class InMemoryModelProviderConfigStore : ModelProviderConfigStore {
    private val values = linkedMapOf<String, ModelProviderConfig>()
    @Synchronized override fun load(): List<ModelProviderConfig> = values.values.toList()
    @Synchronized override fun save(config: ModelProviderConfig) { values[config.providerId] = config }
    @Synchronized override fun remove(providerId: String) { values.remove(providerId) }
}

class ModelProviderRegistryPlugin(
    private val adapter: ModelAdapterPlugin,
    private val credentialFor: (String) -> ApiKeyProvider,
    private val configStore: ModelProviderConfigStore = InMemoryModelProviderConfigStore(),
    private val forgetCredential: (String) -> Unit = {},
    /**
     * Attachment storage handed to every provider backend.
     *
     * Null until the app wires a Workspace-scoped attachment repository; a null store makes any
     * attachment message fail closed instead of being sent without its media.
     */
    private val attachmentReader: AttachmentReader? = null,
    /** Audit trail for attachment sends and refusals; see ConfigurableModelBackend. */
    private val attachmentAudit: AttachmentAuditSink = NoopAttachmentAuditSink,
    private val discovery: ModelDiscoveryService = HttpModelDiscoveryService(),
    private val saveCredential: ((String, String) -> Unit)? = null,
) : ManagedPlugin, ModelProviderRegistryService {
    override val spec = modelProviderRegistryPluginSpec()
    private val configs = linkedMapOf<String, ModelProviderConfig>().apply {
        val stored = configStore.load()
        require(stored.size <= MAX_PROVIDERS) { "Too many stored model providers" }
        stored.forEach { config ->
            require(!config.allowInsecureLoopbackForTests) { "Test-only loopback endpoints cannot be loaded by the app registry" }
            require(!containsKey(config.providerId)) { "Duplicate stored provider '${config.providerId}'" }
            put(config.providerId, config.duplicate(models = config.models.toList()))
        }
    }
    private val backends = linkedMapOf<String, ConfigurableModelBackend>()
    @Volatile private var enabled = false

    @Synchronized
    override fun onEnable() {
        check(!enabled) { "Model Provider Registry is already enabled" }
        enabled = true
        try {
            configs.values.forEach(::attach)
        } catch (failure: Exception) {
            backends.keys.toList().forEach(::detach)
            enabled = false
            throw failure
        }
    }

    @Synchronized
    override fun onDisable() {
        if (!enabled && backends.isEmpty()) return
        enabled = false
        backends.keys.toList().forEach(::detach)
    }

    @Synchronized
    override fun onUninstall() {
        onDisable()
        synchronized(this) {
            configs.keys.toList().forEach { providerId ->
                configStore.remove(providerId)
                forgetCredential(providerId)
            }
            configs.clear()
        }
    }

    @Synchronized
    override fun providers(): List<ModelProviderConfig> = configs.values.map { it.duplicate(models = it.models.toList()) }

    override fun discoverAndAddProvider(connection: ModelProviderConnection, apiKey: String): ModelProviderConfig {
        // Network work must not hold the registry monitor or freeze generation/provider settings.
        val persistKey = saveCredential ?: error("Provider credential writer is not configured")
        val modelIds = discovery.discover(connection, apiKey)
        val config = connection.config(modelIds)
        synchronized(this) {
            check(enabled) { "Model Provider Registry is not enabled" }
            require(config.providerId !in configs) { "Provider is already configured" }
            require(configs.size < MAX_PROVIDERS) { "Too many providers" }
            try {
                persistKey(config.providerId, apiKey)
                addProvider(config)
            } catch (failure: Exception) {
                runCatching { forgetCredential(config.providerId) }
                throw failure
            }
        }
        return config
    }

    override fun refreshModels(providerId: String): ModelProviderConfig {
        val original = synchronized(this) {
            check(enabled) { "Model Provider Registry is not enabled" }
            configs[providerId] ?: error("Provider is not configured")
        }
        val key = credentialFor(providerId).apiKey() ?: error("Provider API key is missing")
        val ids = discovery.discover(ModelProviderConnection(original.providerId, original.displayName, original.protocol, original.endpoint), key)
        val refreshed = original.duplicate(
            models = ids,
            parameterCapabilities = original.parameterCapabilities.filterKeys { it in ids },
            attachmentCapabilities = original.attachmentCapabilities.filterKeys { it in ids },
        )
        synchronized(this) {
            check(enabled && configs[providerId] === original) { "Provider changed during discovery; retry" }
            updateProvider(refreshed)
        }
        return refreshed
    }

    @Synchronized
    override fun addProvider(config: ModelProviderConfig) {
        check(enabled) { "Model Provider Registry is not enabled" }
        require(!config.allowInsecureLoopbackForTests) { "Test-only loopback endpoints cannot be registered by the app" }
        require(configs.size < MAX_PROVIDERS) { "At most $MAX_PROVIDERS providers may be configured" }
        require(!configs.containsKey(config.providerId)) { "Provider '${config.providerId}' is already configured" }
        val safeConfig = config.duplicate(models = config.models.toList())
        configStore.save(safeConfig.duplicate(allowInsecureLoopbackForTests = false))
        configs[safeConfig.providerId] = safeConfig
        try {
            attach(safeConfig)
        } catch (failure: Exception) {
            configs.remove(config.providerId)
            configStore.remove(config.providerId)
            throw failure
        }
    }

    @Synchronized
    override fun updateProvider(config: ModelProviderConfig) {
        check(enabled) { "Model Provider Registry is not enabled" }
        require(!config.allowInsecureLoopbackForTests) { "Test-only loopback endpoints cannot be registered by the app" }
        val existing = configs[config.providerId]
            ?: throw IllegalArgumentException("Provider '${config.providerId}' is not configured")
        // A protocol, endpoint or model change invalidates the live backend, so rebuild it.
        val safeConfig = config.duplicate(models = config.models.toList())
        detach(config.providerId)
        try {
            configStore.save(safeConfig.duplicate(allowInsecureLoopbackForTests = false))
            configs[config.providerId] = safeConfig
            attach(safeConfig)
        } catch (failure: Exception) {
            configs[config.providerId] = existing
            runCatching { configStore.save(existing.duplicate(allowInsecureLoopbackForTests = false)) }
            runCatching { attach(existing) }
            throw failure
        }
    }

    @Synchronized
    override fun removeProvider(providerId: String) {
        check(enabled) { "Model Provider Registry is not enabled" }
        if (configs.remove(providerId) != null) {
            detach(providerId)
            configStore.remove(providerId)
            forgetCredential(providerId)
        }
    }

    private fun attach(config: ModelProviderConfig) {
        check(config.providerId !in backends) { "Provider '${config.providerId}' is already attached" }
        val backend = ConfigurableModelBackend(
            config,
            credentialFor(config.providerId),
            attachmentReader = attachmentReader,
            attachmentAudit = attachmentAudit,
        )
        try {
            adapter.attachBackend(spec.id, backend)
            backends[config.providerId] = backend
        } catch (failure: Exception) {
            backend.close()
            throw failure
        }
    }

    private fun detach(providerId: String) {
        val backend = backends.remove(providerId) ?: return
        try {
            adapter.detachBackend(spec.id, providerId)
        } finally {
            backend.close()
        }
    }

    private companion object {
        const val MAX_PROVIDERS = 32
    }
}
