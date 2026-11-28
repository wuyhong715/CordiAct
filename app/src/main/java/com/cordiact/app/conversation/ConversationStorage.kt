package com.cordiact.app.conversation

import android.content.Context
import com.cordiact.core.attachment.AttachmentAuditSink
import com.cordiact.core.attachment.AttachmentBlobStore
import com.cordiact.core.attachment.AttachmentCatalog
import com.cordiact.core.attachment.AttachmentDescriptor
import com.cordiact.core.attachment.AttachmentId
import com.cordiact.core.attachment.AttachmentReferenceIndex
import com.cordiact.core.context.ContextCompactionStore
import com.cordiact.core.conversation.ConversationSessionStore
import com.cordiact.core.jobs.JobStore
import com.cordiact.core.subagent.AgentRunStore
import com.cordiact.core.tool.MutableToolPreferenceStore
import com.cordiact.core.workspace.WorkspaceStore
import java.io.File

/** Selected once during native application composition, before any consumer opens the store. */
fun interface ConversationStorageProvider {
    fun open(context: Context, privateRoot: File): ConversationStorage
}

/**
 * The application's shared durable storage. Providers own consistency across workspaces, history,
 * draft attachments, runs and compaction. Existing data must be migrated by the chosen provider
 * before opening it. One provider is used throughout the process.
 */
interface ConversationStorage : ConversationSessionStore, WorkspaceStore, MutableToolPreferenceStore,
    AgentRunStore, ContextCompactionStore, JobStore {
    fun pluginSetting(workspaceId: String, pluginId: String, key: String): String?
    fun putPluginSetting(workspaceId: String, pluginId: String, key: String, value: String?)
    fun attachments(blobs: AttachmentBlobStore, audit: AttachmentAuditSink): ConversationAttachmentStorage
}

/** Draft references share the same ownership and retention rules as persisted message parts. */
interface ConversationAttachmentStorage : AttachmentCatalog, AttachmentReferenceIndex {
    fun addDraftAttachment(draftId: String, workspaceId: String, sessionId: String?, attachmentId: AttachmentId, nowMillis: Long): Boolean
    fun draftAttachments(draftId: String): List<AttachmentDescriptor>
    fun removeDraftAttachment(draftId: String, attachmentId: AttachmentId): Boolean
    fun pruneDeletedSessionDrafts(): Int
}

object AndroidConversationStorageProvider : ConversationStorageProvider {
    override fun open(context: Context, privateRoot: File): ConversationStorage = AndroidConversationSessionStore(context)
}
