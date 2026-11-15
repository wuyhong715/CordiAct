package com.cordiact.app.security

import com.cordiact.core.security.ToolEffectClass
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltInToolEffectClassifierTest {
    @Test fun restoredBuiltInsHaveExplicitEffectsAndUnknownsRemainDenied() {
        val classifier = BuiltInToolEffectClassifier()
        listOf("job_list", "job_output", "job_probe", "tool_output_read", "skill.open").forEach {
            assertEquals(it, ToolEffectClass.WORKSPACE_READ, classifier.classify(it, ""))
        }
        assertEquals(ToolEffectClass.COMMAND, classifier.classify("job_kill", ""))
        assertEquals(ToolEffectClass.DEVICE_READ, classifier.classify("screen", ""))
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify("unknown_tool", ""))
        assertEquals(ToolEffectClass.ROOT, classifier.classify("priv_root", ""))
    }
}
