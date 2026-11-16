package com.cordiact.app.experience

import com.cordiact.core.conversation.firstUserSentenceTitle
import com.cordiact.core.conversation.normalizeSessionTitle
import org.junit.Assert.*
import org.junit.Test

class SessionTitleTest {
    @Test fun titlesUseFirstSentenceOrFirstLine() {
        assertEquals("你好 世界。", firstUserSentenceTitle("  你好   世界。第二句"))
        assertEquals("Hello!", firstUserSentenceTitle("Hello! Next sentence"))
        assertEquals("第一行", firstUserSentenceTitle("第一行\n第二行"))
        assertEquals("附件对话", firstUserSentenceTitle(" \n\t"))
        assertEquals(120, firstUserSentenceTitle("长".repeat(200)).length)
        assertFalse(firstUserSentenceTitle("长".repeat(119) + "😀").last().isHighSurrogate())
    }
    @Test fun manualTitlesValidateAndNormalize() {
        assertEquals("手动标题", normalizeSessionTitle("  手动标题  "))
        for (bad in listOf("", "x\ny", "x\u2028y", "长".repeat(121))) {
            assertThrows(IllegalArgumentException::class.java) { normalizeSessionTitle(bad) }
        }
    }
}
