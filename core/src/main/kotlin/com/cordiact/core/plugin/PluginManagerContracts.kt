package com.cordiact.core.plugin

/**
 * Model-facing plugin management surface.
 *
 * Deliberately separate from [PluginLifecycleManager]: that class is the state machine and is
 * called by the host at startup, while this interface is what a tool or a settings screen talks
 * to. Keeping them apart is what lets two front-ends (an agent tool and a UI) share one
 * implementation without either owning the lifecycle rules.
 */
interface PluginManagerService {

    /** Plugins the caller may list, with the reason any of them is read-only. */
    fun inventory(): List<PluginInventoryEntry>

    /** Enable or disable one plugin. Bundled plugins report [PluginChangeResult.Application.OVERRIDDEN]. */
    fun setEnabled(pluginId: String, enabled: Boolean): PluginChangeResult

    /**
     * Install or update one user plugin from a spec.
     *
     * Installing an id that already exists with a different spec is refused rather than
     * replacing it: configuration changes go through [remove] then [install], so a half-applied
     * change never leaves a plugin between two configs.
     */
    fun install(spec: PluginSpec): PluginChangeResult

    /** Remove a user plugin and erase its owned data. Bundled plugins are protected. */
    fun remove(pluginId: String): PluginChangeResult
}

/**
 * One row of the inventory.
 *
 * [readOnlyReason] is non-null exactly when a management action would be refused, so a caller
 * never has to duplicate the protection rules to decide whether to offer a button.
 */
data class PluginInventoryEntry(
    val spec: PluginSpec,
    val origin: PluginOrigin,
    val state: PluginState,
    val lastError: String? = null,
    /** Tool ids this plugin currently publishes, for "is it actually doing anything" questions. */
    val publishedToolIds: List<String> = emptyList(),
    val readOnlyReason: ReadOnlyReason? = null,
) {
    /** Why a management action is refused. */
    enum class ReadOnlyReason {
        /** Bundled system plugin: owned by the APK, not by the user or the agent. */
        MANAGEMENT_REQUIRED,
    }
}

/**
 * Outcome of one management call.
 *
 * [nextStep] is the part that matters for a model: it is a sentence saying what to do or tell
 * the user, present whenever [application] is not [Application.APPLIED]. Without it a model
 * happily reports success for a change that only took effect on paper.
 */
data class PluginChangeResult(
    val pluginId: String,
    val application: Application,
    val reason: String? = null,
    val nextStep: String? = null,
    val entry: PluginInventoryEntry? = null,
) {
    enum class Application {
        /** Live now. */
        APPLIED,
        /** Durable, but needs a process restart before it takes effect. */
        RESTART_REQUIRED,
        /** Refused by policy, not by a failure. */
        OVERRIDDEN,
        /** Attempted and failed; [reason] says why. */
        FAILED,
    }
}

/** Structured refusal from [PluginManagerService]; carries the reason a caller renders. */
class PluginManagementException(
    val code: Code,
    message: String,
) : RuntimeException(message) {
    enum class Code {
        UNKNOWN_PLUGIN,
        ALREADY_INSTALLED,
        NOT_REMOVABLE,
        INVALID_SPEC,
        MANAGEMENT_FAILED,
    }
}

/**
 * Turns a service call into the JSON-ish result the model reads.
 *
 * Lives in core so every surface words a change outcome the same way; the tool and the settings
 * screen must never disagree about what "restart required" means.
 */
object PluginManagerPresenter {

    fun present(result: PluginChangeResult): String = buildString {
        append("plugin=").append(result.pluginId)
        append(" application=").append(result.application.name.lowercase())
        result.reason?.let { append(" reason=\"").append(it).append('"') }
        result.nextStep?.let { append("\nnextStep: ").append(it) }
        result.entry?.let { entry ->
            append("\nstate=").append(entry.state.name.lowercase())
            append(" origin=").append(entry.origin.name.lowercase())
            if (entry.publishedToolIds.isNotEmpty()) {
                append(" tools=").append(entry.publishedToolIds.joinToString(","))
            }
            entry.lastError?.let { append(" lastError=\"").append(it).append('"') }
        }
    }

    fun presentInventory(entries: List<PluginInventoryEntry>): String =
        if (entries.isEmpty()) {
            "No plugins installed."
        } else {
            entries.joinToString("\n") { entry ->
                buildString {
                    append(entry.spec.id)
                    append(' ').append(entry.spec.version)
                    append(' ').append(entry.state.name.lowercase())
                    append(' ').append(entry.origin.name.lowercase())
                    entry.readOnlyReason?.let { append(" readOnly=").append(it.name.lowercase()) }
                    if (entry.publishedToolIds.isNotEmpty()) {
                        append(" tools=").append(entry.publishedToolIds.size)
                    }
                    entry.lastError?.let { append(" lastError=\"").append(it).append('"') }
                }
            }
        }
}

const val PLUGIN_MANAGER_PLUGIN_ID = "core.plugin-manager"

fun pluginManagerPluginSpec(): PluginSpec = PluginSpec(
    id = PLUGIN_MANAGER_PLUGIN_ID,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
)
