package com.cordiact.core.attachment

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * MIME normalisation, P0 format allow-listing, magic-number sniffing and header-only dimension
 * reading.
 *
 * Nothing here trusts a file name or a declared MIME type. Sniffing is a gate, not an anti-virus
 * scan: it proves the bytes start like the declared format, and nothing more.
 */
object AttachmentFormats {
    const val MIME_PNG = "image/png"
    const val MIME_JPEG = "image/jpeg"
    const val MIME_WEBP = "image/webp"
    const val MIME_PDF = "application/pdf"
    const val MIME_TEXT = "text/plain"

    /** P0 allow-list. Everything else is importable only as [AttachmentMediaKind.BINARY] (local only). */
    private val SUPPORTED_IMAGES = setOf(MIME_PNG, MIME_JPEG, MIME_WEBP)

    /** `application/…` types that are text by nature, so they can be projected as text. */
    private val TEXT_APPLICATION_TYPES = setOf(
        "application/json",
        "application/xml",
        "application/yaml",
        "application/x-yaml",
        "application/toml",
        "application/javascript",
        "application/typescript",
        "application/x-sh",
        "application/sql",
        "application/graphql",
        "application/x-httpd-php",
        "application/rtf",
    )

    /** Removes parameters, trims and lower-cases; returns "" for a blank input. */
    fun normalizeMimeType(raw: String): String {
        val withoutParameters = raw.substringBefore(';').trim().lowercase()
        if (withoutParameters.isEmpty()) return ""
        val slash = withoutParameters.indexOf('/')
        if (slash <= 0 || slash == withoutParameters.lastIndex) return ""
        if (withoutParameters.any { it.isWhitespace() }) return ""
        return withoutParameters
    }

    fun kindOf(mimeType: String): AttachmentMediaKind {
        val normalized = normalizeMimeType(mimeType)
        return when {
            normalized.isEmpty() -> AttachmentMediaKind.BINARY
            normalized == MIME_PDF -> AttachmentMediaKind.PDF
            normalized.startsWith("image/") -> AttachmentMediaKind.IMAGE
            normalized.startsWith("text/") -> AttachmentMediaKind.TEXT
            normalized in TEXT_APPLICATION_TYPES -> AttachmentMediaKind.TEXT
            normalized.endsWith("+json") || normalized.endsWith("+xml") || normalized.endsWith("+yaml") ->
                AttachmentMediaKind.TEXT
            else -> AttachmentMediaKind.BINARY
        }
    }

    /** True for the formats P0 can send (static image, PDF, text). */
    fun isSupportedInP0(mimeType: String): Boolean {
        val normalized = normalizeMimeType(mimeType)
        return normalized in SUPPORTED_IMAGES ||
            normalized == MIME_PDF ||
            kindOf(normalized) == AttachmentMediaKind.TEXT
    }

    /** The media kind a byte prefix actually looks like, or null when no P0 signature matches. */
    fun sniff(bytes: ByteArray, length: Int = bytes.size): String? {
        val available = minOf(length, bytes.size)
        if (available >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) {
            return MIME_PNG
        }
        if (available >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return MIME_JPEG
        }
        if (available >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return MIME_WEBP
        }
        if (available >= 5 &&
            bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[4] == '-'.code.toByte()
        ) {
            return MIME_PDF
        }
        return null
    }

    /** Strict UTF-8 decoding; returns null when the bytes are not valid UTF-8 text. */
    fun decodeUtf8OrNull(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * Validates a declared MIME type against the bytes that were actually read.
     *
     * Throws [AttachmentException] with [AttachmentErrorCode.INVALID_MEDIA_TYPE] rather than
     * "fixing" the type from the file name.
     */
    fun verifyDeclaredType(declaredMimeType: String, head: ByteArray, headLength: Int) {
        val declared = normalizeMimeType(declaredMimeType)
        if (declared.isEmpty()) {
            throw AttachmentException(
                AttachmentErrorCode.INVALID_MEDIA_TYPE,
                "The source did not report a usable MIME type",
            )
        }
        if (headLength <= 0) {
            throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "The source is empty")
        }
        when (kindOf(declared)) {
            AttachmentMediaKind.IMAGE -> {
                val sniffed = sniff(head, headLength)
                if (sniffed == null || kindOf(sniffed) != AttachmentMediaKind.IMAGE) {
                    throw AttachmentException(
                        AttachmentErrorCode.INVALID_MEDIA_TYPE,
                        "The file does not start like the declared image type $declared",
                    )
                }
                if (declared in SUPPORTED_IMAGES && sniffed != declared) {
                    throw AttachmentException(
                        AttachmentErrorCode.INVALID_MEDIA_TYPE,
                        "Declared $declared but the bytes are $sniffed",
                    )
                }
                if (declared !in SUPPORTED_IMAGES) {
                    throw AttachmentException(
                        AttachmentErrorCode.UNSUPPORTED_MIME,
                        "$declared images are not supported yet; convert to PNG, JPEG or WebP",
                    )
                }
            }

            AttachmentMediaKind.PDF -> {
                if (sniff(head, headLength) != MIME_PDF) {
                    throw AttachmentException(
                        AttachmentErrorCode.INVALID_MEDIA_TYPE,
                        "The file does not start with a PDF header",
                    )
                }
            }

            AttachmentMediaKind.TEXT -> {
                if (sniff(head, headLength) != null) {
                    throw AttachmentException(
                        AttachmentErrorCode.INVALID_MEDIA_TYPE,
                        "Declared a text type but the bytes are a binary format",
                    )
                }
                if (looksBinary(head, headLength)) {
                    throw AttachmentException(
                        AttachmentErrorCode.INVALID_MEDIA_TYPE,
                        "Declared a text type but the bytes contain NUL or control characters",
                    )
                }
            }

            AttachmentMediaKind.BINARY -> Unit
        }
    }

