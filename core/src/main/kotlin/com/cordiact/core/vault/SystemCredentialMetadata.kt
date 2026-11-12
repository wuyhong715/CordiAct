package com.cordiact.core.vault

/** No encrypted payloads or plaintext in this settings boundary. Null dates mean legacy/unknown. */
data class SystemCredentialMetadata(
    val id: String, val revision: Long, val updatedAtMillis: Long?, val lastUsedAtMillis: Long?,
)
interface SystemCredentialMetadataRepository {
    fun systemCredentialMetadata(): List<SystemCredentialMetadata>
    fun systemCredentialRevision(id: String): Long
    fun markSystemCredentialUsed(id: String)
}
