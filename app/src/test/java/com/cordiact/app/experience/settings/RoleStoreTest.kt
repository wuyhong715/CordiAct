package com.cordiact.app.experience.settings

import android.app.Application
import com.cordiact.app.context.SummaryModelSelectionStore
import com.cordiact.app.memory.ExtractionModelSelectionStore
import com.cordiact.core.conversation.ModelId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class RoleStoreTest {
    @Test fun explicitFollowDisabledAndInvalidSurviveReopening() {
        val context = RuntimeEnvironment.getApplication()
        val model = ModelId("custom-test", "m")
        val summary = SummaryModelSelectionStore(context)
        summary.select(model)
        assertEquals(ModelRoleSelection.Explicit(model), SummaryModelSelectionStore(context).selection())
        assertTrue(summary.resolve(emptySet(), model) is ModelRoleResolution.Invalid)
        summary.selectFollowingConversation()
        assertEquals(ModelRoleSelection.FollowConversation, SummaryModelSelectionStore(context).selection())
        assertEquals(ModelRoleResolution.Resolved(model), summary.resolve(setOf(model), model))
        summary.clear()
        assertEquals(ModelRoleSelection.Disabled, SummaryModelSelectionStore(context).selection())
        val extraction = ExtractionModelSelectionStore(context)
        extraction.select(model)
        assertEquals(ModelRoleSelection.Explicit(model), ExtractionModelSelectionStore(context).selection())
        extraction.selectFollowingConversation()
        assertEquals(ModelRoleSelection.FollowConversation, ExtractionModelSelectionStore(context).selection())
        extraction.clear()
        assertEquals(ModelRoleSelection.Disabled, ExtractionModelSelectionStore(context).selection())
    }

    @Test fun roleRevisionsPersistAcrossInstancesAndModes() {
        val context = RuntimeEnvironment.getApplication()
        val model = ModelId("revision-test", "m")
        val summary = SummaryModelSelectionStore(context)
        val summaryBefore = summary.revision()
        summary.select(model); summary.selectFollowingConversation(); summary.clear()
        assertEquals(summaryBefore + 3, SummaryModelSelectionStore(context).revision())
        val extraction = ExtractionModelSelectionStore(context)
        val extractionBefore = extraction.revision()
        extraction.select(model); extraction.selectFollowingConversation(); extraction.clear()
        assertEquals(extractionBefore + 3, ExtractionModelSelectionStore(context).revision())
        val defaults = com.cordiact.app.model.ConversationModelSelectionStore(context)
        val before = defaults.revision()
        defaults.setDefault(model)
        assertEquals(before + 1, com.cordiact.app.model.ConversationModelSelectionStore(context).revision())
    }
}
