package com.cordiact.core.automation

/** How much privilege the current channel actually has. */
enum class PrivilegeLevel {
    NONE,

    /** uid 2000: Shizuku started from ADB/wireless debugging, or a self-ADB channel. */
    SHELL,

    /** uid 0: Sui (root-started Shizuku) or libsu/`su`. */
    ROOT,
}

data class PrivilegeStatus(
    val level: PrivilegeLevel,
    val reason: String? = null,
    val recovery: String? = null,
    val backend: String? = null,
) {
    val ready: Boolean get() = level != PrivilegeLevel.NONE
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

/**
 * A privileged command channel. Implementations live in `app/privilege`
 * (Shizuku UserService, self-ADB, libsu). `core` only defines the contract so
 * the screen/display plugins can be tested against a fake.
 */
interface PrivilegedChannel {
    fun status(): PrivilegeStatus

    fun exec(command: String, timeoutMs: Long = ShellLimits.DEFAULT_TIMEOUT_MS): ShellResult
}

object ShellLimits {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 300_000L
    const val MAX_OUTPUT_CHARS = 64_000
    const val MAX_COMMAND_CHARS = 8_192
}

enum class ShellCommandClass {
    READ_ONLY,
    MUTATING,
    DESTRUCTIVE,
    INVALID,
}

/**
 * Classifies shell commands before they reach the channel. This is not a
 * security boundary (any `am`/`pm` command can already change the device);
 * it is a guard against accidental destructive commands from a model that is
 * reasoning over untrusted screen content.
 */
object ShellCommandPolicy {

    /** Command boundary: start of line, whitespace, or a shell separator/quote. */
    private const val BOUNDARY = """(^|[\s'"`;|&()])"""

    private val DESTRUCTIVE = listOf(
        Regex("""${BOUNDARY}rm\s+-[a-zA-Z]*r[a-zA-Z]*f\s+[/~]"""),
        Regex("""${BOUNDARY}rm\s+-[a-zA-Z]*f[a-zA-Z]*r\s+[/~]"""),
        Regex("""${BOUNDARY}mkfs(\.|\s)"""),
        Regex("""${BOUNDARY}dd\s+.*\bof=/dev/"""),
        Regex("""${BOUNDARY}(reboot|poweroff|shutdown)\b"""),
        Regex("""${BOUNDARY}pm\s+(uninstall|clear)\b"""),
        Regex("""${BOUNDARY}settings\s+put\s+secure\s+enabled_accessibility_services\b"""),
        Regex("""${BOUNDARY}svc\s+power\b"""),
        Regex("""${BOUNDARY}am\s+force-stop\s+android\b"""),
        Regex("""${BOUNDARY}su(\s|$)"""),
        Regex("""${BOUNDARY}chmod\s+777\s+/data\b"""),
    )

    private val MUTATING_PREFIXES = listOf(
        "pm ", "am ", "settings put", "settings delete", "setprop ", "svc ",
        "wm ", "input ", "cmd ", "content ", "appops ", "deviceidle ",
        "screencap", "screenrecord",
    )

    private val READ_ONLY_PREFIXES = listOf(
        "getprop", "pm list", "pm path", "pm dump", "settings get", "dumpsys ",
        "am stack list", "am task list", "wm size", "wm density", "wm user-rotation",
        "id", "whoami", "ps ", "pidof ", "ls ", "stat ", "cat /proc/", "df ", "du ",
    )

    fun classify(raw: String): ShellCommandClass {
        val command = raw.trim()
        if (command.isEmpty() || command.length > ShellLimits.MAX_COMMAND_CHARS) return ShellCommandClass.INVALID
        if (DESTRUCTIVE.any { it.containsMatchIn(command) }) return ShellCommandClass.DESTRUCTIVE
        if (READ_ONLY_PREFIXES.any { command.startsWith(it) }) return ShellCommandClass.READ_ONLY
        if (MUTATING_PREFIXES.any { command.startsWith(it) }) return ShellCommandClass.MUTATING
        return ShellCommandClass.MUTATING
    }

    /**
     * Whether a command may run given the channel level and whether the user
     * enabled the "advanced commands" switch.
     */
    fun isAllowed(command: String, level: PrivilegeLevel, advancedEnabled: Boolean): Boolean {
        if (level == PrivilegeLevel.NONE) return false
        return when (classify(command)) {
            ShellCommandClass.INVALID -> false
            ShellCommandClass.DESTRUCTIVE -> advancedEnabled
            ShellCommandClass.MUTATING, ShellCommandClass.READ_ONLY -> true
        }
    }
}
