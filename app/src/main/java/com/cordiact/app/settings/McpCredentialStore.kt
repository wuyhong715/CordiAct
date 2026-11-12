package com.cordiact.app.settings

import android.content.Context
import com.cordiact.app.security.AndroidSystemCredentials
import com.cordiact.core.mcp.McpCredential
import org.json.JSONObject

/** Plaintext is accessible only to the execution path; UI reads metadata. */
class McpCredentialStore(context: Context) {
    private val vault = AndroidSystemCredentials.get(context)
    fun set(server: String, credential: McpCredential?) {
        credential?.let { validateHeader(it.headerName); require(it.value.isNotBlank() && !it.value.contains('\r') && !it.value.contains('\n')) }
        vault.set("mcp", server, credential?.let { JSONObject().put("name", it.headerName).put("value", it.value).toString() })
    }
    fun readForExecution(server: String): McpCredential? = vault.read("mcp", server)?.let {
        val value = JSONObject(it)
        McpCredential(value.getString("name"), value.getString("value")).also { validateHeader(it.headerName) }
    }
    companion object {
        fun validateHeader(name: String) {
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "MCP_HEADER_INVALID" }
            require(name.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding")) { "MCP_HEADER_FORBIDDEN" }
        }
    }
}
