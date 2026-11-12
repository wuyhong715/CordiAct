package com.cordiact.app.security

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cordiact.core.security.AccessEnvelope
import com.cordiact.core.security.ApprovalAuthority
import com.cordiact.core.security.AuditCategory
import com.cordiact.core.security.AuditEvent
import com.cordiact.core.security.AuditEventDraft
import com.cordiact.core.security.AuditFilter
import com.cordiact.core.security.AuditLog
import com.cordiact.core.security.AuditOutcome
import com.cordiact.core.security.AuditPhase
import com.cordiact.core.security.AuditSigner
import com.cordiact.core.security.AuditUnavailableException
import com.cordiact.core.security.AuditVerification
import com.cordiact.core.security.TransactionalAuditLog
import com.cordiact.core.security.PermissionPreset
import com.cordiact.core.security.Principal
import com.cordiact.core.security.PrincipalType
import com.cordiact.core.security.SecurityClock
import com.cordiact.core.vault.CredentialRepository
import com.cordiact.core.vault.CredentialScope
import com.cordiact.core.vault.CredentialType
import com.cordiact.core.vault.EncryptedValue
import com.cordiact.core.vault.StaleRevisionException
import com.cordiact.core.vault.StoredCredential
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * One private, no-backup SQLite database holding both encrypted credentials and the mandatory
 * audit chain. Keeping them in one database lets a local credential mutation and its success
 * event commit in the same transaction (the gate calls the audit sink before/around the mutation).
 *
 * The database file lives under noBackupFilesDir/security/vault.db. WAL, SHM and temporary files
 * created by SQLite stay in the same directory.
 */
