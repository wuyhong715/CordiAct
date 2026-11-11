package com.cordiact.core.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentFormatsTest {
    @Test
    fun normalizesMimeTypes() {
        assertEquals("image/png", AttachmentFormats.normalizeMimeType(" Image/PNG ; charset=binary"))
        assertEquals("text/plain", AttachmentFormats.normalizeMimeType("text/plain"))
        assertEquals("", AttachmentFormats.normalizeMimeType(""))
        assertEquals("", AttachmentFormats.normalizeMimeType("not a mime"))
    }

    @Test
    fun classifiesMedia() {
        assertEquals(AttachmentMediaKind.IMAGE, AttachmentFormats.kindOf("image/png"))
        assertEquals(AttachmentMediaKind.PDF, AttachmentFormats.kindOf("application/pdf"))
        assertEquals(AttachmentMediaKind.TEXT, AttachmentFormats.kindOf("text/markdown"))
        assertEquals(AttachmentMediaKind.TEXT, AttachmentFormats.kindOf("application/json"))
        assertEquals(AttachmentMediaKind.BINARY, AttachmentFormats.kindOf("application/zip"))
    }

    @Test
    fun sniffsSupportedSignatures() {
        assertEquals(AttachmentFormats.MIME_PNG, AttachmentFormats.sniff(pngHeader(10, 20)))
        assertEquals(AttachmentFormats.MIME_JPEG, AttachmentFormats.sniff(jpegHeader(30, 40)))
        assertEquals(AttachmentFormats.MIME_WEBP, AttachmentFormats.sniff(webpHeader(50, 60)))
        assertEquals(AttachmentFormats.MIME_PDF, AttachmentFormats.sniff("%PDF-1.7\n".toByteArray()))
        assertNull(AttachmentFormats.sniff("hello".toByteArray()))
    }

    @Test
    fun rejectsMismatchedDeclaredType() {
        val failure = assertFailsWith<AttachmentException> {
            AttachmentFormats.verifyDeclaredType("image/png", "%PDF-1.7".toByteArray(), 8)
        }
        assertEquals(AttachmentErrorCode.INVALID_MEDIA_TYPE, failure.code)
    }

    @Test
    fun acceptsValidPngAndPdf() {
        AttachmentFormats.verifyDeclaredType("image/png", pngHeader(10, 20), 24)
        AttachmentFormats.verifyDeclaredType("application/pdf", "%PDF-1.7\n".toByteArray(), 9)
    }

    @Test
    fun validatesUtf8Strictly() {
        assertEquals("héllo", AttachmentFormats.decodeUtf8OrNull("héllo".toByteArray()))
        assertNull(AttachmentFormats.decodeUtf8OrNull(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun detectsBinaryText() {
        assertTrue(AttachmentFormats.looksBinary(byteArrayOf(0, 65)))
        assertFalse(AttachmentFormats.looksBinary("hello\nworld\t!".toByteArray()))
    }

    @Test
    fun readsImageDimensionsFromHeaders() {
        assertEquals(ImageDimensions(10, 20), AttachmentFormats.readImageDimensions(pngHeader(10, 20), 24))
        assertEquals(ImageDimensions(30, 40), AttachmentFormats.readImageDimensions(jpegHeader(30, 40)))
        assertEquals(ImageDimensions(50, 60), AttachmentFormats.readImageDimensions(webpHeader(50, 60)))
    }

    @Test
    fun sanitizesDisplayNames() {
        assertEquals("passwd", AttachmentNames.sanitizeDisplayName("../../etc/passwd"))
        assertEquals("a_b", AttachmentNames.sanitizeDisplayName("a\u0000b"))
        assertEquals("attachment", AttachmentNames.sanitizeDisplayName("   "))
        assertTrue(AttachmentNames.sanitizeDisplayName("x".repeat(300)).length <= AttachmentNames.MAX_LENGTH)
    }

    @Test
    fun capabilityFailsClosedByDefault() {
        val unsupported = AttachmentInputCapability.UNSUPPORTED
        assertFalse(unsupported.declared)
        assertFalse(unsupported.supports("image/png", Interpretation.VISION))
    }

    @Test
    fun capabilitySupportsWildcards() {
        val capability = AttachmentInputCapability(
            interpretations = setOf(Interpretation.TEXT_EXTRACT),
            mimeTypes = setOf("text/*"),
            maxFileBytes = 1000,
            maxRequestBytes = 2000,
            maxCount = 2,
        )
        assertTrue(capability.supports("text/markdown", Interpretation.TEXT_EXTRACT))
        assertFalse(capability.supports("image/png", Interpretation.VISION))
        assertFalse(capability.supports("text/plain", Interpretation.VISION))
    }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(33)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes, 0)
        bytes[11] = 0x0D
        "IHDR".toByteArray().copyInto(bytes, 12)
        writeIntBigEndian(bytes, 16, width)
        writeIntBigEndian(bytes, 20, height)
        return bytes
    }

    private fun jpegHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(32)
        bytes[0] = 0xFF.toByte(); bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte(); bytes[3] = 0xE0.toByte()
        bytes[4] = 0x00; bytes[5] = 0x10
        bytes[20] = 0xFF.toByte(); bytes[21] = 0xC0.toByte()
        bytes[22] = 0x00; bytes[23] = 0x11
        bytes[24] = 0x08
        bytes[25] = (height shr 8).toByte(); bytes[26] = height.toByte()
        bytes[27] = (width shr 8).toByte(); bytes[28] = width.toByte()
        return bytes
    }

    private fun webpHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(30)
        "RIFF".toByteArray().copyInto(bytes, 0)
        writeIntLittleEndian(bytes, 4, 22)
        "WEBP".toByteArray().copyInto(bytes, 8)
        "VP8X".toByteArray().copyInto(bytes, 12)
        writeIntLittleEndian(bytes, 16, 10)
        writeLittleEndian24(bytes, 24, width - 1)
        writeLittleEndian24(bytes, 27, height - 1)
        return bytes
    }

    private fun writeIntBigEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun writeIntLittleEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
    }

    private fun writeLittleEndian24(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
    }
}
