package com.cordiact.app.experience.settings

import com.cordiact.core.execution.SshHostSummary
import com.cordiact.core.execution.SshProbeResult

/** The UI can submit a replacement secret, but can never retrieve one. */
interface SshSettings {
    suspend fun list(): List<SshHostSummary>
    suspend fun probe(host: String, port: Int): SshProbeResult
    suspend fun importPrivateKey(uri: android.net.Uri): String
    suspend fun update(originalName: String, draft: SshHostDraft, confirmedFingerprint: String)
    suspend fun clearImportedKey(owner: String)
    suspend fun save(draft: SshHostDraft, confirmedFingerprint: String)
    suspend fun confirmFingerprint(name: String, confirmedFingerprint: String)
    suspend fun delete(name: String)
    suspend fun test(name: String): String
}

data class SshHostDraft(val name: String, val host: String, val port: Int, val user: String,
    val password: String? = null, val privateKey: String? = null, val passphrase: String? = null, val importedKeyOwner: String? = null)