class AndroidSecurityDatabase private constructor(
    context: Context,
    databaseName: String,
    private val signer: AuditSigner,
    private val clock: SecurityClock,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION),
    CredentialRepository,
    com.cordiact.core.vault.SystemCredentialRepository,
    com.cordiact.core.vault.SystemCredentialMetadataRepository,
    TransactionalAuditLog {

    constructor(
        context: Context,
        signer: AuditSigner,
        clock: SecurityClock = SecurityClock.system(),
    ) : this(
        context = context,
        databaseName = File(context.noBackupFilesDir, "security/vault.db")
            .apply { parentFile?.mkdirs() }
            .absolutePath,
        signer = signer,
        clock = clock,
    )

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSystemCredentials(db)
        db.execSQL(
            """
            CREATE TABLE $TABLE_CREDENTIALS (
                $COL_ID TEXT PRIMARY KEY NOT NULL,
                $COL_SCOPE_KEY TEXT NOT NULL,
                $COL_SCOPE_WORKSPACE TEXT,
                $COL_TYPE TEXT NOT NULL,
                $COL_META_NONCE BLOB NOT NULL,
                $COL_META_CIPHERTEXT BLOB NOT NULL,
                $COL_PAYLOAD_NONCE BLOB NOT NULL,
                $COL_PAYLOAD_CIPHERTEXT BLOB NOT NULL,
                $COL_REVISION INTEGER NOT NULL,
                $COL_SCHEMA_VERSION INTEGER NOT NULL,
                $COL_KEY_VERSION INTEGER NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                $COL_EXPIRES_AT INTEGER,
                $COL_DISABLED_AT INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_credentials_scope ON $TABLE_CREDENTIALS($COL_SCOPE_KEY, $COL_SCOPE_WORKSPACE)")
        db.execSQL(
            """
            CREATE TABLE $TABLE_AUDIT (
                $COL_AUDIT_EVENT_ID TEXT PRIMARY KEY NOT NULL,
                $COL_AUDIT_SEQUENCE INTEGER NOT NULL,
                $COL_AUDIT_OCCURRED_AT INTEGER NOT NULL,
                $COL_AUDIT_BOOT_INSTANCE TEXT NOT NULL,
                $COL_AUDIT_CATEGORY TEXT NOT NULL,
                $COL_AUDIT_PHASE TEXT NOT NULL,
                $COL_AUDIT_OUTCOME TEXT NOT NULL,
                $COL_AUDIT_REASON_CODE TEXT NOT NULL,
                $COL_AUDIT_CORRELATION_ID TEXT NOT NULL,
                $COL_AUDIT_WORKSPACE_ID TEXT NOT NULL,
                $COL_AUDIT_RUN_ID TEXT,
                $COL_AUDIT_JSON TEXT NOT NULL,
                $COL_AUDIT_PREV_MAC TEXT,
                $COL_AUDIT_EVENT_MAC TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_audit_workspace ON $TABLE_AUDIT($COL_AUDIT_WORKSPACE_ID, $COL_AUDIT_SEQUENCE)")
        db.execSQL("CREATE INDEX idx_audit_correlation ON $TABLE_AUDIT($COL_AUDIT_CORRELATION_ID, $COL_AUDIT_SEQUENCE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSystemCredentials(db)
        if (oldVersion < 3) createSystemCredentialMetadata(db)
    }

    private fun createSystemCredentials(db: SQLiteDatabase) {
        createSystemCredentialMetadata(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS system_credentials (id TEXT PRIMARY KEY NOT NULL, nonce BLOB NOT NULL, ciphertext BLOB NOT NULL, key_version INTEGER NOT NULL)")
    }

    private fun createSystemCredentialMetadata(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS system_credential_metadata (id TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL, updated_at INTEGER, last_used_at INTEGER)")
    }

    override fun systemCredentialMetadata(): List<com.cordiact.core.vault.SystemCredentialMetadata> =
        readableDatabase.rawQuery("SELECT c.id, COALESCE(m.revision,0), m.updated_at, m.last_used_at FROM system_credentials c LEFT JOIN system_credential_metadata m ON m.id=c.id ORDER BY c.id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(com.cordiact.core.vault.SystemCredentialMetadata(
                cursor.getString(0), cursor.getLong(1), if (cursor.isNull(2)) null else cursor.getLong(2),
                if (cursor.isNull(3)) null else cursor.getLong(3))) }
        }
    override fun systemCredentialRevision(id: String): Long = readableDatabase.rawQuery(
        "SELECT revision FROM system_credential_metadata WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else 0 }
    override fun markSystemCredentialUsed(id: String) = transaction {
        // Upgraded v2 records have no metadata row. Record usage without inventing an update time or rotating revision.
        writableDatabase.execSQL("INSERT OR IGNORE INTO system_credential_metadata(id,revision,updated_at,last_used_at) SELECT id,0,NULL,NULL FROM system_credentials WHERE id=?", arrayOf<Any>(id))
        writableDatabase.execSQL("UPDATE system_credential_metadata SET last_used_at=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), id))
    }
    private fun bumpSystemCredentialRevision(id: String) {
        val revision = systemCredentialRevision(id)
        check(revision < Long.MAX_VALUE) { "CREDENTIAL_REVISION_EXHAUSTED" }
        val values = ContentValues().apply {
            put("id", id); put("revision", revision + 1); put("updated_at", System.currentTimeMillis())
            putNull("last_used_at")
        }
        check(writableDatabase.insertWithOnConflict("system_credential_metadata", null, values,
            SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "CREDENTIAL_METADATA_WRITE_FAILED" }
    }

    override fun readSystemCredential(id: String): com.cordiact.core.vault.SystemCredentialRecord? =
        readableDatabase.query("system_credentials", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) null else com.cordiact.core.vault.SystemCredentialRecord(
                id,
                com.cordiact.core.vault.EncryptedValue(cursor.getBlob(cursor.getColumnIndexOrThrow("nonce")), cursor.getBlob(cursor.getColumnIndexOrThrow("ciphertext"))),
                cursor.getInt(cursor.getColumnIndexOrThrow("key_version")),
            )
        }

    override fun writeSystemCredential(record: com.cordiact.core.vault.SystemCredentialRecord) = transaction {
        val values = ContentValues().apply {
            put("id", record.id); put("nonce", record.encrypted.nonce)
            put("ciphertext", record.encrypted.ciphertext); put("key_version", record.keyVersion)
        }
        check(writableDatabase.insertWithOnConflict("system_credentials", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "SYSTEM_CREDENTIAL_WRITE_FAILED" }
        bumpSystemCredentialRevision(record.id)
    }

    override fun deleteSystemCredential(id: String) = transaction {
        writableDatabase.delete("system_credentials", "id = ?", arrayOf(id))
        bumpSystemCredentialRevision(id)
    }

    // ---------------------------------------------------------------- credentials

    override fun list(scope: CredentialScope): List<StoredCredential> {
        val selection: String
        val args: Array<String>
        when (scope) {
            is CredentialScope.Workspace -> {
                selection = "$COL_SCOPE_KEY = ? AND $COL_SCOPE_WORKSPACE = ?"
                args = arrayOf("workspace", scope.workspaceId)
            }
            CredentialScope.UserGlobal -> {
                selection = "$COL_SCOPE_KEY = ?"
                args = arrayOf("global")
            }
        }
        return readableDatabase.query(
            TABLE_CREDENTIALS,
            CREDENTIAL_COLUMNS,
            selection,
            args,
            null,
            null,
            "$COL_UPDATED_AT DESC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toCredential()) } }
    }

    override fun find(id: String): StoredCredential? = readableDatabase.query(
        TABLE_CREDENTIALS,
        CREDENTIAL_COLUMNS,
        "$COL_ID = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toCredential() else null }

    override fun insert(record: StoredCredential): StoredCredential {
        writableDatabase.insertOrThrow(TABLE_CREDENTIALS, null, record.toValues())
        return record
    }

    override fun update(record: StoredCredential, expectedRevision: Long): StoredCredential {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.query(
                TABLE_CREDENTIALS,
                arrayOf(COL_REVISION),
                "$COL_ID = ?",
                arrayOf(record.id),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                ?: throw com.cordiact.core.vault.CredentialNotFoundException(record.id)
            if (current != expectedRevision) throw StaleRevisionException(expectedRevision, current)
            val updated = db.update(
                TABLE_CREDENTIALS,
                record.toValues(),
                "$COL_ID = ? AND $COL_REVISION = ?",
                arrayOf(record.id, expectedRevision.toString()),
            )
            if (updated != 1) throw StaleRevisionException(expectedRevision, current)
            db.setTransactionSuccessful()
            return record
        } finally {
            db.endTransaction()
        }
    }

    override fun delete(id: String, expectedRevision: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.query(
                TABLE_CREDENTIALS,
                arrayOf(COL_REVISION),
                "$COL_ID = ?",
                arrayOf(id),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                ?: return false
            if (current != expectedRevision) throw StaleRevisionException(expectedRevision, current)
            val deleted = db.delete(
                TABLE_CREDENTIALS,
                "$COL_ID = ? AND $COL_REVISION = ?",
                arrayOf(id, expectedRevision.toString()),
            )
            db.setTransactionSuccessful()
            return deleted == 1
        } finally {
            db.endTransaction()
        }
    }

    override fun all(): List<StoredCredential> = readableDatabase.query(
        TABLE_CREDENTIALS,
        CREDENTIAL_COLUMNS,
        null,
        null,
        null,
        null,
        "$COL_CREATED_AT ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toCredential()) } }

    private fun StoredCredential.toValues(): ContentValues = ContentValues().apply {
        put(COL_ID, id)
        when (val scope = scope) {
            is CredentialScope.Workspace -> {
                put(COL_SCOPE_KEY, "workspace")
                put(COL_SCOPE_WORKSPACE, scope.workspaceId)
            }
            CredentialScope.UserGlobal -> {
                put(COL_SCOPE_KEY, "global")
                putNull(COL_SCOPE_WORKSPACE)
            }
        }
        put(COL_TYPE, type.name)
        put(COL_META_NONCE, metadata.nonce)
        put(COL_META_CIPHERTEXT, metadata.ciphertext)
        put(COL_PAYLOAD_NONCE, payload.nonce)
        put(COL_PAYLOAD_CIPHERTEXT, payload.ciphertext)
        put(COL_REVISION, revision)
        put(COL_SCHEMA_VERSION, schemaVersion)
        put(COL_KEY_VERSION, keyVersion)
        put(COL_CREATED_AT, createdAt.toEpochMilli())
        put(COL_UPDATED_AT, updatedAt.toEpochMilli())
        val expiry = expiresAt
        if (expiry == null) putNull(COL_EXPIRES_AT) else put(COL_EXPIRES_AT, expiry.toEpochMilli())
        val disabled = disabledAt
        if (disabled == null) putNull(COL_DISABLED_AT) else put(COL_DISABLED_AT, disabled.toEpochMilli())
    }

    private fun android.database.Cursor.toCredential(): StoredCredential {
        val scopeKey = getString(getColumnIndexOrThrow(COL_SCOPE_KEY))
        val scope = if (scopeKey == "global") {
            CredentialScope.UserGlobal
        } else {
            CredentialScope.Workspace(getString(getColumnIndexOrThrow(COL_SCOPE_WORKSPACE)))
        }
        return StoredCredential(
            id = getString(getColumnIndexOrThrow(COL_ID)),
            scope = scope,
            type = CredentialType.valueOf(getString(getColumnIndexOrThrow(COL_TYPE))),
            metadata = EncryptedValue(
                nonce = getBlob(getColumnIndexOrThrow(COL_META_NONCE)),
                ciphertext = getBlob(getColumnIndexOrThrow(COL_META_CIPHERTEXT)),
            ),
            payload = EncryptedValue(
                nonce = getBlob(getColumnIndexOrThrow(COL_PAYLOAD_NONCE)),
                ciphertext = getBlob(getColumnIndexOrThrow(COL_PAYLOAD_CIPHERTEXT)),
            ),
            revision = getLong(getColumnIndexOrThrow(COL_REVISION)),
            schemaVersion = getInt(getColumnIndexOrThrow(COL_SCHEMA_VERSION)),
            keyVersion = getInt(getColumnIndexOrThrow(COL_KEY_VERSION)),
            createdAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow(COL_CREATED_AT))),
            updatedAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow(COL_UPDATED_AT))),
            expiresAt = getNullableLong(COL_EXPIRES_AT)?.let(Instant::ofEpochMilli),
            disabledAt = getNullableLong(COL_DISABLED_AT)?.let(Instant::ofEpochMilli),
        )
    }

    private fun android.database.Cursor.getNullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    // ---------------------------------------------------------------- audit

    private val auditLock = Any()

    override fun <T> transaction(block: () -> T): T = synchronized(auditLock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val value = block()
            db.setTransactionSuccessful()
            value
        } finally {
            db.endTransaction()
        }
    }

    override fun append(draft: AuditEventDraft): AuditEvent {
        require(draft.workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        synchronized(auditLock) {
            val db = writableDatabase
            if (db.inTransaction()) {
                return appendInternal(db, draft)
            }
            db.beginTransaction()
            try {
                val event = appendInternal(db, draft)
                db.setTransactionSuccessful()
                return event
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun appendInternal(db: SQLiteDatabase, draft: AuditEventDraft): AuditEvent {
        val nextSequence = db.rawQuery(
            "SELECT COALESCE(MAX($COL_AUDIT_SEQUENCE), 0) + 1 FROM $TABLE_AUDIT",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 1L }
        val previousMac = db.query(
            TABLE_AUDIT,
            arrayOf(COL_AUDIT_EVENT_MAC),
            null,
            null,
            null,
            null,
            "$COL_AUDIT_SEQUENCE DESC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val occurredAt = clock.now()
        val unsigned = AuditEvent(
            eventId = java.util.UUID.randomUUID().toString(),
            sequence = nextSequence,
            occurredAt = occurredAt,
            bootInstance = bootInstance(),
            draft = draft,
            prevMac = previousMac,
            eventMac = "",
        )
        val mac = try {
            signer.sign(previousMac, unsigned.canonicalPayload())
        } catch (failure: Throwable) {
            throw AuditUnavailableException("AUDIT_SIGNING_FAILED", cause = failure)
        }
        val event = unsigned.copy(eventMac = mac)
        val values = ContentValues().apply {
            put(COL_AUDIT_EVENT_ID, event.eventId)
            put(COL_AUDIT_SEQUENCE, event.sequence)
            put(COL_AUDIT_OCCURRED_AT, event.occurredAt.toEpochMilli())
            put(COL_AUDIT_BOOT_INSTANCE, event.bootInstance)
            put(COL_AUDIT_CATEGORY, event.draft.category.name)
            put(COL_AUDIT_PHASE, event.draft.phase.name)
            put(COL_AUDIT_OUTCOME, event.draft.outcome.name)
            put(COL_AUDIT_REASON_CODE, event.draft.reasonCode)
            put(COL_AUDIT_CORRELATION_ID, event.draft.correlationId)
            put(COL_AUDIT_WORKSPACE_ID, event.draft.workspaceId)
            if (event.draft.runId == null) putNull(COL_AUDIT_RUN_ID) else put(COL_AUDIT_RUN_ID, event.draft.runId)
            put(COL_AUDIT_JSON, AuditJson.encode(event.draft))
            if (event.prevMac == null) putNull(COL_AUDIT_PREV_MAC) else put(COL_AUDIT_PREV_MAC, event.prevMac)
            put(COL_AUDIT_EVENT_MAC, event.eventMac)
        }
        db.insertOrThrow(TABLE_AUDIT, null, values)
        return event
    }

    override fun query(filter: AuditFilter): List<AuditEvent> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        filter.workspaceId?.let { clauses += "$COL_AUDIT_WORKSPACE_ID = ?"; args += it }
        filter.correlationId?.let { clauses += "$COL_AUDIT_CORRELATION_ID = ?"; args += it }
        filter.category?.let { clauses += "$COL_AUDIT_CATEGORY = ?"; args += it.name }
        filter.phase?.let { clauses += "$COL_AUDIT_PHASE = ?"; args += it.name }
        filter.outcome?.let { clauses += "$COL_AUDIT_OUTCOME = ?"; args += it.name }
        filter.since?.let { clauses += "$COL_AUDIT_OCCURRED_AT >= ?"; args += it.toEpochMilli().toString() }
        val selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        return readableDatabase.query(
            TABLE_AUDIT,
            AUDIT_COLUMNS,
            selection,
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null,
            null,
            "$COL_AUDIT_SEQUENCE ${if (filter.newestFirst) "DESC" else "ASC"}",
            filter.limit.toString(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toAuditEvent()) } }
    }

    override fun verify(): AuditVerification {
        val events = readableDatabase.query(
            TABLE_AUDIT,
            AUDIT_COLUMNS,
            null,
            null,
            null,
            null,
            "$COL_AUDIT_SEQUENCE ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toAuditEvent()) } }
        var previous: String? = null
        events.forEach { event ->
            val expected = try {
                signer.sign(previous, event.copy(eventMac = "").canonicalPayload())
            } catch (_: Throwable) {
                return AuditVerification(false, events.size, event.sequence)
            }
            if (event.prevMac != previous || event.eventMac != expected) {
                return AuditVerification(false, events.size, event.sequence)
            }
            previous = event.eventMac
        }
        return AuditVerification(true, events.size)
    }

    private fun android.database.Cursor.toAuditEvent(): AuditEvent = AuditEvent(
        eventId = getString(getColumnIndexOrThrow(COL_AUDIT_EVENT_ID)),
        sequence = getLong(getColumnIndexOrThrow(COL_AUDIT_SEQUENCE)),
        occurredAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow(COL_AUDIT_OCCURRED_AT))),
        bootInstance = getString(getColumnIndexOrThrow(COL_AUDIT_BOOT_INSTANCE)),
        draft = AuditJson.decode(getString(getColumnIndexOrThrow(COL_AUDIT_JSON))),
        prevMac = getColumnIndexOrThrow(COL_AUDIT_PREV_MAC).let { if (isNull(it)) null else getString(it) },
        eventMac = getString(getColumnIndexOrThrow(COL_AUDIT_EVENT_MAC)),
    )

    private fun bootInstance(): String {
        // One process-level value, stable for the lifetime of this helper.
        return bootInstance ?: java.util.UUID.randomUUID().toString().also { bootInstance = it }
    }

    private var bootInstance: String? = null

    private companion object {
        const val DATABASE_VERSION = 3
        const val TABLE_CREDENTIALS = "credentials"
        const val TABLE_AUDIT = "audit_events"

        const val COL_ID = "id"
        const val COL_SCOPE_KEY = "scope_key"
        const val COL_SCOPE_WORKSPACE = "scope_workspace_id"
        const val COL_TYPE = "type"
        const val COL_META_NONCE = "metadata_nonce"
        const val COL_META_CIPHERTEXT = "metadata_ciphertext"
        const val COL_PAYLOAD_NONCE = "payload_nonce"
        const val COL_PAYLOAD_CIPHERTEXT = "payload_ciphertext"
        const val COL_REVISION = "revision"
        const val COL_SCHEMA_VERSION = "schema_version"
        const val COL_KEY_VERSION = "key_version"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
        const val COL_EXPIRES_AT = "expires_at"
        const val COL_DISABLED_AT = "disabled_at"

        const val COL_AUDIT_EVENT_ID = "event_id"
        const val COL_AUDIT_SEQUENCE = "sequence"
        const val COL_AUDIT_OCCURRED_AT = "occurred_at"
        const val COL_AUDIT_BOOT_INSTANCE = "boot_instance"
        const val COL_AUDIT_CATEGORY = "category"
        const val COL_AUDIT_PHASE = "phase"
        const val COL_AUDIT_OUTCOME = "outcome"
        const val COL_AUDIT_REASON_CODE = "reason_code"
        const val COL_AUDIT_CORRELATION_ID = "correlation_id"
        const val COL_AUDIT_WORKSPACE_ID = "workspace_id"
        const val COL_AUDIT_RUN_ID = "run_id"
        const val COL_AUDIT_JSON = "event_json"
        const val COL_AUDIT_PREV_MAC = "prev_mac"
        const val COL_AUDIT_EVENT_MAC = "event_mac"

        val CREDENTIAL_COLUMNS = arrayOf(
            COL_ID, COL_SCOPE_KEY, COL_SCOPE_WORKSPACE, COL_TYPE,
            COL_META_NONCE, COL_META_CIPHERTEXT, COL_PAYLOAD_NONCE, COL_PAYLOAD_CIPHERTEXT,
            COL_REVISION, COL_SCHEMA_VERSION, COL_KEY_VERSION,
            COL_CREATED_AT, COL_UPDATED_AT, COL_EXPIRES_AT, COL_DISABLED_AT,
        )

        val AUDIT_COLUMNS = arrayOf(
            COL_AUDIT_EVENT_ID, COL_AUDIT_SEQUENCE, COL_AUDIT_OCCURRED_AT, COL_AUDIT_BOOT_INSTANCE,
            COL_AUDIT_JSON, COL_AUDIT_PREV_MAC, COL_AUDIT_EVENT_MAC,
        )
    }
}

private object AuditJson {
    fun encode(draft: AuditEventDraft): String {
        val objectValue = JSONObject()
        objectValue.put("category", draft.category.name)
        objectValue.put("phase", draft.phase.name)
        objectValue.put("outcome", draft.outcome.name)
        objectValue.put("reasonCode", draft.reasonCode)
        objectValue.put("correlationId", draft.correlationId)
        objectValue.putOpt("invocationId", draft.invocationId)
        objectValue.putOpt("approvalId", draft.approvalId)
        objectValue.putOpt("attemptId", draft.attemptId)
        objectValue.put("principalChain", JSONArray().apply {
            draft.principalChain.forEach { principal ->
                put(JSONObject().put("id", principal.id).put("type", principal.type.name))
            }
        })
        objectValue.put("workspaceId", draft.workspaceId)
        objectValue.putOpt("sessionId", draft.sessionId)
        objectValue.putOpt("runId", draft.runId)
        objectValue.putOpt("turnId", draft.turnId)
        objectValue.putOpt("toolCallId", draft.toolCallId)
        objectValue.putOpt("toolId", draft.toolId)
        objectValue.putOpt("policyVersion", draft.policyVersion)
        draft.policyEpoch?.let { objectValue.put("policyEpoch", it) }
        objectValue.putOpt("permissionPreset", draft.permissionPreset?.name)
        objectValue.putOpt("baseEnvelope", draft.baseEnvelope?.name)
        objectValue.putOpt("approvalAuthority", draft.approvalAuthority?.name)
        objectValue.putOpt("reviewerId", draft.reviewerId)
        draft.reviewerConfigRevision?.let { objectValue.put("reviewerConfigRevision", it) }
        objectValue.putOpt("reviewRequestId", draft.reviewRequestId)
        objectValue.putOpt("credentialOpaqueId", draft.credentialOpaqueId)
        draft.credentialRevision?.let { objectValue.put("credentialRevision", it) }
        objectValue.putOpt("targetDescriptorSanitized", draft.targetDescriptorSanitized)
        objectValue.putOpt("receiverDescriptorSanitized", draft.receiverDescriptorSanitized)
        return objectValue.toString()
    }

    fun decode(json: String): AuditEventDraft {
        val value = JSONObject(json)
        val principals = value.optJSONArray("principalChain") ?: JSONArray()
        val chain = buildList {
            for (index in 0 until principals.length()) {
                val principal = principals.getJSONObject(index)
                add(Principal(principal.getString("id"), PrincipalType.valueOf(principal.getString("type"))))
            }
        }
        return AuditEventDraft(
            category = AuditCategory.valueOf(value.getString("category")),
            phase = AuditPhase.valueOf(value.getString("phase")),
            outcome = AuditOutcome.valueOf(value.getString("outcome")),
            reasonCode = value.optString("reasonCode", ""),
            correlationId = value.getString("correlationId"),
            invocationId = value.optNullableString("invocationId"),
            approvalId = value.optNullableString("approvalId"),
            attemptId = value.optNullableString("attemptId"),
            principalChain = chain,
            workspaceId = value.getString("workspaceId"),
            sessionId = value.optNullableString("sessionId"),
            runId = value.optNullableString("runId"),
            turnId = value.optNullableString("turnId"),
            toolCallId = value.optNullableString("toolCallId"),
            toolId = value.optNullableString("toolId"),
            policyVersion = value.optNullableString("policyVersion"),
            policyEpoch = value.optNullableLong("policyEpoch"),
            permissionPreset = value.optNullableString("permissionPreset")?.let(PermissionPreset::valueOf),
            baseEnvelope = value.optNullableString("baseEnvelope")?.let(AccessEnvelope::valueOf),
            approvalAuthority = value.optNullableString("approvalAuthority")?.let(ApprovalAuthority::valueOf),
            reviewerId = value.optNullableString("reviewerId"),
            reviewerConfigRevision = value.optNullableLong("reviewerConfigRevision"),
            reviewRequestId = value.optNullableString("reviewRequestId"),
            credentialOpaqueId = value.optNullableString("credentialOpaqueId"),
            credentialRevision = value.optNullableLong("credentialRevision"),
            targetDescriptorSanitized = value.optNullableString("targetDescriptorSanitized"),
            receiverDescriptorSanitized = value.optNullableString("receiverDescriptorSanitized"),
        )
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else getLong(name)
}
