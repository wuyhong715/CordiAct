package com.cordiact.core.attachment

/** User policy is global. Protocol/model caps are still enforced by AttachmentPlanner. */
fun interface AttachmentPolicyProvider {
    fun limits(): AttachmentLimits
}

object AttachmentPolicy {
    val safe = AttachmentLimits()
    fun validate(value: AttachmentLimits): AttachmentLimits {
        require(value.maxAttachmentsPerMessage in 1..safe.maxAttachmentsPerMessage)
        require(value.maxImageBytes in 1..safe.maxImageBytes)
        require(value.maxImagePixels in 1..safe.maxImagePixels)
        require(value.maxImageEdge in 1..safe.maxImageEdge)
        require(value.maxPdfBytes in 1..safe.maxPdfBytes)
        require(value.maxPdfPages in 1..safe.maxPdfPages)
        require(value.maxTextBytes in 1..safe.maxTextBytes)
        require(value.maxTextTotalBytes in value.maxTextBytes..safe.maxTextTotalBytes)
        require(value.maxRawRequestBytes == safe.maxRawRequestBytes && value.maxEncodedRequestBytes == safe.maxEncodedRequestBytes) { "REQUEST_LIMITS_READ_ONLY" }
        return value
    }
}
