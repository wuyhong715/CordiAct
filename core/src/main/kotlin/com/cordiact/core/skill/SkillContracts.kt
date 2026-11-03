package com.cordiact.core.skill

import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

const val SKILL_PLUGIN_ID = "core.skill"
const val SKILL_PLUGIN_VERSION = "0.1.0"

fun skillPluginSpec() = PluginSpec(
    id = SKILL_PLUGIN_ID,
    version = SKILL_PLUGIN_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf("core.workspace-manager", "core.file-manager"),
)

/**
 * Where a skill came from. Lower rank wins, matching harness's integer scheme.
 *
 * Workspace skills outrank bundled ones because the Workspace is the user's own space: if they
 * wrote a skill named `review`, that is what `review` must mean there. Silently falling back to
 * the bundled version would make their file look ignored.
 */
enum class SkillOrigin(val rank: Int) {
    /**
     * A skill the user wrote in `skills/`.
     *
     * Always the highest rank: if the user wrote it, that is what the name means. A generated
     * procedure with the same name is refused rather than allowed to shadow it.
     */
    WORKSPACE(100),

    /**
     * A procedure distilled from memory under `memory/skills/`.
     *
     * Below user skills and above bundled ones: it is specific to this Workspace, so it beats a
     * generic built-in, but it is machine-derived, so it must never outrank something the user
     * wrote by hand.
     */
    GENERATED(200),

    BUNDLED(600),
}

/** A parsed `SKILL.md` header. The body is read on demand, never at discovery. */
data class SkillDescriptor(
    /** Directory name, which is also the invocation name. */
    val name: String,
    val description: String,
    val origin: SkillOrigin,
    /** Workspace the skill belongs to. Null for bundled skills. */
    val workspaceId: String?,
    /** Workspace-relative path to the SKILL.md, e.g. `skills/review/SKILL.md`. */
    val path: String,
) {
    init {
        require(name.isNotBlank()) { "Skill name must not be blank" }
        require(description.isNotBlank()) { "Skill description must not be blank" }
        require(name.length <= SkillParser.MAX_NAME_LENGTH) {
            "Skill name must be at most ${SkillParser.MAX_NAME_LENGTH} chars"
        }
    }
}

/** Rejection reasons. Every one rejects the skill rather than loading a partial entry. */
class SkillException(val code: Code, message: String) : RuntimeException(message) {
    enum class Code {
        MISSING_FRONTMATTER,
        MISSING_NAME,
        MISSING_DESCRIPTION,
        NAME_TOO_LONG,
        INVALID_NAME,
        BODY_UNREADABLE,
        DISCOVERY_FAILED,
        TOO_LARGE,
    }
}

/** Parsed pieces of a SKILL.md, before any policy is applied. */
data class ParsedSkill(
    val name: String?,
    val description: String?,
    val body: String,
)

/**
 * Parses the `SKILL.md` header.
 *
 * Only the two fields the index needs are required: a name and a description. Everything else
 * is body text the model reads later. Requiring more would make writing a skill harder than
 * writing a note, and skills that are annoying to write do not get written.
 *
 * A file with no frontmatter is rejected rather than guessed at: inferring a name from the
 * directory and using the first line as a description produces entries the author never
 * reviewed.
 */
object SkillParser {

    const val MAX_NAME_LENGTH = 64
    const val MAX_FILE_CHARS = 256 * 1024
    const val FRONTMATTER_DELIMITER = "---"

    /** Skills distilled by procedural memory live here, distinct from user-authored ones. */
    const val GENERATED_PREFIX = "memory/skills/"

    private val NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")

    fun parse(content: String): ParsedSkill {
        if (content.length > MAX_FILE_CHARS) {
            throw SkillException(
                SkillException.Code.TOO_LARGE,
                "SKILL.md is ${content.length} chars, over the $MAX_FILE_CHARS limit",
            )
        }

        val lines = content.lines()
        if (lines.firstOrNull()?.trim() != FRONTMATTER_DELIMITER) {
            throw SkillException(
                SkillException.Code.MISSING_FRONTMATTER,
                "SKILL.md must start with a '$FRONTMATTER_DELIMITER' frontmatter block",
            )
        }

        val end = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
        if (end < 0) {
            throw SkillException(
                SkillException.Code.MISSING_FRONTMATTER,
                "Frontmatter block is not closed with '$FRONTMATTER_DELIMITER'",
            )
        }

        val header = lines.subList(1, end + 1)
        val body = lines.drop(end + 2).joinToString("\n")

        var name: String? = null
        var description: String? = null
        for (line in header) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val separator = trimmed.indexOf(':')
            if (separator <= 0) continue
            val key = trimmed.substring(0, separator).trim().lowercase()
            val value = trimmed.substring(separator + 1).trim()
                .removeSurrounding("\"").removeSurrounding("'")
            when (key) {
                "name" -> name = value.ifBlank { null }
                "description" -> description = value.ifBlank { null }
            }
        }

