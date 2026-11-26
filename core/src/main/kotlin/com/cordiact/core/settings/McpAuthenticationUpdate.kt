package com.cordiact.core.settings

import com.cordiact.core.mcp.McpCredential
import java.util.concurrent.CancellationException

const val MCP_AUTHENTICATION_AUDIT_TARGET = "mcp.authentication"

/** Runs inside the audited mutation. The caller has already probed and rechecked the target.
 * A failed vault write may have partly committed; never restart until the previous value has been
 * written back successfully. The lifecycle manager may leave a failed restart blocked for repair.
 */
fun applyMcpAuthenticationUpdate(
    previous: McpCredential?,
    replacement: McpCredential?,
    enabled: Boolean,
    writeCredential: (McpCredential?) -> Unit,
    setEnabled: (Boolean) -> Unit,
) {
    if (enabled) setEnabled(false) // If disable is uncertain, do not touch the vault.
    try {
        writeCredential(replacement)
        if (enabled) setEnabled(true)
    } catch (failure: Exception) {
        // Do not retry the new credential or enable with an uncertain vault value.
        val restored = runCatching { writeCredential(previous) }.isSuccess
        if (enabled && restored) runCatching { setEnabled(true) }
        if (failure is CancellationException) throw failure
        throw IllegalStateException("MCP_AUTH_UPDATE_FAILED_RECONCILE")
    }
}
