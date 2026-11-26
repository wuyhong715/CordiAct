package com.cordiact.mcp.client

import com.cordiact.core.mcp.McpClientFactory
import com.cordiact.core.mcp.McpCredential
import com.cordiact.core.mcp.McpServerConfig
import com.cordiact.core.mcp.McpToolDescriptor

/** Read a fresh catalog on a disposable connection. Does not publish or invoke any tools. */
fun McpClientFactory.probeTools(config: McpServerConfig, credential: McpCredential?): List<McpToolDescriptor> {
    val connection = connect(config, credential)
    return AutoCloseable { connection.close() }.use {
        connection.initialize()
        connection.listTools(config.toolCallTimeoutMs)
    }
}
