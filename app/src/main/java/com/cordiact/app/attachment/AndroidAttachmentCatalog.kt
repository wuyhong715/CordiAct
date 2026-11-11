package com.cordiact.app.attachment

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_ATTACHMENT_ID
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_BLOB_KEY
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_BYTE_SIZE
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_CREATED_AT
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_DISPLAY_NAME
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_EXTRACTOR_VERSION
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_HEIGHT
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_MIME_TYPE
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_PAGE_COUNT
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_RENDITION_ID
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_SHA256
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_STATE
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_TRANSFORM_JSON
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_VERIFIED_AT
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_WIDTH
import com.cordiact.app.attachment.AttachmentDatabaseSchema.COL_WORKSPACE_ID
import com.cordiact.app.attachment.AttachmentDatabaseSchema.TABLE_ATTACHMENTS
import com.cordiact.app.attachment.AttachmentDatabaseSchema.TABLE_RENDITIONS
import com.cordiact.core.attachment.AttachmentAuditAction
import com.cordiact.core.attachment.AttachmentAuditDecision
import com.cordiact.core.attachment.AttachmentAuditEvent
import com.cordiact.core.attachment.AttachmentAuditSink
import com.cordiact.core.attachment.NoopAttachmentAuditSink
import com.cordiact.core.attachment.AttachmentDescriptor
import com.cordiact.core.attachment.AttachmentErrorCode
import com.cordiact.core.attachment.AttachmentException
import com.cordiact.core.attachment.AttachmentId
import com.cordiact.core.attachment.AttachmentRendition
import com.cordiact.core.attachment.AttachmentState
import com.cordiact.core.attachment.AttachmentBlobStore
import com.cordiact.core.attachment.AttachmentCatalog
import com.cordiact.core.attachment.AttachmentReferenceIndex
import java.io.InputStream
import java.security.MessageDigest

/**
 * SQLite metadata over [AttachmentBlobStore] bytes.
 *
 * Every query is scoped by `workspace_id`; a guessed attachment id from another Workspace resolves
 * to nothing. The blob key never leaves this class.
 */
