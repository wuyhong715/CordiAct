package com.cordiact.core.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsWriterTest {
    @Test fun validatesBeforeCommitThenAuditsAndNotifies() {
        val calls = mutableListOf<String>()
        val writer = SettingsWriter({ calls += "audit:${it.revision}" }, { calls += "notify:${it.revision}" })
        val revision = writer.write("security:workspace", 7, object : SettingsMutation {
            override fun validate() { calls += "validate" }
            override fun commit(expectedRevision: Long): Long { calls += "commit"; return expectedRevision + 1 }
        })
        assertEquals(8L, revision)
        assertEquals(listOf("validate", "commit", "audit:8", "notify:8"), calls)
    }
    @Test fun failedValidationCannotCommit() {
        var committed = false
        assertThrows(IllegalArgumentException::class.java) {
            SettingsWriter({}).write("security", 1, object : SettingsMutation {
                override fun validate() { require(false) }
                override fun commit(expectedRevision: Long): Long { committed = true; return 2 }
            })
        }
        assertFalse(committed)
    }
    @Test fun auditFailureStillNotifiesAndIsNotSuccess() {
        var notified = false
        assertThrows(IllegalStateException::class.java) {
            SettingsWriter({ error("AUDIT_UNAVAILABLE") }, { notified = true }).write("security", 1, object : SettingsMutation {
                override fun validate() {}
                override fun commit(expectedRevision: Long) = 2L
            })
        }
        assertTrue(notified)
    }
}