        return ParsedSkill(name = name, description = description, body = body.trim())
    }

    /**
     * Validates a parsed header into a descriptor.
     *
     * The name must be lowercase kebab so it can be typed after `/` or `@` without quoting, and
     * so two skills cannot differ only by case on a case-insensitive filesystem.
     */
    fun validate(
        parsed: ParsedSkill,
        directoryName: String,
        origin: SkillOrigin,
        workspaceId: String?,
        path: String,
    ): SkillDescriptor {
        val name = parsed.name
            ?: throw SkillException(SkillException.Code.MISSING_NAME, "SKILL.md must declare a name")
        val description = parsed.description
            ?: throw SkillException(
                SkillException.Code.MISSING_DESCRIPTION,
                "SKILL.md must declare a description; it is all the model sees before choosing",
            )

        if (name.length > MAX_NAME_LENGTH) {
            throw SkillException(
                SkillException.Code.NAME_TOO_LONG,
                "Skill name '$name' is ${name.length} chars, over $MAX_NAME_LENGTH",
            )
        }
        if (!NAME_PATTERN.matches(name)) {
            throw SkillException(
                SkillException.Code.INVALID_NAME,
                "Skill name '$name' must be lowercase letters, digits and dashes",
            )
        }
        // A name that disagrees with its directory makes the same skill addressable two ways,
        // and the index would list something that cannot then be opened under that name.
        if (name != directoryName) {
            throw SkillException(
                SkillException.Code.INVALID_NAME,
                "Skill name '$name' must match its directory '$directoryName'",
            )
        }

        return SkillDescriptor(
            name = name,
            description = description,
            origin = origin,
            workspaceId = workspaceId,
            path = path,
        )
    }
}

/** One skill that could not be loaded, kept so the picker can explain itself. */
data class RejectedSkill(val path: String, val reason: SkillException.Code, val detail: String)

data class SkillCatalog(
    val skills: List<SkillDescriptor>,
    val rejected: List<RejectedSkill>,
) {
    val isEmpty: Boolean get() = skills.isEmpty()
}

/** Reads skill files. Returns null when absent; throws only for real IO errors. */
interface SkillFileSource {
    /** Lists `SKILL.md` paths under `skills/` for [workspaceId]. */
    fun listSkillFiles(workspaceId: String): List<String>

    /** Reads a Workspace-relative path, or null when it does not exist. */
    fun read(workspaceId: String, path: String): String?
}

interface SkillService {
    /** Skills visible in [workspaceId], highest rank first then alphabetical. */
    fun catalog(workspaceId: String): SkillCatalog

    fun find(workspaceId: String, name: String): SkillDescriptor?

    /**
     * Reads a skill body.
     *
     * Separate from [find] because the body is what the model fetches once it has chosen: the
     * index carries names and descriptions only, and loading every body would defeat the point
     * of having an index.
     */
    fun body(workspaceId: String, name: String): String?
}

class SkillPlugin(
    private val source: SkillFileSource,
    private val bundled: List<SkillDescriptor> = emptyList(),
) : ManagedPlugin, SkillService {
    override val spec: PluginSpec = skillPluginSpec()

    @Volatile
    private var enabled = false

    @Volatile
    private var delegate: DefaultSkillService? = null

    override fun onEnable() {
        enabled = true
    }

    override fun onDisable() {
        enabled = false
        delegate = null
    }

    private fun service(): SkillService {
        check(enabled) { "Skill plugin is not enabled" }
        return delegate ?: DefaultSkillService(source, bundled).also { delegate = it }
    }

    /** Drops the cached catalog for [workspaceId], e.g. after a Workspace file change. */
    fun invalidate(workspaceId: String) {
        delegate?.invalidate(workspaceId)
    }

    override fun catalog(workspaceId: String): SkillCatalog = service().catalog(workspaceId)

    override fun find(workspaceId: String, name: String): SkillDescriptor? = service().find(workspaceId, name)

    override fun body(workspaceId: String, name: String): String? = service().body(workspaceId, name)
}

internal class DefaultSkillService(
    private val source: SkillFileSource,
    private val bundled: List<SkillDescriptor>,
) : SkillService {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, SkillCatalog>()

    override fun catalog(workspaceId: String): SkillCatalog = cache.getOrPut(workspaceId) { load(workspaceId) }

    fun invalidate(workspaceId: String) {
        cache.remove(workspaceId)
    }

    private fun load(workspaceId: String): SkillCatalog {
        val rejected = mutableListOf<RejectedSkill>()
        val discovered = mutableListOf<SkillDescriptor>()

        val paths = try {
            source.listSkillFiles(workspaceId)
        } catch (e: Exception) {
            rejected += RejectedSkill("skills/", SkillException.Code.DISCOVERY_FAILED,
                "Skill discovery failed: ${e.message}")
            emptyList()
        }

        for (path in paths.sorted()) {
            val directoryName = path.substringBeforeLast('/').substringAfterLast('/')
            val content = try {
                source.read(workspaceId, path)
            } catch (e: Exception) {
                null
            }
            if (content == null) {
                rejected += RejectedSkill(path, SkillException.Code.BODY_UNREADABLE, "Could not read $path")
                continue
            }
            try {
                discovered += SkillParser.validate(
                    parsed = SkillParser.parse(content),
                    directoryName = directoryName,
                    // Origin follows the path, not the loader: a skill under memory/skills was
                    // distilled from memory and must rank below anything the user wrote.
                    origin = if (path.startsWith(SkillParser.GENERATED_PREFIX)) {
                        SkillOrigin.GENERATED
                    } else {
                        SkillOrigin.WORKSPACE
                    },
                    workspaceId = workspaceId,
                    path = path,
                )
            } catch (e: SkillException) {
                rejected += RejectedSkill(path, e.code, e.message ?: "Rejected")
            }
        }

        // Workspace first, bundled second; alphabetical within an origin for a stable index.
        val merged = (discovered + bundled)
            .sortedWith(compareBy({ it.origin.rank }, { it.name }))
            .distinctBy { it.name }

        return SkillCatalog(skills = merged, rejected = rejected)
    }

    override fun find(workspaceId: String, name: String): SkillDescriptor? =
        catalog(workspaceId).skills.firstOrNull { it.name == name }

    override fun body(workspaceId: String, name: String): String? {
        val descriptor = find(workspaceId, name) ?: return null
        if (descriptor.origin == SkillOrigin.BUNDLED) return null
        val content = try {
            source.read(workspaceId, descriptor.path)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            SkillParser.parse(content).body
        } catch (e: SkillException) {
            null
        }
    }
}