    /** NUL bytes and C0 controls other than TAB/LF/CR/FF mean "not text". */
    fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val available = minOf(length, bytes.size)
        for (index in 0 until available) {
            val value = bytes[index].toInt() and 0xFF
            if (value == 0) return true
            if (value < 0x09) return true
            if (value in 0x0E..0x1F) return true
            if (value == 0x7F) return true
        }
        return false
    }

    /** Best-effort PDF encryption check over the leading bytes; used to refuse, never to accept. */
    fun looksEncryptedPdf(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val text = String(bytes, 0, minOf(length, bytes.size), StandardCharsets.ISO_8859_1)
        return text.contains("/Encrypt")
    }

    /**
     * Header-only image dimensions. Returns null when the header cannot be parsed; callers must
     * then refuse the file instead of decoding it blindly.
     */
    fun readImageDimensions(bytes: ByteArray, length: Int = bytes.size): ImageDimensions? {
        val available = minOf(length, bytes.size)
        val mime = sniff(bytes, available) ?: return null
        return when (mime) {
            MIME_PNG -> readPngDimensions(bytes, available)
            MIME_JPEG -> readJpegDimensions(bytes, available)
            MIME_WEBP -> readWebpDimensions(bytes, available)
            else -> null
        }
    }

    private fun readPngDimensions(bytes: ByteArray, available: Int): ImageDimensions? {
        // 8-byte signature, 4-byte length, "IHDR", then width/height as big-endian ints.
        if (available < 24) return null
        if (!(bytes[12] == 'I'.code.toByte() && bytes[13] == 'H'.code.toByte() &&
                bytes[14] == 'D'.code.toByte() && bytes[15] == 'R'.code.toByte())
        ) {
            return null
        }
        val width = readIntBigEndian(bytes, 16)
        val height = readIntBigEndian(bytes, 20)
        return if (width > 0 && height > 0) ImageDimensions(width, height) else null
    }

    private fun readJpegDimensions(bytes: ByteArray, available: Int): ImageDimensions? {
        var index = 2
        while (index + 9 < available) {
            if ((bytes[index].toInt() and 0xFF) != 0xFF) {
                index++
                continue
            }
            val marker = bytes[index + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                index += 2
                continue
            }
            if (index + 3 >= available) return null
            val segmentLength = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
            if (segmentLength < 2) return null
            val isStartOfFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isStartOfFrame) {
                if (index + 8 >= available) return null
                val height = ((bytes[index + 5].toInt() and 0xFF) shl 8) or (bytes[index + 6].toInt() and 0xFF)
                val width = ((bytes[index + 7].toInt() and 0xFF) shl 8) or (bytes[index + 8].toInt() and 0xFF)
                return if (width > 0 && height > 0) ImageDimensions(width, height) else null
            }
            index += 2 + segmentLength
        }
        return null
    }

    private fun readWebpDimensions(bytes: ByteArray, available: Int): ImageDimensions? {
        if (available < 30) return null
        val chunk = String(bytes, 12, 4, StandardCharsets.ISO_8859_1)
        return when (chunk) {
            "VP8X" -> {
                val width = 1 + (readLittleEndian24(bytes, 24))
                val height = 1 + (readLittleEndian24(bytes, 27))
                if (width > 0 && height > 0) ImageDimensions(width, height) else null
            }

            "VP8 " -> {
                val width = readIntLittleEndian16(bytes, 26) and 0x3FFF
                val height = readIntLittleEndian16(bytes, 28) and 0x3FFF
                if (width > 0 && height > 0) ImageDimensions(width, height) else null
            }

            "VP8L" -> {
                if (available < 25) return null
                val bits = (bytes[21].toInt() and 0xFF) or
                    ((bytes[22].toInt() and 0xFF) shl 8) or
                    ((bytes[23].toInt() and 0xFF) shl 16) or
                    ((bytes[24].toInt() and 0xFF) shl 24)
                val width = (bits and 0x3FFF) + 1
                val height = ((bits shr 14) and 0x3FFF) + 1
                if (width > 0 && height > 0) ImageDimensions(width, height) else null
            }

            else -> null
        }
    }

    private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readIntLittleEndian16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLittleEndian24(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}

data class ImageDimensions(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
    val longestEdge: Int get() = maxOf(width, height)
}

/** Display-name hygiene: no paths, no control characters, no bidi overrides, bounded length. */
object AttachmentNames {
    const val MAX_LENGTH = 120

    fun sanitizeDisplayName(raw: String?): String {
        val source = raw.orEmpty()
        val withoutPath = source.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (character in withoutPath) {
                val isForbidden = character.isISOControl() ||
                    character == '\u202A' || character == '\u202B' ||
                    character == '\u202C' || character == '\u202D' ||
                    character == '\u202E' || character == '\u2066' ||
                    character == '\u2067' || character == '\u2068' ||
                    character == '\u2069'
                append(if (isForbidden) '_' else character)
            }
        }.trim().trim('.')
        val bounded = if (cleaned.length > MAX_LENGTH) cleaned.take(MAX_LENGTH) else cleaned
        return bounded.ifBlank { "attachment" }
    }
}