class AndroidAttachmentCatalog(
    private val helper: SQLiteOpenHelper,
    private val blobs: AttachmentBlobStore,
    private val audit: AttachmentAuditSink = NoopAttachmentAuditSink,
    private val now: () -> Long = System::currentTimeMillis,
) : AttachmentCatalog, AttachmentReferenceIndex {

    private fun database(): SQLiteDatabase = helper.writableDatabase

    override fun find(workspaceId: String, id: AttachmentId): AttachmentDescriptor? =
        database().query(
            TABLE_ATTACHMENTS,
            ATTACHMENT_COLUMNS,
            "$COL_WORKSPACE_ID=? AND $COL_ATTACHMENT_ID=?",
            arrayOf(workspaceId, id.value),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toDescriptor() else null }

    override fun list(workspaceId: String): List<AttachmentDescriptor> =
        database().query(
            TABLE_ATTACHMENTS,
            ATTACHMENT_COLUMNS,
            "$COL_WORKSPACE_ID=?",
            arrayOf(workspaceId),
            null,
            null,
            "$COL_CREATED_AT ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toDescriptor())
            }
        }

    override fun rendition(
        workspaceId: String,
        attachmentId: AttachmentId,
        renditionId: String,
    ): AttachmentRendition? = database().query(
        "$TABLE_RENDITIONS r JOIN $TABLE_ATTACHMENTS a ON a.$COL_ATTACHMENT_ID = r.$COL_ATTACHMENT_ID",
        arrayOf(COL_RENDITION_ID, COL_ATTACHMENT_ID, COL_MIME_TYPE, COL_BYTE_SIZE, COL_SHA256, COL_TRANSFORM_JSON, COL_EXTRACTOR_VERSION)
            .let { columns -> Array<String>(columns.size) { "r.${columns[it]}" } },
        "a.$COL_WORKSPACE_ID=? AND r.$COL_ATTACHMENT_ID=? AND r.$COL_RENDITION_ID=?",
        arrayOf(workspaceId, attachmentId.value, renditionId),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        AttachmentRendition(
            id = cursor.getString(0),
            attachmentId = AttachmentId(cursor.getString(1)),
            mimeType = cursor.getString(2),
            byteSize = cursor.getLong(3),
            sha256 = cursor.getString(4),
            transformJson = cursor.stringOrNull(5),
            extractorVersion = cursor.stringOrNull(6),
        )
    }

    override fun open(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): InputStream {
        val blobKey = blobKeyFor(workspaceId, attachmentId, renditionId)
        if (blobKey == null) {
            recordAudit(
                action = AttachmentAuditAction.READ,
                decision = AttachmentAuditDecision.DENIED,
                workspaceId = workspaceId,
                attachmentId = attachmentId.value,
                renditionId = renditionId,
                reasonCode = AttachmentErrorCode.UNKNOWN_ATTACHMENT.name,
                bestEffort = true,
            )
            throw AttachmentException(
                AttachmentErrorCode.UNKNOWN_ATTACHMENT,
                "Attachment ${attachmentId.value} is not stored in this Workspace",
            )
        }
        // A read is a data access. If it cannot be audited, the bytes are not handed out.
        recordAudit(
            action = AttachmentAuditAction.READ,
            decision = AttachmentAuditDecision.ALLOWED,
            workspaceId = workspaceId,
            attachmentId = attachmentId.value,
            renditionId = renditionId,
            mimeType = mimeFor(workspaceId, attachmentId, renditionId),
            sha256 = digestRefFor(workspaceId, attachmentId, renditionId)?.sha256,
            bestEffort = false,
        )
        return blobs.open(workspaceId, blobKey)
    }

    override fun verify(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): Boolean {
        val ref = digestRefFor(workspaceId, attachmentId, renditionId) ?: return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            blobs.open(workspaceId, ref.blobKey).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == ref.sha256
        } catch (_: Exception) {
            false
        }
    }

    override fun upsert(descriptor: AttachmentDescriptor, blobKey: String) {
        val db = database()
        val owner = db.query(
            TABLE_ATTACHMENTS,
            arrayOf(COL_WORKSPACE_ID),
            "$COL_ATTACHMENT_ID=?",
            arrayOf(descriptor.id.value),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (owner != null && owner != descriptor.workspaceId) {
            throw AttachmentException(
                AttachmentErrorCode.CROSS_WORKSPACE,
                "Attachment ${descriptor.id.value} belongs to another Workspace",
            )
        }
        val values = ContentValues().apply {
            put(COL_ATTACHMENT_ID, descriptor.id.value)
            put(COL_WORKSPACE_ID, descriptor.workspaceId)
            put(COL_BLOB_KEY, blobKey)
            put(COL_MIME_TYPE, descriptor.mimeType)
            put(COL_DISPLAY_NAME, descriptor.displayName)
            put(COL_BYTE_SIZE, descriptor.byteSize)
            put(COL_SHA256, descriptor.sha256)
            put(COL_STATE, descriptor.state.name)
            put(COL_CREATED_AT, descriptor.createdAtMillis)
            descriptor.verifiedAtMillis?.let { put(COL_VERIFIED_AT, it) } ?: putNull(COL_VERIFIED_AT)
            descriptor.width?.let { put(COL_WIDTH, it) } ?: putNull(COL_WIDTH)
            descriptor.height?.let { put(COL_HEIGHT, it) } ?: putNull(COL_HEIGHT)
            descriptor.pageCount?.let { put(COL_PAGE_COUNT, it) } ?: putNull(COL_PAGE_COUNT)
        }
        val updated = db.update(
            TABLE_ATTACHMENTS,
            values,
            "$COL_WORKSPACE_ID=? AND $COL_ATTACHMENT_ID=?",
            arrayOf(descriptor.workspaceId, descriptor.id.value),
        )
        if (updated == 0) db.insertOrThrow(TABLE_ATTACHMENTS, null, values)
    }

    override fun upsertRendition(workspaceId: String, rendition: AttachmentRendition, blobKey: String) {
        val db = database()
        val owner = db.query(
            TABLE_ATTACHMENTS,
            arrayOf(COL_WORKSPACE_ID),
            "$COL_ATTACHMENT_ID=?",
            arrayOf(rendition.attachmentId.value),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (owner != workspaceId) {
            throw AttachmentException(
                AttachmentErrorCode.CROSS_WORKSPACE,
                "Attachment ${rendition.attachmentId.value} is not stored in this Workspace",
            )
        }
        val values = ContentValues().apply {
            put(COL_RENDITION_ID, rendition.id)
            put(COL_ATTACHMENT_ID, rendition.attachmentId.value)
            put(COL_BLOB_KEY, blobKey)
            put(COL_MIME_TYPE, rendition.mimeType)
            put(COL_BYTE_SIZE, rendition.byteSize)
            put(COL_SHA256, rendition.sha256)
            rendition.transformJson?.let { put(COL_TRANSFORM_JSON, it) } ?: putNull(COL_TRANSFORM_JSON)
            rendition.extractorVersion?.let { put(COL_EXTRACTOR_VERSION, it) } ?: putNull(COL_EXTRACTOR_VERSION)
        }
        val updated = db.update(
            TABLE_RENDITIONS,
            values,
            "$COL_RENDITION_ID=? AND $COL_ATTACHMENT_ID=?",
            arrayOf(rendition.id, rendition.attachmentId.value),
        )
        if (updated == 0) db.insertOrThrow(TABLE_RENDITIONS, null, values)
    }

    override fun deleteRendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): Boolean {
        val db = database()
        val owned = db.query(
            "$TABLE_RENDITIONS r JOIN $TABLE_ATTACHMENTS a ON a.$COL_ATTACHMENT_ID = r.$COL_ATTACHMENT_ID",
            arrayOf("r.$COL_RENDITION_ID"),
            "a.$COL_WORKSPACE_ID=? AND r.$COL_ATTACHMENT_ID=? AND r.$COL_RENDITION_ID=?",
            arrayOf(workspaceId, attachmentId.value, renditionId),
            null,
            null,
            null,
        ).use { it.moveToFirst() }
        if (!owned) return false
        val blobKey = blobKeyFor(workspaceId, attachmentId, renditionId) ?: return false
        val deleted = db.delete(
            TABLE_RENDITIONS,
            "$COL_RENDITION_ID=? AND $COL_ATTACHMENT_ID=?",
            arrayOf(renditionId, attachmentId.value),
        ) > 0
        if (deleted) blobs.delete(workspaceId, blobKey)
        return deleted
    }

    override fun delete(workspaceId: String, id: AttachmentId): Boolean {
        val db = database()
        val exists = db.query(
            TABLE_ATTACHMENTS,
            arrayOf(COL_ATTACHMENT_ID),
            "$COL_WORKSPACE_ID=? AND $COL_ATTACHMENT_ID=?",
            arrayOf(workspaceId, id.value),
            null,
            null,
            null,
        ).use { it.moveToFirst() }
        if (!exists) return false

        // Collect blob keys before the cascade removes the rendition rows.
        val blobKeys = buildList {
            db.query(
                TABLE_RENDITIONS,
                arrayOf(COL_BLOB_KEY),
                "$COL_ATTACHMENT_ID=?",
                arrayOf(id.value),
                null,
                null,
                null,
            ).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val attachmentBlob = db.query(
            TABLE_ATTACHMENTS,
            arrayOf(COL_BLOB_KEY),
            "$COL_WORKSPACE_ID=? AND $COL_ATTACHMENT_ID=?",
            arrayOf(workspaceId, id.value),
            null,
            null,
            null,
        ).use { if (it.moveToFirst()) it.getString(0) else null }

        try {
            val deleted = db.delete(
                TABLE_ATTACHMENTS,
                "$COL_WORKSPACE_ID=? AND $COL_ATTACHMENT_ID=?",
                arrayOf(workspaceId, id.value),
            )
            if (deleted == 0) return false
        } catch (failure: SQLiteConstraintException) {
            throw AttachmentException(
                AttachmentErrorCode.STORAGE_FAILURE,
                "Attachment ${id.value} is still referenced by a message",
                failure,
            )
        }
        attachmentBlob?.let { blobs.delete(workspaceId, it) }
        blobKeys.forEach { blobs.delete(workspaceId, it) }
        // Deletion has already happened; an audit failure here cannot be undone, so it is recorded
        // best-effort rather than reported as a failed delete.
        recordAudit(
            action = AttachmentAuditAction.LOCAL_DELETE,
            decision = AttachmentAuditDecision.ALLOWED,
            workspaceId = workspaceId,
            attachmentId = id.value,
            bestEffort = true,
        )
        return true
    }

    private fun mimeFor(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): String? =
        if (renditionId == null) {
            find(workspaceId, attachmentId)?.mimeType
        } else {
            rendition(workspaceId, attachmentId, renditionId)?.mimeType
        }

    private fun recordAudit(
        action: AttachmentAuditAction,
        decision: AttachmentAuditDecision,
        workspaceId: String,
        attachmentId: String?,
        renditionId: String? = null,
        mimeType: String? = null,
        sha256: String? = null,
        reasonCode: String? = null,
        bestEffort: Boolean,
    ) {
        val event = AttachmentAuditEvent(
            action = action,
            decision = decision,
            workspaceId = workspaceId,
            attachmentId = attachmentId,
            renditionId = renditionId,
            mimeType = mimeType,
            sha256 = sha256,
            reasonCode = reasonCode,
            occurredAtMillis = now(),
        )
        if (bestEffort) {
            runCatching { audit.record(event) }
        } else {
            try {
                audit.record(event)
            } catch (failure: Exception) {
                throw AttachmentException(
                    AttachmentErrorCode.STORAGE_FAILURE,
                    "The attachment audit log is unavailable; the read was refused",
                    failure,
                )
            }
        }
    }

    /**
     * Adds an imported attachment to a draft, after the message text.
     *
     * Draft references are separate from message parts: removing a card must not affect anything
     * already sent, and committing the draft turns these rows into message parts.
     */
    fun addDraftAttachment(
        draftId: String,
        workspaceId: String,
        sessionId: String?,
        attachmentId: AttachmentId,
        nowMillis: Long,
    ): Boolean {
        require(draftId.isNotBlank()) { "draftId must not be blank" }
        val db = database()
        if (find(workspaceId, attachmentId) == null) {
            throw AttachmentException(
                AttachmentErrorCode.UNKNOWN_ATTACHMENT,
                "Attachment ${attachmentId.value} is not stored in this Workspace",
            )
        }
        val nextOrdinal = db.rawQuery(
            "SELECT COALESCE(MAX(${AttachmentDatabaseSchema.COL_ORDINAL}), -1) + 1 FROM " +
                "${AttachmentDatabaseSchema.TABLE_DRAFT_ATTACHMENTS} WHERE ${AttachmentDatabaseSchema.COL_DRAFT_ID} = ?",
            arrayOf(draftId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val values = ContentValues().apply {
            put(AttachmentDatabaseSchema.COL_DRAFT_ID, draftId)
            put(AttachmentDatabaseSchema.COL_WORKSPACE_ID, workspaceId)
            sessionId?.let { put(AttachmentDatabaseSchema.COL_SESSION_ID, it) }
            put(COL_ATTACHMENT_ID, attachmentId.value)
            put(AttachmentDatabaseSchema.COL_ORDINAL, nextOrdinal)
            put(AttachmentDatabaseSchema.COL_CREATED_AT, nowMillis)
        }
        return db.insertOrThrow(AttachmentDatabaseSchema.TABLE_DRAFT_ATTACHMENTS, null, values) != -1L
    }

    fun draftAttachments(draftId: String): List<AttachmentDescriptor> = database().query(
        "${AttachmentDatabaseSchema.TABLE_DRAFT_ATTACHMENTS} d JOIN $TABLE_ATTACHMENTS a " +
            "ON a.$COL_ATTACHMENT_ID = d.$COL_ATTACHMENT_ID",
        ATTACHMENT_COLUMNS.let { columns -> Array(columns.size) { "a.${columns[it]}" } },
        "d.${AttachmentDatabaseSchema.COL_DRAFT_ID} = ?",
        arrayOf(draftId),
        null,
        null,
        "d.${AttachmentDatabaseSchema.COL_ORDINAL} ASC",
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toDescriptor()) }
    }

    fun removeDraftAttachment(draftId: String, attachmentId: AttachmentId): Boolean = database().delete(
        AttachmentDatabaseSchema.TABLE_DRAFT_ATTACHMENTS,
        "${AttachmentDatabaseSchema.COL_DRAFT_ID} = ? AND $COL_ATTACHMENT_ID = ?",
        arrayOf(draftId, attachmentId.value),
    ) > 0

    /**
     * True when a message part or a draft still points at the attachment.
     *
     * Message parts carry no workspace column, so the attachment id alone decides; attachment ids
     * are globally unique and the row itself is workspace-scoped.
     */
    override fun isReferenced(workspaceId: String, attachmentId: AttachmentId): Boolean {
        val db = database()
        val inMessage = db.rawQuery(
            "SELECT 1 FROM ${AttachmentDatabaseSchema.TABLE_MESSAGE_PARTS} " +
                "WHERE $COL_ATTACHMENT_ID = ? LIMIT 1",
            arrayOf(attachmentId.value),
        ).use { it.moveToFirst() }
        if (inMessage) return true
        return db.rawQuery(
            "SELECT 1 FROM ${AttachmentDatabaseSchema.TABLE_DRAFT_ATTACHMENTS} " +
                "WHERE ${AttachmentDatabaseSchema.COL_WORKSPACE_ID} = ? AND $COL_ATTACHMENT_ID = ? LIMIT 1",
            arrayOf(workspaceId, attachmentId.value),
        ).use { it.moveToFirst() }
    }

    fun clearDraft(draftId: String): Int = database().delete(
        AttachmentDatabaseSchema.TABLE_DRAFT_ATTACHMENTS,
        "${AttachmentDatabaseSchema.COL_DRAFT_ID} = ?",
        arrayOf(draftId),
    )

    private fun blobKeyFor(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): String? {
        val db = database()
        return if (renditionId == null) {
            db.query(
                TABLE_ATTACHMENTS,
                arrayOf(COL_BLOB_KEY),
                "$COL_WORKSPACE_ID=? AND $COL_ATTACHMENT_ID=?",
                arrayOf(workspaceId, attachmentId.value),
                null,
                null,
                null,
            ).use { if (it.moveToFirst()) it.getString(0) else null }
        } else {
            db.query(
                "$TABLE_RENDITIONS r JOIN $TABLE_ATTACHMENTS a ON a.$COL_ATTACHMENT_ID = r.$COL_ATTACHMENT_ID",
                arrayOf("r.$COL_BLOB_KEY"),
                "a.$COL_WORKSPACE_ID=? AND r.$COL_ATTACHMENT_ID=? AND r.$COL_RENDITION_ID=?",
                arrayOf(workspaceId, attachmentId.value, renditionId),
                null,
                null,
                null,
            ).use { if (it.moveToFirst()) it.getString(0) else null }
        }
    }

    private fun digestRefFor(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): BlobRef? {
        val db = database()
        return if (renditionId == null) {
            db.query(
                TABLE_ATTACHMENTS,
                arrayOf(COL_BLOB_KEY, COL_SHA256),
                "$COL_WORKSPACE_ID=? AND $COL_ATTACHMENT_ID=?",
                arrayOf(workspaceId, attachmentId.value),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) BlobRef(cursor.getString(0), cursor.getString(1)) else null
            }
        } else {
            db.query(
                "$TABLE_RENDITIONS r JOIN $TABLE_ATTACHMENTS a ON a.$COL_ATTACHMENT_ID = r.$COL_ATTACHMENT_ID",
                arrayOf("r.$COL_BLOB_KEY", "r.$COL_SHA256"),
                "a.$COL_WORKSPACE_ID=? AND r.$COL_ATTACHMENT_ID=? AND r.$COL_RENDITION_ID=?",
                arrayOf(workspaceId, attachmentId.value, renditionId),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) BlobRef(cursor.getString(0), cursor.getString(1)) else null
            }
        }
    }

    private data class BlobRef(val blobKey: String, val sha256: String)

    private fun Cursor.toDescriptor(): AttachmentDescriptor = AttachmentDescriptor(
        id = AttachmentId(getString(getColumnIndexOrThrow(COL_ATTACHMENT_ID))),
        workspaceId = getString(getColumnIndexOrThrow(COL_WORKSPACE_ID)),
        mimeType = getString(getColumnIndexOrThrow(COL_MIME_TYPE)),
        displayName = getString(getColumnIndexOrThrow(COL_DISPLAY_NAME)),
        byteSize = getLong(getColumnIndexOrThrow(COL_BYTE_SIZE)),
        sha256 = getString(getColumnIndexOrThrow(COL_SHA256)),
        state = runCatching { AttachmentState.valueOf(getString(getColumnIndexOrThrow(COL_STATE))) }
            .getOrDefault(AttachmentState.FAILED),
        createdAtMillis = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
        verifiedAtMillis = getColumnIndexOrThrow(COL_VERIFIED_AT).let { index ->
            if (isNull(index)) null else getLong(index)
        },
        width = nullableInt(COL_WIDTH),
        height = nullableInt(COL_HEIGHT),
        pageCount = nullableInt(COL_PAGE_COUNT),
    )

    private fun Cursor.nullableInt(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        val ATTACHMENT_COLUMNS = arrayOf(
            COL_ATTACHMENT_ID,
            COL_WORKSPACE_ID,
            COL_MIME_TYPE,
            COL_DISPLAY_NAME,
            COL_BYTE_SIZE,
            COL_SHA256,
            COL_STATE,
            COL_CREATED_AT,
            COL_VERIFIED_AT,
            COL_WIDTH,
            COL_HEIGHT,
            COL_PAGE_COUNT,
        )
    }
}
