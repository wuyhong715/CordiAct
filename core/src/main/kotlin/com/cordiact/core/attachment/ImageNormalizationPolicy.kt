package com.cordiact.core.attachment

/**
 * Pure decision for turning a stored image into a send-ready rendition.
 *
 * The policy is deliberately free of Android types so the arithmetic that protects memory and
 * orientation can be unit-tested: the decoder only executes what this returns.
 */
data class ImageNormalizationPlan(
    val outputMimeType: String,
    val outputWidth: Int,
    val outputHeight: Int,
    /** Power-of-two decode factor, applied before rotation and scaling. */
    val sampleSize: Int,
    val rotationDegrees: Int,
    val flipHorizontal: Boolean,
    val flipVertical: Boolean,
    val requiresTransform: Boolean,
) {
    init {
        require(outputWidth > 0 && outputHeight > 0)
        require(sampleSize >= 1)
        require(rotationDegrees in setOf(0, 90, 180, 270))
    }
}

object ImageNormalizationPolicy {
    /**
     * Conservative send edge. 4096 would need a 64 MB ARGB buffer at peak on a low-memory phone;
     * 2048 keeps the peak near 16 MB while staying well above what models actually need.
     */
    const val MAX_SEND_EDGE = 2048

    /** JPEG for opaque photos; PNG keeps transparency, which JPEG would silently destroy. */
    fun outputMimeTypeFor(inputMimeType: String): String =
        if (AttachmentFormats.normalizeMimeType(inputMimeType) == AttachmentFormats.MIME_PNG) {
            AttachmentFormats.MIME_PNG
        } else {
            AttachmentFormats.MIME_JPEG
        }

    /**
     * @param exifOrientation the EXIF orientation tag (1..8); anything else is treated as 1.
     */
    fun plan(
        inputMimeType: String,
        width: Int,
        height: Int,
        exifOrientation: Int,
        maxEdge: Int = MAX_SEND_EDGE,
    ): ImageNormalizationPlan {
        require(width > 0 && height > 0) { "An image needs positive dimensions" }
        require(maxEdge > 0)

        val orientation = if (exifOrientation in 2..8) exifOrientation else 1
        val swapsAxes = orientation in 5..8
        val orientedWidth = if (swapsAxes) height else width
        val orientedHeight = if (swapsAxes) width else height

        val (rotation, flipHorizontal, flipVertical) = when (orientation) {
            2 -> Triple(0, true, false)
            3 -> Triple(180, false, false)
            4 -> Triple(0, false, true)
            5 -> Triple(90, true, false)
            6 -> Triple(90, false, false)
            7 -> Triple(270, true, false)
            8 -> Triple(270, false, false)
            else -> Triple(0, false, false)
        }

        val longest = maxOf(orientedWidth, orientedHeight)
        var sampleSize = 1
        while (longest / (sampleSize * 2) >= maxEdge) sampleSize *= 2

        val scaledWidth: Int
        val scaledHeight: Int
        if (longest > maxEdge) {
            val ratio = maxEdge.toDouble() / longest.toDouble()
            scaledWidth = maxOf(1, Math.round(orientedWidth * ratio).toInt())
            scaledHeight = maxOf(1, Math.round(orientedHeight * ratio).toInt())
        } else {
            scaledWidth = orientedWidth
            scaledHeight = orientedHeight
        }

        val requiresTransform = orientation != 1 || longest > maxEdge
        return ImageNormalizationPlan(
            outputMimeType = outputMimeTypeFor(inputMimeType),
            outputWidth = scaledWidth,
            outputHeight = scaledHeight,
            sampleSize = sampleSize,
            rotationDegrees = rotation,
            flipHorizontal = flipHorizontal,
            flipVertical = flipVertical,
            requiresTransform = requiresTransform,
        )
    }
}
