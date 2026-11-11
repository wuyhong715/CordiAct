package com.cordiact.app.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.cordiact.core.attachment.AttachmentErrorCode
import com.cordiact.core.attachment.AttachmentException
import com.cordiact.core.attachment.AttachmentFormats
import com.cordiact.core.attachment.ImageNormalizationPlan
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Re-encoded image bytes plus the dimensions the plan produced. */
data class NormalizedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

/**
 * Turns a stored image into a send-ready rendition: orientation applied, metadata dropped, long edge
 * bounded.
 *
 * Decoding follows the plan's sample size, so the largest bitmap in memory is roughly the target
 * size rather than the camera's full resolution. The original object is never modified.
 */
class AndroidImageNormalizer {

    fun readOrientation(source: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(source)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** True when the image carries GPS coordinates that must not leave the device. */
    fun hasLocationMetadata(source: ByteArray): Boolean = runCatching {
        ExifInterface(ByteArrayInputStream(source)).let { exif ->
            val latLong = FloatArray(2)
            exif.getLatLong(latLong) ||
                exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null ||
                exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) != null
        }
    }.getOrDefault(false)

    fun normalize(source: ByteArray, plan: ImageNormalizationPlan): NormalizedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw AttachmentException(
                AttachmentErrorCode.INVALID_MEDIA_TYPE,
                "The image could not be decoded; the file may be corrupt",
            )
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = plan.sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options)
            ?: throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "The image could not be decoded")
        var transformed: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val matrix = Matrix().apply {
                if (plan.rotationDegrees != 0) postRotate(plan.rotationDegrees.toFloat())
                if (plan.flipHorizontal) postScale(-1f, 1f)
                if (plan.flipVertical) postScale(1f, -1f)
            }
            transformed = if (plan.rotationDegrees != 0 || plan.flipHorizontal || plan.flipVertical) {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } else {
                decoded
            }
            scaled = if (transformed.width != plan.outputWidth || transformed.height != plan.outputHeight) {
                Bitmap.createScaledBitmap(transformed, plan.outputWidth, plan.outputHeight, true)
            } else {
                transformed
            }
            val format = if (plan.outputMimeType == AttachmentFormats.MIME_PNG) {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            val output = ByteArrayOutputStream()
            if (!scaled.compress(format, JPEG_QUALITY, output)) {
                throw AttachmentException(AttachmentErrorCode.STORAGE_FAILURE, "The image could not be re-encoded")
            }
            return NormalizedImage(output.toByteArray(), scaled.width, scaled.height, plan.outputMimeType)
        } finally {
            if (scaled != null && scaled !== transformed) scaled.recycle()
            if (transformed != null && transformed !== decoded) transformed.recycle()
            decoded.recycle()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 85
    }
}
