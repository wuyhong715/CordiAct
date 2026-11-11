package com.cordiact.core.composer

import com.cordiact.core.attachment.AttachmentDescriptor
import com.cordiact.core.attachment.AttachmentErrorCode
import com.cordiact.core.attachment.AttachmentHistoryPolicy
import com.cordiact.core.attachment.AttachmentId
import com.cordiact.core.attachment.AttachmentInputCapability
import com.cordiact.core.attachment.AttachmentState
import com.cordiact.core.attachment.Interpretation
import com.cordiact.core.attachment.MessagePart
import com.cordiact.core.attachment.MessageParts
import com.cordiact.core.composer.ComposerRules.DraftAttachment
import com.cordiact.core.composer.ComposerRules.SendAvailability
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelCapabilities
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.conversation.ResponseSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerRulesTest {
    private fun descriptor(mime: String, size: Long = 1000, width: Int? = null, height: Int? = null, pages: Int? = null) =
        AttachmentDescriptor(
            id = AttachmentId("att-" + mime.hashCode()), workspaceId = "ws", mimeType = mime, displayName = "f",
            byteSize = size, sha256 = "0".repeat(64), state = AttachmentState.READY, createdAtMillis = 0,
            verifiedAtMillis = 0, width = width, height = height, pageCount = pages,
        )

    private val vision = AttachmentInputCapability(
        interpretations = setOf(Interpretation.VISION, Interpretation.TEXT_EXTRACT),
        mimeTypes = setOf("image/png", "image/jpeg", "text/*"),
        maxFileBytes = 5_000_000, maxRequestBytes = 20_000_000, maxCount = 4,
    )

    @Test fun imageIsSentAsVisionWhenDeclared() {
        assertEquals(AttachmentCompatibility.Sendable(Interpretation.VISION), ComposerRules.compatibility(descriptor("image/png"), vision))
    }

    @Test fun undeclaredModelKeepsEverythingLocal() {
        assertEquals(AttachmentCompatibility.LocalOnly, ComposerRules.compatibility(descriptor("image/png"), AttachmentInputCapability.UNSUPPORTED))
    }

    @Test fun pdfWithoutNativeDocumentSupportStaysLocal() {
        assertEquals(AttachmentCompatibility.LocalOnly, ComposerRules.compatibility(descriptor("application/pdf"), vision))
    }

    @Test fun limitsWinOverCapability() {
        val huge = descriptor("image/png", width = 10_000, height = 10_000)
        assertEquals(AttachmentCompatibility.OverLimit(AttachmentErrorCode.IMAGE_TOO_LARGE), ComposerRules.compatibility(huge, vision))
        val big = descriptor("image/png", size = 6_000_000)
        assertEquals(AttachmentCompatibility.OverLimit(AttachmentErrorCode.FILE_TOO_LARGE), ComposerRules.compatibility(big, vision))
    }

    @Test fun maxAttachmentsUsesTheSmallerLimit() {
        assertEquals(4, ComposerRules.maxAttachments(vision))
        assertEquals(10, ComposerRules.maxAttachments(AttachmentInputCapability.UNSUPPORTED))
    }

    @Test fun sendAvailabilityCoversEveryState() {
        val ready = DraftAttachment(AttachmentState.READY, AttachmentCompatibility.Sendable(Interpretation.VISION))
        val importing = DraftAttachment(AttachmentState.IMPORTING, null)
        val failed = DraftAttachment(AttachmentState.FAILED, null)
        val local = DraftAttachment(AttachmentState.READY, AttachmentCompatibility.LocalOnly)
        assertEquals(SendAvailability.DISABLED, ComposerRules.sendAvailability(" ", emptyList(), 10))
        assertEquals(SendAvailability.READY, ComposerRules.sendAvailability("hi", emptyList(), 10))
        assertEquals(SendAvailability.READY, ComposerRules.sendAvailability("", listOf(ready), 10))
        assertEquals(SendAvailability.WAIT_FOR_ATTACHMENTS, ComposerRules.sendAvailability("hi", listOf(ready, importing), 10))
        assertEquals(SendAvailability.BLOCKED, ComposerRules.sendAvailability("hi", listOf(failed), 10))
        assertEquals(SendAvailability.BLOCKED, ComposerRules.sendAvailability("", listOf(local), 10))
        assertEquals(SendAvailability.READY, ComposerRules.sendAvailability("hi", listOf(local), 10))
        assertEquals(SendAvailability.BLOCKED, ComposerRules.sendAvailability("hi", listOf(ready, ready), 1))
    }

    @Test fun effortStopsPlaceAutoBetweenNoneAndMinimal() {
        val caps = ModelCapabilities(reasoningEfforts = setOf(ReasoningEffort.MAX, ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.HIGH))
        assertEquals(
            listOf(ReasoningEffort.NONE, ReasoningEffort.AUTO, ReasoningEffort.MINIMAL, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            ComposerRules.effortStops(caps),
        )
        assertTrue(ComposerRules.effortStops(ModelCapabilities.NONE).isEmpty())
    }

    @Test fun effortLabelsAreWireNamesWithCapitalMax() {
        assertEquals(
            listOf("none", "auto", "minimal", "low", "medium", "high", "xhigh", "Max"),
            ReasoningEffort.entries.map(ComposerRules::effortLabel),
        )
    }

    @Test fun speedCyclesThroughDeclaredValuesOnly() {
        val caps = ModelCapabilities(responseSpeeds = setOf(ResponseSpeed.FAST))
        assertEquals(ResponseSpeed.FAST, ComposerRules.nextSpeed(ResponseSpeed.AUTO, caps))
        assertEquals(ResponseSpeed.AUTO, ComposerRules.nextSpeed(ResponseSpeed.FAST, caps))
        assertEquals(ResponseSpeed.AUTO, ComposerRules.nextSpeed(ResponseSpeed.ULTRAFAST, ModelCapabilities.NONE))
    }

    @Test fun reconcileFallsBackToAutoForUndeclaredChoices() {
        val tuning = ComposerRules.Tuning(ReasoningEffort.XHIGH, ResponseSpeed.ULTRAFAST)
        val caps = ModelCapabilities(reasoningEfforts = setOf(ReasoningEffort.HIGH), responseSpeeds = setOf(ResponseSpeed.ULTRAFAST))
        assertEquals(ComposerRules.Tuning(ReasoningEffort.AUTO, ResponseSpeed.ULTRAFAST), ComposerRules.reconcile(tuning, caps))
    }

    @Test fun autoOverridesProviderDefaultsAndStillOmitsFieldsOnWire() {
        val auto = ComposerRules.parameters(ComposerRules.Tuning(ReasoningEffort.AUTO, ResponseSpeed.AUTO))
        assertEquals(ReasoningEffort.AUTO, auto.reasoningEffort)
        assertEquals(ResponseSpeed.AUTO, auto.responseSpeed)
        val defaults = com.cordiact.core.conversation.GenerationParameters(reasoningEffort = ReasoningEffort.MAX, responseSpeed = ResponseSpeed.FAST)
        assertEquals(ReasoningEffort.AUTO, defaults.mergedWith(auto).reasoningEffort)
        val p = ComposerRules.parameters(ComposerRules.Tuning(ReasoningEffort.HIGH, ResponseSpeed.AUTO))
        assertEquals(ReasoningEffort.HIGH, p.reasoningEffort)
        assertEquals(ResponseSpeed.AUTO, p.responseSpeed)
    }

    @Test fun localOnlyTurnsIntoMarkerWhileAllOtherPartsKeepStrictPreflight() {
        val image = MessagePart.Attachment(AttachmentId("a1"), Interpretation.VISION)
        val local = MessagePart.Attachment(AttachmentId("a2"), Interpretation.LOCAL_ONLY)
        val parts = listOf(MessagePart.Text("看图"), image, local)
        val message = ModelMessage(MessageRole.USER, MessageParts.plainText(parts), parts = parts)
        val normalized = AttachmentHistoryPolicy.normalize(listOf(message)).single()
        assertTrue(normalized.parts.contains(image))
        assertTrue(normalized.parts.none { it == local })
        assertTrue(normalized.content.contains("a2") && normalized.content.contains("未发送给模型"))
        MessageParts.validate(normalized.parts)
        assertEquals(normalized, AttachmentHistoryPolicy.normalize(listOf(normalized)).single())
    }
}
