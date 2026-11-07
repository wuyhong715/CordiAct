package com.cordiact.core.execution

/**
 * Configuration needed to establish an SSH connection.
 */
data class SshHostConfig(
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val pinnedFingerprint: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Host name must not be blank" }
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(user.isNotBlank()) { "User must not be blank" }
        require(!password.isNullOrBlank() || !privateKey.isNullOrBlank()) {
            "Either password or privateKey must be provided for SSH authentication"
        }
    }

    fun toSummary(): SshHostSummary = SshHostSummary(
        name = name,
        host = host,
        port = port,
        user = user,
        authType = if (!privateKey.isNullOrBlank()) "key" else "password",
        pinnedFingerprint = pinnedFingerprint,
    )
}

/**
 * Safe, desensitized summary of an SSH host configuration.
 */
data class SshHostSummary(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val authType: String,
    val pinnedFingerprint: String? = null,
)

/**
 * Parameters for executing a command over SSH.
 */
data class SshExecParams(
    val command: String,
    val stdin: String? = null,
    val timeoutMs: Long = 30_000,
    val background: Boolean = false,
)

/**
 * Result of an SFTP file transfer.
 */
data class SshFileResult(
    val success: Boolean,
    val path: String,
    val bytesTransferred: Long,
    val error: String? = null,
)

/**
 * Result of a remote SSH server probe (presented host key & latency).
 */
data class SshProbeResult(
    val reachable: Boolean,
    val hostKeyType: String? = null,
    val fingerprintSha256: String? = null,
    val latencyMs: Long = 0,
    val error: String? = null,
)

/**
 * Summary of an active SSH local port forwarding tunnel.
 */
data class SshTunnelInfo(
    val tunnelId: String,
    val hostName: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val active: Boolean,
)

/**
 * Abstract interface for SSH operations.
 */
interface SshExecutionService {
    fun execute(config: SshHostConfig, params: SshExecParams): ExecutionResult
    fun testConnection(config: SshHostConfig, timeoutMs: Long = 10_000): Pair<Boolean, String>
    fun probe(host: String, port: Int = 22, timeoutMs: Long = 5_000): SshProbeResult
    fun upload(config: SshHostConfig, localBytes: ByteArray, remotePath: String): SshFileResult
    fun download(config: SshHostConfig, remotePath: String, maxBytes: Long = 10 * 1024 * 1024): Pair<ByteArray?, String?>
    fun openTunnel(config: SshHostConfig, localPort: Int, remoteHost: String, remotePort: Int): Pair<String?, String?> = null to "Tunneling not supported"
    fun closeTunnel(tunnelId: String): Boolean = false
    fun listTunnels(): List<SshTunnelInfo> = emptyList()
}
