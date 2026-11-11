package com.cordiact.core.attachment

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AttachmentImporterTest {
    private fun store(): LocalAttachmentBlobStore =
        LocalAttachmentBlobStore(Files.createTempDirectory("importer").toFile())

    @Test
    fun importsUtf8Text() {
        val bytes = "hello attachment".toByteArray()
        val store = store()
        val result = AttachmentImporter(store).import(
            AttachmentImportRequest("ws-1", "notes.txt", "text/plain", 1000L),
            AttachmentSource { ByteArrayInputStream(bytes) },
        )
        assertEquals(AttachmentState.READY, result.descriptor.state)
        assertEquals("text/plain", result.descriptor.mimeType)
        assertEquals(bytes.size.toLong(), result.descriptor.byteSize)
        assertEquals(64, result.descriptor.sha256.length)
        assertContentEquals(bytes, store.open("ws-1", result.blobKey).use { it.readBytes() })
    }

    @Test
    fun rejectsInvalidUtf8() {
        val failure = assertFailsWith<AttachmentException> {
            AttachmentImporter(store()).import(
                AttachmentImportRequest("ws-1", "bad.txt", "text/plain", 1000L),
                AttachmentSource { ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)) },
            )
        }
        assertEquals(AttachmentErrorCode.INVALID_MEDIA_TYPE, failure.code)
    }

    @Test
    fun importsPngWithDimensions() {
        val bytes = pngHeader(640, 480)
        val result = AttachmentImporter(store()).import(
            AttachmentImportRequest("ws-1", "photo.png", "image/png", 1000L),
            AttachmentSource { ByteArrayInputStream(bytes) },
        )
        assertEquals(640, result.descriptor.width)
        assertEquals(480, result.descriptor.height)
    }

    @Test
    fun rejectsPngWhoseBytesAreNotPng() {
        val failure = assertFailsWith<AttachmentException> {
            AttachmentImporter(store()).import(
                AttachmentImportRequest("ws-1", "fake.png", "image/png", 1000L),
                AttachmentSource { ByteArrayInputStream("not a png".toByteArray()) },
            )
        }
        assertEquals(AttachmentErrorCode.INVALID_MEDIA_TYPE, failure.code)
    }

    @Test
    fun rejectsOversizedText() {
        val limits = AttachmentLimits(maxTextBytes = 4, maxTextTotalBytes = 4)
        val failure = assertFailsWith<AttachmentException> {
            AttachmentImporter(store(), limits).import(
                AttachmentImportRequest("ws-1", "long.txt", "text/plain", 1000L),
                AttachmentSource { ByteArrayInputStream("12345".toByteArray()) },
            )
        }
        assertEquals(AttachmentErrorCode.FILE_TOO_LARGE, failure.code)
    }

    @Test
    fun rejectsEncryptedPdf() {
        val bytes = "%PDF-1.7\n1 0 obj << /Encrypt 2 0 R >>".toByteArray()
        val failure = assertFailsWith<AttachmentException> {
            AttachmentImporter(store()).import(
                AttachmentImportRequest("ws-1", "secret.pdf", "application/pdf", 1000L),
                AttachmentSource { ByteArrayInputStream(bytes) },
            )
        }
        assertEquals(AttachmentErrorCode.INVALID_MEDIA_TYPE, failure.code)
    }

    @Test
    fun sanitizesNamesAndGeneratesOpaqueIds() {
        val result = AttachmentImporter(store()).import(
            AttachmentImportRequest("ws-1", "../../etc/passwd", "text/plain", 1000L),
            AttachmentSource { ByteArrayInputStream("x".toByteArray()) },
        )
        assertEquals("passwd", result.descriptor.displayName)
        assertTrue(result.descriptor.id.value.startsWith("att-"))
        assertTrue(result.descriptor.id.value.none { it == '/' || it == '.' })
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

    private fun writeIntBigEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
