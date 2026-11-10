package com.cordiact.core.vault

import com.cordiact.core.security.AuditCategory
import com.cordiact.core.security.AuditEventDraft
import com.cordiact.core.security.AuditLog
import com.cordiact.core.security.AuditOutcome
import com.cordiact.core.security.AuditPhase
import com.cordiact.core.security.SecurityClock
import com.cordiact.core.security.TransactionalAuditLog

/**
 * Re-encrypts every credential at [fromVersion] to [toVersion], one record at a time.
 *
 * Each record update bumps its revision and is paired with an audit event. When the audit sink is
 * transactional and shares the vault database, the update and the event commit together. The old
 * key is never deleted here; a caller must keep it until every record has moved and a verified
 * backup/checkpoint exists.
 */
class CredentialVaultKeyRotation(
    private val repository: CredentialRepository,
    private val crypto: VaultCrypto,
    private val audit: AuditLog? = null,
    private val clock: SecurityClock = SecurityClock.system(),
) {
    fun rotate(scope: CredentialScope, fromVersion: Int, toVersion: Int): Int {
        require(fromVersion >= 1) { "fromVersion must be positive" }
        require(toVersion > fromVersion) { "toVersion must be greater than fromVersion" }
        require(crypto.currentKeyVersion == toVersion) {
            "crypto currentKeyVersion must equal the target version"
        }
        var rotated = 0
        repository.list(scope).filter { it.keyVersion == fromVersion }.forEach { record ->
            val transactional = audit as? TransactionalAuditLog
            if (transactional != null) {
                transactional.transaction { rotateOne(record, fromVersion, toVersion) }
            } else {
                rotateOne(record, fromVersion, toVersion)
            }
            rotated++
        }
        return rotated
    }

    private fun rotateOne(record: StoredCredential, fromVersion: Int, toVersion: Int) {
        val oldAad = CredentialAad.build(
            record.id,
            record.scope,
            record.schemaVersion,
            fromVersion,
            record.revision,
            record.type,
        )
        val metadata = crypto.decrypt(record.metadata, oldAad, fromVersion)
        val payload = crypto.decrypt(record.payload, oldAad, fromVersion)
        val nextRevision = record.revision + 1
        val newAad = CredentialAad.build(
            record.id,
            record.scope,
            record.schemaVersion,
            toVersion,
            nextRevision,
            record.type,
        )
        val updated = record.copy(
            metadata = crypto.encrypt(metadata, newAad, toVersion),
            payload = crypto.encrypt(payload, newAad, toVersion),
            keyVersion = toVersion,
            revision = nextRevision,
            updatedAt = clock.now(),
        )
        repository.update(updated, record.revision)
        audit?.append(
            AuditEventDraft(
                category = AuditCategory.SECURITY,
                phase = AuditPhase.SUCCEEDED,
                outcome = AuditOutcome.SUCCEEDED,
                reasonCode = "KEY_ROTATED",
                correlationId = "key-rotation-${record.id}-$fromVersion-$toVersion",
                workspaceId = when (val scope = record.scope) {
                    is CredentialScope.Workspace -> scope.workspaceId
                    CredentialScope.UserGlobal -> "global"
                },
                credentialOpaqueId = record.id,
                credentialRevision = nextRevision,
            ),
        )
    }
}
