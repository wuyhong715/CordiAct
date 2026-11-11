package com.cordiact.core.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessagePartsJsonTest {
    @Test
    fun roundTripsOrderedParts() {
        val parts = listOf(
            MessagePart.Text("分析这张图"),
            MessagePart.Attachment(AttachmentId("att-1"), Interpretation.VISION),
            MessagePart.Attachment(AttachmentId("att-2"), Interpretation.NATIVE_DOCUMENT, "r-1"),
        )
        val json = MessagePartsJson.encode(parts)
        assertEquals(parts, MessagePartsJson.decode(json))
    }

    @Test
    fun rejectsUnknownSchemaVersion() {
        val failure = assertFailsWith<AttachmentException> {
            MessagePartsJson.decode("""{"v":99,"parts":[]}""")
        }
        assertEquals(AttachmentErrorCode.INVALID_MEDIA_TYPE, failure.code)
    }

    @Test
    fun rejectsUnknownPartKind() {
        val failure = assertFailsWith<AttachmentException> {
            MessagePartsJson.decode("""{"v":1,"parts":[{"kind":"video","id":"x"}]}""")
        }
        assertEquals(AttachmentErrorCode.INVALID_MEDIA_TYPE, failure.code)
    }

    @Test
    fun plainTextProjectionKeepsOnlyText() {
        val parts = listOf(
            MessagePart.Text("one"),
            MessagePart.Attachment(AttachmentId("att-1"), Interpretation.VISION),
            MessagePart.Text("two"),
        )
        assertEquals("one\ntwo", MessageParts.plainText(parts))
        assertTrue(MessageParts.attachmentParts(parts).size == 1)
    }

    @Test
    fun emptyTextPartIsRejected() {
        assertFailsWith<IllegalArgumentException> { MessagePart.Text("") }
    }
}
