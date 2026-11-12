package com.cordiact.core.composer

import com.cordiact.core.attachment.AttachmentDescriptor
import com.cordiact.core.attachment.AttachmentErrorCode
import com.cordiact.core.attachment.AttachmentInputCapability
import com.cordiact.core.attachment.AttachmentLimits
import com.cordiact.core.attachment.AttachmentMediaKind
import com.cordiact.core.attachment.AttachmentState
import com.cordiact.core.attachment.Interpretation
import com.cordiact.core.conversation.ModelCapabilities
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.conversation.ResponseSpeed

/**
 * Pure rules behind the conversation composer. The UI and the send path both call these, so what
 * the user sees on an attachment chip is exactly what will happen when the message is sent.
 */
sealed interface AttachmentCompatibility {
    /** Sent to the model with this interpretation. */
    data class Sendable(val interpretation: Interpretation) : AttachmentCompatibility

    /** The model cannot read it; it stays in the Workspace and is not transmitted. */
    data object LocalOnly : AttachmentCompatibility

    /** Exceeds a hard limit; the message cannot be sent while it is attached. */
    data class OverLimit(val code: AttachmentErrorCode) : AttachmentCompatibility
}

object ComposerRules {

    // ---- attachments -------------------------------------------------------------------------

    fun compatibility(
        attachment: AttachmentDescriptor,
        capability: AttachmentInputCapability,
        limits: AttachmentLimits = AttachmentLimits(),
    ): AttachmentCompatibility {
        overLimit(attachment, limits)?.let { return AttachmentCompatibility.OverLimit(it) }
        if (!capability.declared) return AttachmentCompatibility.LocalOnly
        if (capability.maxFileBytes > 0 && attachment.byteSize > capability.maxFileBytes) {
            return AttachmentCompatibility.OverLimit(AttachmentErrorCode.FILE_TOO_LARGE)
        }
        val preferred = when (attachment.mediaKind) {
            AttachmentMediaKind.IMAGE -> listOf(Interpretation.VISION)
            AttachmentMediaKind.PDF -> listOf(Interpretation.NATIVE_DOCUMENT)
            AttachmentMediaKind.TEXT -> listOf(Interpretation.TEXT_EXTRACT)
            AttachmentMediaKind.BINARY -> emptyList()
        }
        val chosen = preferred.firstOrNull { capability.supports(attachment.mimeType, it) }
        return if (chosen == null) AttachmentCompatibility.LocalOnly else AttachmentCompatibility.Sendable(chosen)
    }

    private fun overLimit(attachment: AttachmentDescriptor, limits: AttachmentLimits): AttachmentErrorCode? =
        when (attachment.mediaKind) {
            AttachmentMediaKind.IMAGE -> when {
                attachment.byteSize > limits.maxImageBytes -> AttachmentErrorCode.FILE_TOO_LARGE
                attachment.width != null && attachment.height != null &&
                    attachment.width.toLong() * attachment.height > limits.maxImagePixels -> AttachmentErrorCode.IMAGE_TOO_LARGE
                else -> null
            }
            AttachmentMediaKind.PDF -> when {
                attachment.byteSize > limits.maxPdfBytes -> AttachmentErrorCode.FILE_TOO_LARGE
                (attachment.pageCount ?: 0) > limits.maxPdfPages -> AttachmentErrorCode.FILE_TOO_LARGE
                else -> null
            }
            AttachmentMediaKind.TEXT ->
                if (attachment.byteSize > limits.maxTextBytes) AttachmentErrorCode.FILE_TOO_LARGE else null
            AttachmentMediaKind.BINARY -> null
        }

    /** Max attachments the composer accepts for [capability]; LOCAL_ONLY items still count. */
    fun maxAttachments(capability: AttachmentInputCapability, limits: AttachmentLimits = AttachmentLimits()): Int =
        if (capability.declared) minOf(limits.maxAttachmentsPerMessage, capability.maxCount) else limits.maxAttachmentsPerMessage

