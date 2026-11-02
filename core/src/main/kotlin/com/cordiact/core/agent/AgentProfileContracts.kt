package com.cordiact.core.agent

import com.cordiact.core.conversation.GenerationParameters
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.workspace.WorkspaceManagerService

const val AGENT_PROFILE_PLUGIN_ID = "core.agent-profile"
const val AGENT_PROFILE_VERSION = "0.1.0"

fun agentProfilePluginSpec() = PluginSpec(
    id = AGENT_PROFILE_PLUGIN_ID,
    version = AGENT_PROFILE_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf("core.workspace-manager"),
)

/**
 * Where a profile came from. Higher [precedence] wins when two profiles share an id.
 *
 * Whole-record replacement, never a deep merge: a merged profile is a state nobody can
 * describe, and "which tool list is in effect" must always have one answer.
 */
enum class ProfileOrigin(val precedence: Int) {
    BUNDLED(0),
    WORKSPACE(10),
    IMPORTED(20),
    SESSION_OVERRIDE(30),
}

/** Instruction text is either inline (bundled, immutable) or a file inside the profile's own Workspace. */
sealed interface InstructionSource {
    data class Inline(val text: String) : InstructionSource

    /**
     * A Workspace-relative path. It must resolve inside the profile's own Workspace:
     * a profile cannot borrow instructions from somewhere else, which keeps
     * "a profile belongs to exactly one Workspace" true in every direction.
     */
    data class WorkspaceFile(val path: String) : InstructionSource
}

data class AgentProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "Agent profile id must not be blank" }
        require(value.length <= MAX_ID_LENGTH) { "Agent profile id must be at most $MAX_ID_LENGTH chars" }
    }

    companion object {
        const val MAX_ID_LENGTH = 128
    }
}

/**
 * A declarative agent preset.
 *
 * Only [instructions], [modelRef] and [generation] are profile-owned. Everything else is a
 * request: [toolRefs] is intersected with the workspace's enabled tools and can never enable
 * one, and [requiredCapabilities] can only make the profile unusable, never grant anything.
 * That split is the whole point — a profile is a statement of intent, not a permission grant.
 */
data class AgentProfile(
    val id: AgentProfileId,
    /** Owner. A profile is Workspace-scoped and never global. */
    val workspaceId: String,
    /** Monotonic per (workspaceId, id). A new version is a new row; older rows stay resolvable. */
    val version: Int,
    val displayName: String,
    val description: String? = null,
    /** Ordered instruction sources, concatenated in list order. */
    val instructions: List<InstructionSource> = emptyList(),
    /** Target model. Null means "inherit the session default". */
    val modelRef: String? = null,
    /** Sampling defaults for this profile; per-turn overrides still win field by field. */
    val generation: GenerationParameters? = null,
    /** Tools this profile asks for. Intersected with enabled tools; never grants. */
    val toolRefs: Set<String> = emptySet(),
    /** Capabilities the host must already have authorized before this profile may load. */
    val requiredCapabilities: Set<String> = emptySet(),
    val origin: ProfileOrigin = ProfileOrigin.WORKSPACE,
) {
    init {
        require(workspaceId.isNotBlank()) { "Agent profile must belong to a Workspace" }
        require(version >= 1) { "Agent profile version starts at 1" }
        require(displayName.isNotBlank()) { "Agent profile display name must not be blank" }
    }
}

/** What the caller gets after resolution: an immutable, already-validated snapshot. */
data class ResolvedAgentProfile(
    val id: AgentProfileId,
    val workspaceId: String,
    val version: Int,
    val displayName: String,
    /** Instruction texts in order; WorkspaceFile sources have already been read. */
    val instructions: List<String>,
    val modelRef: String?,
    val generation: GenerationParameters?,
    /** toolRefs after intersecting with the enabled tool set. */
    val enabledTools: Set<String>,
    /** toolRefs that were requested but are not enabled; kept for diagnostics, never silently dropped. */
    val unavailableTools: Set<String>,
)

/** Why a profile could not be resolved. Every case rejects the profile rather than degrading it. */
class AgentProfileException(val code: Code, message: String) : RuntimeException(message) {
    enum class Code {
        PROFILE_NOT_FOUND,
        WORKSPACE_NOT_FOUND,
        WORKSPACE_ARCHIVED,
        CAPABILITY_DENIED,
        TOOL_NOT_REGISTERED,
        MODEL_NOT_REGISTERED,
        INSTRUCTION_FILE_UNREADABLE,
        INSTRUCTION_PATH_OUTSIDE_WORKSPACE,
        DUPLICATE_VERSION,
        STORAGE_FAILURE,
    }
}

/** Read-only tool visibility, satisfied by the tool manager. */
interface ToolVisibility {
    fun enabledToolIds(workspaceId: String): Set<String>
    fun registeredToolIds(workspaceId: String): Set<String>
}

