package com.cordiact.app.experience

import com.cordiact.app.experience.model.ToolActivity
import com.cordiact.app.experience.model.ToolPhase
import com.cordiact.app.experience.model.TranscriptEvent
import com.cordiact.app.experience.model.TranscriptTurn
import com.cordiact.app.experience.model.RunPhase
import com.cordiact.app.experience.model.reduce
import com.cordiact.core.conversation.ToolHistoryFormat
import com.cordiact.core.conversation.ToolHistoryProvenance
import com.cordiact.core.conversation.ToolHistorySource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolTracePresentationTest {
    @Test fun packageCardProvenanceRequiresAnExactDigest() {
        val digest = "a".repeat(64)
        assertEquals(identity.copy(packageSha256 = digest), decodeToolTraceProvenance(trace().put("packageSha256", digest)))
        assertNull(decodeToolTraceProvenance(trace().put("packageSha256", "latest")))
        assertNull(decodeToolTraceProvenance(trace().put("packageSha256", digest).put("unexpected", "field")))
    }
    private val identity = ToolHistoryProvenance("pkg.core-quick-notes", "extension.pkg.core-quick-notes.checklist",
        ToolHistorySource.HOST_REGISTRATION, ToolHistoryFormat.BOUNDED_SUMMARY_V1)
    private fun trace() = JSONObject().put("ownerPluginId", identity.ownerPluginId).put("toolId", identity.toolId)
        .put("source", identity.source.wireValue).put("format", identity.format.wireValue)

    @Test fun oldMalformedAndNewerHistoryNeverReceivesARecipeKey() {
        assertNull(decodeToolTraceProvenance(null))
        assertNull(decodeToolTraceProvenance(JSONObject().put("name", identity.toolId)))
        assertNull(decodeToolTraceProvenance(trace().put("format", "bounded-summary-v2")))
        assertNull(decodeToolTraceProvenance(trace().put("source", "provider")))
        assertNull(decodeToolTraceProvenance(trace().put("toolId", 42)))
        assertNull(decodeToolTraceProvenance(trace().put("ownerPluginId", "x\n")))
        assertNull(decodeToolTraceProvenance(trace().put("link", "https://example.org")))
        assertEquals(identity, decodeToolTraceProvenance(trace()))
    }

    @Test fun legacyCredentialDetailsAreNotDisplayedOrCopiedAndOtherDetailsAreBounded() {
        val sensitive = ToolActivity("a", "credential_write", arguments = "SECRET_SENTINEL", summary = "SECRET_SENTINEL",
            phase = ToolPhase.SUCCEEDED, startedAt = 0, provenance = identity).forToolInspector()
        assertEquals(ToolPhase.SUCCEEDED, sensitive.phase)
        assertFalse(sensitive.arguments.contains("SECRET_SENTINEL"))
        assertNull(sensitive.summary)
        assertNull(sensitive.provenance)
        val normal = ToolActivity("b", "example", arguments = "a".repeat(700), summary = "b".repeat(700), startedAt = 0)
            .forToolInspector()
        assertEquals(600, normal.arguments.length)
        assertEquals(600, normal.summary!!.length)
    }

    @Test fun sameNameCallsDoNotShareProvenanceAndFailuresHaveNone() {
        val turn = TranscriptTurn("turn", "", phase = RunPhase.RUNNING)
            .reduce(TranscriptEvent.ToolStarted("first", "same", "", 0))
            .reduce(TranscriptEvent.ToolStarted("second", "same", "", 0))
            .reduce(TranscriptEvent.ToolFinished("second", "same", true, "result", 1, 1, identity))
            .reduce(TranscriptEvent.ToolFinished("first", "same", false, "failure", 2, 2, identity))
        assertNull(turn.tools.first().provenance)
        assertEquals(identity, turn.tools.last().provenance)
    }
}
