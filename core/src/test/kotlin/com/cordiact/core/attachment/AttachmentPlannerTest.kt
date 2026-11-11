package com.cordiact.core.attachment

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AttachmentPlannerTest {
    private val workspace = "ws-1"
    private val image = descriptor("att-image", "image/png", 1000, verified = true)
    private val pdf = descriptor("att-pdf", "application/pdf", 2000, verified = true)
    private val text = descriptor("att-text", "text/plain", 300, verified = true)

    private fun reader(vararg descriptors: AttachmentDescriptor): FakeReader =
        FakeReader(descriptors.associateBy { it.id.value })

    private fun capability(
        interpretations: Set<Interpretation> = setOf(Interpretation.VISION, Interpretation.NATIVE_DOCUMENT, Interpretation.TEXT_EXTRACT),
        mimeTypes: Set<String> = setOf("image/png", "application/pdf", "text/*"),
    ) = AttachmentInputCapability(
        interpretations = interpretations,
        mimeTypes = mimeTypes,
        maxFileBytes = 5_000_000,
        maxRequestBytes = 10_000_000,
        maxCount = 10,
    )

    @Test
    fun textOnlyMessageNeedsNoCapability() {
        val plan = AttachmentPlanner(reader()).plan(request(listOf(MessagePart.Text("hi"))))
        assertEquals(0, plan.attachments.size)
        assertEquals(2L, plan.textBytes)
        assertTrue(plan.estimatedRequestBytes > 0)
    }

    @Test
    fun plansOrderedImageAndText() {
        val parts = listOf(
            MessagePart.Text("look"),
            MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION),
            MessagePart.Attachment(AttachmentId("att-text"), Interpretation.TEXT_EXTRACT),
        )
        val plan = AttachmentPlanner(reader(image, text)).plan(request(parts))
        assertEquals(listOf("att-image", "att-text"), plan.attachments.map { it.attachmentId.value })
        assertEquals(1300, plan.attachmentBytes)
    }

    @Test
    fun refusesWhenCapabilityIsNotDeclared() {
        val failure = assertFailsWith<AttachmentException> {
            AttachmentPlanner(reader(image)).plan(
                request(
                    listOf(MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION)),
                    capability = AttachmentInputCapability.UNSUPPORTED,
                ),
            )
        }
        assertEquals(AttachmentErrorCode.UNSUPPORTED_CAPABILITY, failure.code)
    }

    @Test
    fun refusesLocalOnlyParts() {
        val failure = assertFailsWith<AttachmentException> {
            AttachmentPlanner(reader(image)).plan(
                request(listOf(MessagePart.Attachment(AttachmentId("att-image"), Interpretation.LOCAL_ONLY))),
            )
        }
        assertEquals(AttachmentErrorCode.LOCAL_ONLY_PART, failure.code)
    }

    @Test
    fun refusesUnknownAndUnreadyAttachments() {
        val unknown = assertFailsWith<AttachmentException> {
            AttachmentPlanner(reader()).plan(request(listOf(MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION))))
        }
        assertEquals(AttachmentErrorCode.UNKNOWN_ATTACHMENT, unknown.code)

        val importing = image.copy(state = AttachmentState.IMPORTING, sha256 = "", verifiedAtMillis = null)
        val notReady = assertFailsWith<AttachmentException> {
            AttachmentPlanner(reader(importing)).plan(request(listOf(MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION))))
        }
        assertEquals(AttachmentErrorCode.NOT_READY, notReady.code)
    }

    @Test
    fun refusesCrossWorkspaceDescriptor() {
        val foreign = image.copy(workspaceId = "ws-2")
        val failure = assertFailsWith<AttachmentException> {
            AttachmentPlanner(reader(foreign)).plan(request(listOf(MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION))))
        }
        assertEquals(AttachmentErrorCode.CROSS_WORKSPACE, failure.code)
    }

    @Test
    fun refusesWrongInterpretationForMedia() {
        val failure = assertFailsWith<AttachmentException> {
            AttachmentPlanner(reader(pdf)).plan(request(listOf(MessagePart.Attachment(AttachmentId("att-pdf"), Interpretation.VISION))))
        }
        assertEquals(AttachmentErrorCode.UNSUPPORTED_INTERPRETATION, failure.code)
    }

    @Test
    fun refusesMimeOutsideCapability() {
        val failure = assertFailsWith<AttachmentException> {
            AttachmentPlanner(reader(pdf)).plan(
                request(
                    listOf(MessagePart.Attachment(AttachmentId("att-pdf"), Interpretation.NATIVE_DOCUMENT)),
                    capability = capability(mimeTypes = setOf("image/png")),
                ),
            )
        }
        assertEquals(AttachmentErrorCode.UNSUPPORTED_MIME, failure.code)
    }

    @Test
    fun enforcesCountAndByteLimits() {
        val countFailure = assertFailsWith<AttachmentException> {
            AttachmentPlanner(reader(image)).plan(
                request(
                    listOf(
                        MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION),
                        MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION),
                    ),
                    capability = capability().copy(maxCount = 1),
                ),
            )
        }
        assertEquals(AttachmentErrorCode.TOO_MANY_ATTACHMENTS, countFailure.code)

        val big = image.copy(byteSize = 6_000_000, sha256 = image.sha256)
        val sizeFailure = assertFailsWith<AttachmentException> {
            AttachmentPlanner(reader(big)).plan(request(listOf(MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION))))
        }
        assertEquals(AttachmentErrorCode.FILE_TOO_LARGE, sizeFailure.code)
    }

    @Test
    fun fingerprintChangesWithEndpointAndContent() {
        val parts = listOf(MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION))
        val first = AttachmentPlanner(reader(image)).plan(request(parts))
        val second = AttachmentPlanner(reader(image)).plan(request(parts))
        assertEquals(first.fingerprint, second.fingerprint)
        val other = AttachmentPlanner(reader(image)).plan(request(parts, endpointRevision = "rev-2"))
        assertNotEquals(first.fingerprint, other.fingerprint)
    }

    private fun request(
        parts: List<MessagePart>,
        capability: AttachmentInputCapability = capability(),
        endpointRevision: String = "rev-1",
    ) = TransmissionRequest(
        workspaceId = workspace,
        sessionId = "session-1",
        providerId = "provider",
        modelId = "model",
        protocol = "openai-chat",
        endpointRevision = endpointRevision,
        policyRevision = "policy-1",
        capability = capability,
        parts = parts,
    )

    private fun descriptor(id: String, mime: String, size: Long, verified: Boolean): AttachmentDescriptor =
        AttachmentDescriptor(
            id = AttachmentId(id),
            workspaceId = workspace,
            mimeType = mime,
            displayName = "$id.bin",
            byteSize = size,
            sha256 = if (verified) "a".repeat(64) else "",
            state = if (verified) AttachmentState.READY else AttachmentState.IMPORTING,
            createdAtMillis = 1,
            verifiedAtMillis = if (verified) 1 else null,
        )

    private class FakeReader(private val descriptors: Map<String, AttachmentDescriptor>) : AttachmentReader {
        override fun find(workspaceId: String, id: AttachmentId): AttachmentDescriptor? =
            descriptors[id.value]

        override fun rendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): AttachmentRendition? = null

        override fun open(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): InputStream =
            ByteArrayInputStream(ByteArray(0))

        override fun verify(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): Boolean = true
    }
}