/** Model visibility, satisfied by the provider registry. */
fun interface ModelVisibility {
    fun isRegistered(modelId: String): Boolean
}

/** Reads instruction files, restricted to one Workspace root. */
fun interface InstructionFileReader {
    fun read(workspaceId: String, relativePath: String, maxBytes: Int): String
}

interface AgentProfileService {
    fun list(workspaceId: String): List<AgentProfile>

    fun get(workspaceId: String, id: AgentProfileId, version: Int? = null): AgentProfile?

    /**
     * Resolves the effective profile for a session and returns an immutable snapshot.
     *
     * Fails closed on a dangling tool reference, a denied capability or an unreadable
     * instruction file. A profile that half-loads is worse than one that does not load:
     * the user would see a session silently missing tools with nothing to explain it.
     */
    fun resolve(workspaceId: String, id: AgentProfileId): ResolvedAgentProfile

    /** Appends a new version. Rejects an existing (workspaceId, id, version). */
    fun save(profile: AgentProfile): AgentProfile

    /** Picks the winning profile for an id across origins; higher precedence replaces wholesale. */
    fun selectByPrecedence(candidates: List<AgentProfile>): AgentProfile?
}

class AgentProfilePlugin(
    private val workspaceManager: WorkspaceManagerService,
    private val store: AgentProfileStore,
    private val tools: ToolVisibility,
    private val models: ModelVisibility,
    private val instructions: InstructionFileReader,
    private val grantedCapabilities: Set<String>,
) : ManagedPlugin, AgentProfileService {
    init {
        require(grantedCapabilities.none { it.isBlank() }) { "Granted capabilities must not contain blank entries" }
    }

    override val spec: PluginSpec = agentProfilePluginSpec()
    @Volatile private var enabled = false

    override fun onEnable() {
        enabled = true
    }

    override fun onDisable() {
        enabled = false
    }

    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultAgentProfileService(
            workspaceManager = workspaceManager,
            store = store,
            tools = tools,
            models = models,
            instructions = instructions,
            grantedCapabilities = grantedCapabilities,
        )
    }

    private fun service(): AgentProfileService {
        check(enabled) { "Agent profile plugin is not enabled" }
        return delegate
    }

    override fun list(workspaceId: String): List<AgentProfile> = service().list(workspaceId)

    override fun get(workspaceId: String, id: AgentProfileId, version: Int?): AgentProfile? =
        service().get(workspaceId, id, version)

    override fun resolve(workspaceId: String, id: AgentProfileId): ResolvedAgentProfile =
        service().resolve(workspaceId, id)

    override fun save(profile: AgentProfile): AgentProfile = service().save(profile)

    override fun selectByPrecedence(candidates: List<AgentProfile>): AgentProfile? =
        delegate.selectByPrecedence(candidates)
}

/** Durable profile port. Implemented by the same database as sessions and workspaces. */
interface AgentProfileStore {
    fun list(workspaceId: String): List<AgentProfile>
    fun get(workspaceId: String, id: AgentProfileId, version: Int): AgentProfile?
    fun latest(workspaceId: String, id: AgentProfileId): AgentProfile?
    fun insert(profile: AgentProfile): AgentProfile
    fun exists(workspaceId: String, id: AgentProfileId, version: Int): Boolean
}

