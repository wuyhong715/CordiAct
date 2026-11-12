package com.cordiact.app.experience.settings

import com.cordiact.core.conversation.ModelId
import org.junit.Assert.*
import org.junit.Test

class ModelRoleSelectionTest {
    private val a = ModelId("a", "same")
    private val b = ModelId("b", "same")
    @Test fun explicitReferenceUsesProviderAndModelAndNeverFallsBack() {
        assertEquals(ModelRoleResolution.Resolved(a), ModelRoleSelection.Explicit(a).resolve(setOf(a, b), b))
        assertTrue(ModelRoleSelection.Explicit(a).resolve(setOf(b), b) is ModelRoleResolution.Invalid)
    }
    @Test fun followIsExplicitAndNeedsAnAvailableConversationModel() {
        assertEquals(ModelRoleResolution.Resolved(b), ModelRoleSelection.FollowConversation.resolve(setOf(a, b), b))
        assertTrue(ModelRoleSelection.FollowConversation.resolve(setOf(a), b) is ModelRoleResolution.Invalid)
        assertTrue(ModelRoleSelection.FollowConversation.resolve(setOf(a), null) is ModelRoleResolution.Invalid)
    }
    @Test fun disabledNeverBecomesFollow() {
        assertEquals(ModelRoleResolution.Disabled, ModelRoleSelection.Disabled.resolve(setOf(a), a))
    }
    @Test fun errorsNeverEchoCredentialsOrResponseBodies() {
        val secret = "do-not-display-this-secret"
        assertFalse(settingsError(IllegalStateException("401 $secret")).contains(secret))
        assertFalse(settingsError(RuntimeException(secret)).contains(secret))
        assertTrue(settingsError(IllegalStateException("404 $secret")).contains("端点"))
    }
}
