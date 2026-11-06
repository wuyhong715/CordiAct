package com.cordiact.core.automation

/**
 * Pages where the agent may look but must not act.
 *
 * Rationale: a model reasoning over untrusted screen content must not be able
 * to grant itself permissions, install packages or disable the lock screen just
 * because the page happened to contain a button labelled "确定".
 */
object ProtectedPages {

    /** Packages whose every page is protected. */
    private val FULLY_PROTECTED = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.shell",
    )

    /** Packages the agent must not automate at all. */
    private val BLOCKED = setOf(
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "com.icbc",
        "com.chinamworld.bocmbci",
    )

    /** Settings page titles that are protected when visible. */
    private val SETTINGS_TITLES = listOf(
        "无障碍", "Accessibility", "开发者选项", "Developer options",
        "安装未知应用", "Install unknown apps", "设备管理", "Device admin",
        "安全", "Security", "隐私", "Privacy", "锁屏", "Lock screen",
        "生物识别", "Biometrics", "权限管理", "Permission manager",
        "恢复出厂设置", "Factory reset", "系统更新", "System update",
    )

    fun isBlockedPackage(packageName: String): Boolean = packageName in BLOCKED

    /**
     * Whether the page is protected. [titleTexts] should contain visible
     * toolbar / page-title texts; matching is substring-based because OEM ROMs
     * localise and restyle settings titles.
     */
    fun isProtected(packageName: String, titleTexts: Sequence<String>): Boolean {
        if (packageName in BLOCKED || packageName in FULLY_PROTECTED) return true
        if (packageName != "com.android.settings") return false
        val haystack = titleTexts.joinToString(" ")
        if (haystack.isBlank()) return true // settings app with unknown page: fail closed
        return SETTINGS_TITLES.any { haystack.contains(it) }
    }
}

/** Per-app automation policy, mirroring DSH Go's app rules. */
enum class AppPolicy {
    /** Normal automation. */
    ALLOW,

    /** Every action needs an explicit user confirmation. */
    CONFIRM_EACH_STEP,

    /** Read-only: snapshot works, actions are refused. */
    READ_ONLY,

    /** Never touch. */
    BLOCK,
}

object AppPolicyTable {

    private val DEFAULTS: Map<String, AppPolicy> = mapOf(
        "com.eg.android.AlipayGphone" to AppPolicy.BLOCK,
        "com.tencent.mm" to AppPolicy.CONFIRM_EACH_STEP,
        "com.unionpay" to AppPolicy.BLOCK,
    )

    fun policyFor(packageName: String, overrides: Map<String, AppPolicy> = emptyMap()): AppPolicy =
        overrides[packageName] ?: DEFAULTS[packageName] ?: AppPolicy.ALLOW
}

/** Result of the confirmation gate for a single action. */
enum class ConfirmationDecision { ALLOW, DENY, TIMEOUT }

/**
 * Host-side confirmation gate. Implementations surface a small prompt; core
 * only defines the decision shape so tools can be tested with a stub.
 */
fun interface ConfirmationGate {
    fun confirm(request: ConfirmationRequest): ConfirmationDecision
}

data class ConfirmationRequest(
    val packageName: String,
    val action: String,
    val targetLabel: String?,
    val reason: String,
    val timeoutMs: Long = 30_000,
)