internal class DefaultAgentProfileService(
    private val workspaceManager: WorkspaceManagerService,
    private val store: AgentProfileStore,
    private val tools: ToolVisibility,
    private val models: ModelVisibility,
    private val instructions: InstructionFileReader,
    private val grantedCapabilities: Set<String>,
) : AgentProfileService {

    override fun list(workspaceId: String): List<AgentProfile> {
        requireWorkspace(workspaceId)
        return store.list(workspaceId).sortedWith(compareBy({ it.id.value }, { it.version }))
    }

    override fun get(workspaceId: String, id: AgentProfileId, version: Int?): AgentProfile? {
        requireWorkspace(workspaceId)
        return if (version == null) store.latest(workspaceId, id) else store.get(workspaceId, id, version)
    }

    override fun resolve(workspaceId: String, id: AgentProfileId): ResolvedAgentProfile {
        requireWorkspace(workspaceId)
        val profile = store.latest(workspaceId, id)
            ?: throw AgentProfileException(AgentProfileException.Code.PROFILE_NOT_FOUND, "No profile ${id.value}")

        val denied = profile.requiredCapabilities - grantedCapabilities
        if (denied.isNotEmpty()) {
            throw AgentProfileException(
                AgentProfileException.Code.CAPABILITY_DENIED,
                "Profile ${id.value} requires capabilities that are not granted: ${denied.sorted()}",
            )
        }

        val registered = tools.registeredToolIds(workspaceId)
        val dangling = profile.toolRefs - registered
        if (dangling.isNotEmpty()) {
            throw AgentProfileException(
                AgentProfileException.Code.TOOL_NOT_REGISTERED,
                "Profile ${id.value} references unknown tools: ${dangling.sorted()}",
            )
        }

        profile.modelRef?.let { model ->
            if (!models.isRegistered(model)) {
                throw AgentProfileException(
                    AgentProfileException.Code.MODEL_NOT_REGISTERED,
                    "Profile ${id.value} references an unregistered model: $model",
                )
            }
        }

        val texts = profile.instructions.map { source ->
            when (source) {
                is InstructionSource.Inline -> source.text
                is InstructionSource.WorkspaceFile -> readInstruction(workspaceId, id, source.path)
            }
        }

        val enabled = tools.enabledToolIds(workspaceId)
        return ResolvedAgentProfile(
            id = profile.id,
            workspaceId = profile.workspaceId,
            version = profile.version,
            displayName = profile.displayName,
            instructions = texts,
            modelRef = profile.modelRef,
            generation = profile.generation,
            // Intersection, never union: a profile cannot make an unenabled tool available.
            enabledTools = profile.toolRefs.intersect(enabled),
            unavailableTools = profile.toolRefs - enabled,
        )
    }

    override fun save(profile: AgentProfile): AgentProfile {
        requireWorkspace(profile.workspaceId)
        if (store.exists(profile.workspaceId, profile.id, profile.version)) {
            throw AgentProfileException(
                AgentProfileException.Code.DUPLICATE_VERSION,
                "Profile ${profile.id.value} already has version ${profile.version}",
            )
        }
        return store.insert(profile)
    }

    override fun selectByPrecedence(candidates: List<AgentProfile>): AgentProfile? {
        if (candidates.isEmpty()) return null
        val ids = candidates.map { it.id.value }.distinct()
        require(ids.size == 1) { "selectByPrecedence expects candidates sharing one id, got $ids" }
        // Ties on precedence fall back to the newest version, then to declaration order.
        return candidates.sortedWith(
            compareByDescending<AgentProfile> { it.origin.precedence }
                .thenByDescending { it.version },
        ).first()
    }

    private fun readInstruction(workspaceId: String, id: AgentProfileId, path: String): String {
        // A malformed path is a profile defect, not a crash: surface it as a typed rejection.
        val normalized = try {
            normalizeRelativePath(path)
        } catch (e: IllegalArgumentException) {
            throw AgentProfileException(
                AgentProfileException.Code.INSTRUCTION_PATH_OUTSIDE_WORKSPACE,
                "Instruction path '$path' is not a valid Workspace-relative path for profile ${id.value}: ${e.message}",
            )
        }
        val root = workspaceManager.resolveRoot(workspaceId)
        // Reject anything that escapes the Workspace, including via symlinks, before reading.
        val target = java.io.File(root, normalized).canonicalFile
        if (!target.path.startsWith(root.canonicalFile.path + java.io.File.separator)) {
            throw AgentProfileException(
                AgentProfileException.Code.INSTRUCTION_PATH_OUTSIDE_WORKSPACE,
                "Instruction path $path escapes the Workspace for profile ${id.value}",
            )
        }
        return try {
            instructions.read(workspaceId, normalized, MAX_INSTRUCTION_BYTES)
        } catch (e: AgentProfileException) {
            throw e
        } catch (e: Exception) {
            throw AgentProfileException(
                AgentProfileException.Code.INSTRUCTION_FILE_UNREADABLE,
                "Could not read instruction file $path for profile ${id.value}: ${e.message}",
            )
        }
    }

    private fun requireWorkspace(workspaceId: String) {
        val workspace = workspaceManager.get(workspaceId)
            ?: throw AgentProfileException(
                AgentProfileException.Code.WORKSPACE_NOT_FOUND,
                "Unknown Workspace $workspaceId",
            )
        if (workspace.archivedAtMillis != null) {
            throw AgentProfileException(
                AgentProfileException.Code.WORKSPACE_ARCHIVED,
                "Workspace $workspaceId is archived",
            )
        }
    }

    companion object {
        const val MAX_INSTRUCTION_BYTES = 64 * 1024

        /**
         * Normalizes a Workspace-relative path and rejects traversal, absolute paths and
         * dot-prefixed segments. Dot segments are banned outright because the Workspace root
         * is also a git repository: `.git` must never be reachable as instruction text.
         */
        internal fun normalizeRelativePath(path: String): String {
            require(path.isNotBlank()) { "Instruction path must not be blank" }
            require(!path.startsWith("/")) { "Instruction path must be relative: $path" }
            val segments = path.split('/')
            require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
                "Instruction path must not contain empty, . or .. segments: $path"
            }
            require(segments.none { it.startsWith(".") }) {
                "Instruction path must not address hidden paths: $path"
            }
            return segments.joinToString("/")
        }
    }
}
