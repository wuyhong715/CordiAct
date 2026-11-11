package com.cordiact.app.experience.composer

import android.net.Uri
import com.cordiact.core.attachment.AttachmentDescriptor
import com.cordiact.core.attachment.AttachmentErrorCode
import com.cordiact.core.attachment.AttachmentId
import com.cordiact.core.attachment.AttachmentLimits
import com.cordiact.core.attachment.AttachmentMediaKind
import com.cordiact.core.attachment.AttachmentState
import com.cordiact.core.attachment.Interpretation
import com.cordiact.core.attachment.MessagePart
import com.cordiact.core.composer.AttachmentCompatibility
import com.cordiact.core.composer.ComposerRules
import com.cordiact.core.conversation.ModelCapabilities
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.conversation.ResponseSpeed

/** One attachment chip in the tray. */
data class AttachmentChip(
    val descriptor: AttachmentDescriptor,
    val state: AttachmentState,
    val compatibility: AttachmentCompatibility? = null,
    val renditionId: String? = null,
    val errorCode: AttachmentErrorCode? = null,
    /** Kept so a failed import can be retried without re-picking the file. */
    val sourceUri: Uri? = null,
    val thumbnail: android.graphics.Bitmap? = null,
) {
    val id: AttachmentId get() = descriptor.id
    val displayName: String get() = descriptor.displayName
    val mediaKind: AttachmentMediaKind get() = descriptor.mediaKind
    val byteSize: Long get() = descriptor.byteSize
    val ready: Boolean get() = state == AttachmentState.READY

    val sendable: Boolean get() = ready && compatibility is AttachmentCompatibility.Sendable

    fun part(): MessagePart.Attachment? {
        val interpretation = (compatibility as? AttachmentCompatibility.Sendable)?.interpretation ?: return null
        return MessagePart.Attachment(id, interpretation, renditionId)
    }

    /** Re-evaluates what the (possibly new) model can do with this attachment. */
    fun withModel(capabilities: ModelCapabilities, limits: AttachmentLimits = AttachmentLimits()): AttachmentChip =
        copy(compatibility = ComposerRules.compatibility(descriptor, capabilities.attachments, limits))
}

/** Which popover is open; at most one at a time. */
sealed interface ComposerPopover {
    data object Attach : ComposerPopover
    data object Model : ComposerPopover
    data object Effort : ComposerPopover
    data class AttachmentError(val id: AttachmentId) : ComposerPopover
}

data class ComposerBanner(val text: String, val tone: BannerTone, val action: BannerAction? = null)

enum class BannerTone { INFO, WARNING, ERROR }

enum class BannerAction { PICK_COMPATIBLE_MODEL, REMOVE_LOCAL_ONLY }

data class ComposerState(
    val text: String = "",
    val attachments: List<AttachmentChip> = emptyList(),
    val model: ModelId? = null,
    val modelName: String = "",
    val capabilities: ModelCapabilities = ModelCapabilities.NONE,
    val effort: ReasoningEffort = ReasoningEffort.AUTO,
    val speed: ResponseSpeed = ResponseSpeed.AUTO,
    val popover: ComposerPopover? = null,
    val banner: ComposerBanner? = null,
    val running: Boolean = false,
    val stopping: Boolean = false,
    val usageRevision: Int = 0,
) {
    val effortStops: List<ReasoningEffort> get() = ComposerRules.effortStops(capabilities)
    val speedCycle: List<ResponseSpeed> get() = ComposerRules.speedCycle(capabilities)
    val tuning: ComposerRules.Tuning get() = ComposerRules.Tuning(effort, speed)
    val maxAttachments: Int get() = ComposerRules.maxAttachments(capabilities.attachments)
    val tuningAvailable: Boolean get() = effortStops.isNotEmpty() || speedCycle.isNotEmpty()

    val sendAvailability: ComposerRules.SendAvailability
        get() = ComposerRules.sendAvailability(
            text,
            attachments.filter { it.state != AttachmentState.CANCELLED }
                .map { ComposerRules.DraftAttachment(it.state, it.compatibility) },
            maxAttachments,
        )

    val localOnlyCount: Int
        get() = attachments.count { it.ready && it.compatibility == AttachmentCompatibility.LocalOnly }

    val failedCount: Int get() = attachments.count { it.state == AttachmentState.FAILED }

    /** All ready parts persist with the message; LOCAL_ONLY bytes never reach the provider. */
    fun sendableParts(): List<MessagePart.Attachment> = attachments.mapNotNull {
            when {
                it.sendable -> it.part()
                it.ready && it.compatibility == AttachmentCompatibility.LocalOnly -> MessagePart.Attachment(it.id, Interpretation.LOCAL_ONLY, it.renditionId)
                else -> null
            }
        }

    fun effortLabel(effort: ReasoningEffort): String = ComposerRules.effortLabel(effort)
    fun speedLabel(speed: ResponseSpeed): String = ComposerRules.speedLabel(speed)

    /** Control-pill text: `model · effort · speed`, omitting anything left on auto. */
    fun pillLabel(): String = buildString {
        append(modelName.ifBlank { "选择模型" })
        if (effort != ReasoningEffort.AUTO) append(" · ").append(effortLabel(effort))
        if (speed != ResponseSpeed.AUTO) append(" · ").append(speedLabel(speed))
    }

    companion object {
        val EMPTY = ComposerState()
    }
}

/** Short badge on a chip; null means "sent as-is". */
fun AttachmentCompatibility.badge(): String? = when (this) {
    is AttachmentCompatibility.Sendable ->
        if (interpretation == Interpretation.TEXT_EXTRACT) "文本" else null
    AttachmentCompatibility.LocalOnly -> "仅本地"
    is AttachmentCompatibility.OverLimit -> "超限"
}

fun AttachmentErrorCode?.message(limits: AttachmentLimits = AttachmentLimits()): String = when (this) {
    AttachmentErrorCode.UNSUPPORTED_MIME -> "不支持这种文件类型"
    AttachmentErrorCode.FILE_TOO_LARGE -> "文件超过 ${limits.maxImageBytes / 1_000_000} MB"
    AttachmentErrorCode.IMAGE_TOO_LARGE -> "图片超过 ${limits.maxImagePixels / 1_000_000} MP"
    AttachmentErrorCode.TOO_MANY_ATTACHMENTS -> "每条消息最多 ${limits.maxAttachmentsPerMessage} 个附件"
    AttachmentErrorCode.REQUEST_TOO_LARGE, AttachmentErrorCode.BUDGET_EXCEEDED -> "附件合计过大，请移除一些"
    AttachmentErrorCode.INTEGRITY_MISMATCH, AttachmentErrorCode.STORAGE_FAILURE -> "保存失败，可以重试"
    AttachmentErrorCode.UNSUPPORTED_CAPABILITY, AttachmentErrorCode.LOCAL_ONLY_PART -> "当前模型无法读取此附件"
    AttachmentErrorCode.CANCELLED -> "已取消"
    null -> "导入失败"
    else -> name
}
