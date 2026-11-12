package com.cordiact.core.attachment

import kotlin.test.*
class AttachmentPolicyTest {
    @Test fun defaultsAndLowerValuesAreAllowed() {
        assertEquals(AttachmentPolicy.safe, AttachmentPolicy.validate(AttachmentLimits()))
        assertEquals(3, AttachmentPolicy.validate(AttachmentLimits(maxAttachmentsPerMessage = 3)).maxAttachmentsPerMessage)
    }
    @Test fun increasesAndInvalidDimensionsAreRejected() {
        assertFailsWith<IllegalArgumentException> { AttachmentPolicy.validate(AttachmentLimits(maxImageBytes = 20L * 1024 * 1024)) }
        assertFailsWith<IllegalArgumentException> { AttachmentPolicy.validate(AttachmentLimits(maxImagePixels = 0)) }
        assertFailsWith<IllegalArgumentException> { AttachmentPolicy.validate(AttachmentLimits(maxImageEdge = -1)) }
        assertFailsWith<IllegalArgumentException> { AttachmentPolicy.validate(AttachmentLimits(maxPdfPages = 101)) }
    }
    @Test fun requestBudgetIsNotUserEditable() {
        assertFailsWith<IllegalArgumentException> { AttachmentPolicy.validate(AttachmentLimits(maxRawRequestBytes = 1)) }
    }
}
