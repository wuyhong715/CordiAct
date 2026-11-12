package com.cordiact.app.experience.settings

import com.cordiact.app.CordiActApplication
import com.cordiact.core.execution.SshHostConfig
import com.cordiact.core.execution.SshProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSshSettings(private val app: CordiActApplication) : SshSettings {
    private val runtime get() = app.automationRuntime
    private data class Receipt(val fingerprint: String, val expires: Long)
    private val probes = mutableMapOf<Pair<String, Int>, Receipt>()
    private val audit = com.cordiact.core.settings.SettingsOperationAudit(app.credentialVaultRuntime.database)
    private fun audited(target: String, action: () -> Unit) {
        val receipt = audit.run(target, { runtime.sshHostStore.revision() }, action)
        check(receipt.completionRecorded) { "SSH_SAVED_AUDIT_INCOMPLETE_REFRESH_BEFORE_RETRY" }
    }
    override suspend fun list() = withContext(Dispatchers.IO) { runtime.sshHostStore.list() }
    override suspend fun probe(host: String, port: Int): SshProbeResult = withContext(Dispatchers.IO) {
        require(host.isNotBlank() && port in 1..65535)
        val result = runtime.sshPlugin.service.probe(host, port, 10_000)
        synchronized(probes) {
            probes.remove(host to port)
            if (result.reachable && !result.fingerprintSha256.isNullOrBlank()) {
                probes[host to port] = Receipt(result.fingerprintSha256!!, android.os.SystemClock.elapsedRealtime() + 300_000)
            }
        }
        result
    }
    private fun requireReceipt(host: String, port: Int, fingerprint: String) {
        val receipt = synchronized(probes) { probes[host to port] }
        check(receipt != null && receipt.fingerprint == fingerprint && receipt.expires > android.os.SystemClock.elapsedRealtime()) { "SSH_PROBE_REQUIRED" }
    }
    private val imported = mutableSetOf<String>()
    override suspend fun importPrivateKey(uri: android.net.Uri): String = withContext(Dispatchers.IO) {
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 256 * 1024) { "SSH_KEY_TOO_LARGE" }
                output.write(buffer, 0, count)
            }
            buffer.fill(0)
            output.toByteArray() } ?: error("SSH_KEY_IMPORT_FAILED")
        require(bytes.size <= 256 * 1024) { "SSH_KEY_TOO_LARGE" }
        val text = bytes.toString(Charsets.UTF_8)
        bytes.fill(0)
        require(text.contains("PRIVATE KEY")) { "SSH_KEY_FORMAT_INVALID" }
        val owner = "import-" + java.util.UUID.randomUUID()
        com.cordiact.app.security.AndroidSystemCredentials.get(app).set("ssh", owner, text)
        synchronized(imported) { imported += owner }
        owner
    }
    override suspend fun clearImportedKey(owner: String) = withContext(Dispatchers.IO) {
        if (synchronized(imported) { owner in imported }) {
            com.cordiact.app.security.AndroidSystemCredentials.get(app).set("ssh", owner, null)
            synchronized(imported) { imported.remove(owner) }
        }
    }
    private fun privateKey(draft: SshHostDraft): String? = draft.importedKeyOwner?.let { owner ->
        require(synchronized(imported) { owner in imported }) { "SSH_IMPORT_INVALID" }
        com.cordiact.app.security.AndroidSystemCredentials.get(app).read("ssh", owner) ?: error("SSH_IMPORTED_KEY_MISSING")
    } ?: draft.privateKey
    override suspend fun update(originalName: String, draft: SshHostDraft, confirmedFingerprint: String) = withContext(Dispatchers.IO) {
        requireReceipt(draft.host, draft.port, confirmedFingerprint)
        require(draft.name == originalName) { "SSH_RENAME_NOT_SUPPORTED" }
        val previous = runtime.sshHostStore.get(originalName) ?: error("SSH_HOST_MISSING")
        val newKey = privateKey(draft)
        val replaceAuth = !draft.password.isNullOrBlank() || !newKey.isNullOrBlank()
        audited("ssh.host.save") {
            runtime.sshHostStore.save(SshHostConfig(draft.name, draft.host, draft.port, draft.user,
                if (replaceAuth) draft.password else previous.password,
                if (replaceAuth) newKey else previous.privateKey,
                if (replaceAuth) draft.passphrase else previous.passphrase, confirmedFingerprint))
        }
        draft.importedKeyOwner?.let { clearImportedKey(it) }
        Unit
    }
    override suspend fun save(draft: SshHostDraft, confirmedFingerprint: String) = withContext(Dispatchers.IO) {
        requireReceipt(draft.host, draft.port, confirmedFingerprint)
        check(runtime.sshHostStore.list().none { it.name == draft.name }) { "SSH_NAME_EXISTS" }
        audited("ssh.host.save") {
            runtime.sshHostStore.save(SshHostConfig(draft.name, draft.host, draft.port, draft.user,
                draft.password, privateKey(draft), draft.passphrase, confirmedFingerprint))
        }
        draft.importedKeyOwner?.let { clearImportedKey(it) }
        Unit
    }
    override suspend fun confirmFingerprint(name: String, confirmedFingerprint: String) = withContext(Dispatchers.IO) {
        val summary = runtime.sshHostStore.list().single { it.name == name }
        requireReceipt(summary.host, summary.port, confirmedFingerprint)
        audited("ssh.host.pin") { runtime.sshHostStore.pin(name, confirmedFingerprint) }
    }
    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        audited("ssh.host.delete") { check(runtime.sshHostStore.delete(name)) { "SSH_HOST_MISSING" } }
    }
    override suspend fun test(name: String): String = withContext(Dispatchers.IO) {
        val config = runtime.sshHostStore.get(name) ?: error("SSH_HOST_MISSING")
        val (success, _) = runtime.sshPlugin.service.testConnection(config, 10_000)
        check(success) { "SSH_CONNECTION_FAILED_VERIFY_PIN_AND_CREDENTIAL" }
        "已通过固定指纹及认证检查"
    }
}
