package com.cordiact.app.attachment

import android.database.sqlite.SQLiteDatabase

/**
 * Attachment tables, created in one place so [com.cordiact.app.conversation.AndroidConversationSessionStore]
 * and [AndroidAttachmentCatalog] cannot drift apart.
 *
 * Design rules encoded here:
 * - blobs are not stored in the database; only the opaque blob key is;
 * - a part row points at an attachment row, and a message cannot lose its attachment silently
 *   (the foreign key is `RESTRICT`, not `SET NULL`);
 * - the schema version of a part row travels with the row, so a future format can be migrated.
 */
object AttachmentDatabaseSchema {
    const val TABLE_ATTACHMENTS = "conversation_attachments"
    const val TABLE_RENDITIONS = "conversation_attachment_renditions"
    const val TABLE_MESSAGE_PARTS = "conversation_message_parts"
    const val TABLE_DRAFT_ATTACHMENTS = "conversation_draft_attachments"

    const val COL_DRAFT_ID = "draft_id"
    const val COL_SESSION_ID = "session_id"

    const val COL_ATTACHMENT_ID = "attachment_id"
    const val COL_WORKSPACE_ID = "workspace_id"
    const val COL_BLOB_KEY = "blob_key"
    const val COL_MIME_TYPE = "mime_type"
    const val COL_DISPLAY_NAME = "display_name"
    const val COL_BYTE_SIZE = "byte_size"
    const val COL_SHA256 = "sha256"
    const val COL_STATE = "state"
    const val COL_CREATED_AT = "created_at"
    const val COL_VERIFIED_AT = "verified_at"
    const val COL_WIDTH = "width"
    const val COL_HEIGHT = "height"
    const val COL_PAGE_COUNT = "page_count"

    const val COL_RENDITION_ID = "rendition_id"
    const val COL_TRANSFORM_JSON = "transform_json"
    const val COL_EXTRACTOR_VERSION = "extractor_version"

    const val COL_MESSAGE_ID = "message_id"
    const val COL_ORDINAL = "ordinal"
    const val COL_KIND = "kind"
    const val COL_TEXT_VALUE = "text_value"
    const val COL_INTERPRETATION = "interpretation"
    const val COL_SCHEMA_VERSION = "schema_version"

    fun create(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ATTACHMENTS (
                $COL_ATTACHMENT_ID TEXT PRIMARY KEY NOT NULL,
                $COL_WORKSPACE_ID TEXT NOT NULL,
                $COL_BLOB_KEY TEXT NOT NULL,
                $COL_MIME_TYPE TEXT NOT NULL,
                $COL_DISPLAY_NAME TEXT NOT NULL,
                $COL_BYTE_SIZE INTEGER NOT NULL,
                $COL_SHA256 TEXT NOT NULL,
                $COL_STATE TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_VERIFIED_AT INTEGER,
                $COL_WIDTH INTEGER,
                $COL_HEIGHT INTEGER,
                $COL_PAGE_COUNT INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_attachments_workspace ON $TABLE_ATTACHMENTS($COL_WORKSPACE_ID, $COL_CREATED_AT)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_RENDITIONS (
                $COL_RENDITION_ID TEXT PRIMARY KEY NOT NULL,
                $COL_ATTACHMENT_ID TEXT NOT NULL REFERENCES $TABLE_ATTACHMENTS($COL_ATTACHMENT_ID) ON DELETE CASCADE,
                $COL_BLOB_KEY TEXT NOT NULL,
                $COL_MIME_TYPE TEXT NOT NULL,
                $COL_BYTE_SIZE INTEGER NOT NULL,
                $COL_SHA256 TEXT NOT NULL,
                $COL_TRANSFORM_JSON TEXT,
                $COL_EXTRACTOR_VERSION TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MESSAGE_PARTS (
                $COL_MESSAGE_ID TEXT NOT NULL REFERENCES conversation_messages(message_id) ON DELETE CASCADE,
                $COL_ORDINAL INTEGER NOT NULL,
                $COL_KIND TEXT NOT NULL,
                $COL_TEXT_VALUE TEXT,
                $COL_ATTACHMENT_ID TEXT REFERENCES $TABLE_ATTACHMENTS($COL_ATTACHMENT_ID) ON DELETE RESTRICT,
                $COL_RENDITION_ID TEXT,
                $COL_INTERPRETATION TEXT,
                $COL_SCHEMA_VERSION INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY($COL_MESSAGE_ID, $COL_ORDINAL)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_message_parts_attachment ON $TABLE_MESSAGE_PARTS($COL_ATTACHMENT_ID)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DRAFT_ATTACHMENTS (
                $COL_DRAFT_ID TEXT NOT NULL,
                $COL_WORKSPACE_ID TEXT NOT NULL,
                $COL_SESSION_ID TEXT,
                $COL_ATTACHMENT_ID TEXT NOT NULL REFERENCES $TABLE_ATTACHMENTS($COL_ATTACHMENT_ID) ON DELETE RESTRICT,
                $COL_ORDINAL INTEGER NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                PRIMARY KEY($COL_DRAFT_ID, $COL_ORDINAL)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_draft_attachments_attachment ON $TABLE_DRAFT_ATTACHMENTS($COL_ATTACHMENT_ID)",
        )
    }
}