    // ---- send button -------------------------------------------------------------------------

    enum class SendAvailability { DISABLED, READY, WAIT_FOR_ATTACHMENTS, BLOCKED }

    data class DraftAttachment(val state: AttachmentState, val compatibility: AttachmentCompatibility?)

    fun sendAvailability(text: String, attachments: List<DraftAttachment>, maxAttachments: Int): SendAvailability {
        val live = attachments.filter { it.state != AttachmentState.CANCELLED }
        if (text.isBlank() && live.isEmpty()) return SendAvailability.DISABLED
        if (live.size > maxAttachments) return SendAvailability.BLOCKED
        if (live.any { it.state == AttachmentState.FAILED }) return SendAvailability.BLOCKED
        if (live.any { it.compatibility is AttachmentCompatibility.OverLimit }) return SendAvailability.BLOCKED
        if (live.any { it.state == AttachmentState.IMPORTING || it.state == AttachmentState.VERIFYING }) {
            return SendAvailability.WAIT_FOR_ATTACHMENTS
        }
        // Text-less message whose only attachments cannot reach the model has nothing to send.
        if (text.isBlank() && live.none { it.compatibility is AttachmentCompatibility.Sendable }) return SendAvailability.BLOCKED
        return SendAvailability.READY
    }

    // ---- reasoning effort & speed ------------------------------------------------------------

    /**
     * Slider stops for a model: the declared efforts in enum order with AUTO placed between NONE and
     * MINIMAL (the enum's own order). Empty when the model declares nothing adjustable.
     */
    fun effortStops(capabilities: ModelCapabilities): List<ReasoningEffort> =
        if (capabilities.reasoningEfforts.isEmpty()) emptyList()
        else ReasoningEffort.entries.filter { it == ReasoningEffort.AUTO || it in capabilities.reasoningEfforts }

    /** OpenAI-standard wire names, shown as-is; only MAX is capitalised. */
    fun effortLabel(effort: ReasoningEffort): String =
        if (effort == ReasoningEffort.MAX) "Max" else effort.name.lowercase()

    /** Speed cycle used by the ⚡ button: AUTO first, then the declared speeds in enum order. */
    fun speedCycle(capabilities: ModelCapabilities): List<ResponseSpeed> =
        if (capabilities.responseSpeeds.isEmpty()) emptyList()
        else ResponseSpeed.entries.filter { it == ResponseSpeed.AUTO || it in capabilities.responseSpeeds }

    fun nextSpeed(current: ResponseSpeed, capabilities: ModelCapabilities): ResponseSpeed {
        val cycle = speedCycle(capabilities)
        if (cycle.isEmpty()) return ResponseSpeed.AUTO
        val index = cycle.indexOf(current)
        return cycle[(index + 1).mod(cycle.size)]
    }

    fun speedLabel(speed: ResponseSpeed): String = when (speed) {
        ResponseSpeed.AUTO -> "标准"
        ResponseSpeed.FAST -> "快速"
        ResponseSpeed.ULTRAFAST -> "极速"
    }

    data class Tuning(val effort: ReasoningEffort, val speed: ResponseSpeed)

    /** Drops any choice the (new) model does not declare back to AUTO. */
    fun reconcile(tuning: Tuning, capabilities: ModelCapabilities): Tuning = Tuning(
        effort = if (tuning.effort == ReasoningEffort.AUTO || tuning.effort in capabilities.reasoningEfforts) tuning.effort else ReasoningEffort.AUTO,
        speed = if (tuning.speed == ResponseSpeed.AUTO || tuning.speed in capabilities.responseSpeeds) tuning.speed else ResponseSpeed.AUTO,
    )

    /** Explicit AUTO overrides a provider's stored high/fast defaults; the backend omits it on wire. */
    fun parameters(tuning: Tuning): com.cordiact.core.conversation.GenerationParameters =
        com.cordiact.core.conversation.GenerationParameters(reasoningEffort = tuning.effort, responseSpeed = tuning.speed)
}
