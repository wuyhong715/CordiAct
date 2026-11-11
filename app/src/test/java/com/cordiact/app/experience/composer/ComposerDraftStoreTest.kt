package com.cordiact.app.experience.composer

import android.app.Application
import android.content.Context
import com.cordiact.core.conversation.*
import com.cordiact.app.experience.GlyphName
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class ComposerDraftStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences("composer_drafts", 0).edit().clear().commit()
    }
    @Test fun sessionDraftsSurviveReloadWithoutLeakingIntoAnotherSession() {
        val first = ComposerDraft("第一份草稿", ModelId("provider", "model"), ReasoningEffort.MAX, ResponseSpeed.FAST)
        ComposerDraftStore(context).save("one", first)
        val second = ComposerDraft("第二份", ModelId("other", "small"), ReasoningEffort.NONE, ResponseSpeed.AUTO)
        ComposerDraftStore(context).save("two", second)
        val restored = ComposerDraftStore(context)
        assertEquals(first, restored.load("one"))
        assertEquals(second, restored.load("two"))
        restored.clear("one")
        assertEquals(ComposerDraft(), restored.load("one"))
        assertEquals(second, restored.load("two"))
    }
    @Test fun lastUsedNeverCarriesAnotherSessionsText() {
        val draft = ComposerDraft("private draft", ModelId("p", "m"), ReasoningEffort.MINIMAL)
        ComposerDraftStore(context).saveLastUsed(draft)
        assertEquals(draft.copy(text = ""), ComposerDraftStore(context).lastUsed())
    }
    @Test fun registryInvokesRealHandlersAndRejectsDuplicateIds() {
        var invoked = false
        val registry = ComposerActionRegistry().register(ComposerAction("photo", "照片", GlyphName.Image) { invoked = true })
        assertEquals(listOf("photo"), registry.actions().map { it.id })
        registry.actions().single().invoke()
        assertTrue(invoked)
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(ComposerAction("photo", "重复", GlyphName.Image) {})
        }
    }
}
