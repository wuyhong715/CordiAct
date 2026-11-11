package com.cordiact.core.attachment

import java.io.InputStream

/** Read side of the attachment catalogue. Every lookup is scoped by Workspace. */
interface AttachmentReader {
    fun find(workspaceId: String, id: AttachmentId): AttachmentDescriptor?

    fun rendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): AttachmentRendition?

    fun open(workspaceId: String, attachmentId: AttachmentId, renditionId: String? = null): InputStream

    /** Re-reads the stored bytes and compares them with the recorded digest. */
    fun verify(workspaceId: String, attachmentId: AttachmentId, renditionId: String? = null): Boolean
}

/**
 * Persistence port for attachment metadata.
 *
 * Implementations must scope every query by `workspaceId` and must never return a descriptor whose
 * `workspaceId` differs from the requested one.
 */
interface AttachmentCatalog : AttachmentReader {
    /**
     * Inserts while state is IMPORTING, or updates after verification.
     *
     * The blob key is stored next to the descriptor because the descriptor is workspace-facing and
     * the key is storage-facing; they must not be the same opaque token.
     */
    fun upsert(descriptor: AttachmentDescriptor, blobKey: String)

    fun upsertRendition(workspaceId: String, rendition: AttachmentRendition, blobKey: String)

    /** Removes one rendition's metadata; the caller owns its blob. */
    fun deleteRendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): Boolean

    fun list(workspaceId: String): List<AttachmentDescriptor>

    /** Deletes metadata only; blob deletion is the repository's separate, explicit step. */
    fun delete(workspaceId: String, id: AttachmentId): Boolean
}
