package com.cordiact.app.experience.composer

import com.cordiact.app.experience.GlyphName

/** Small registry instead of a hard-coded menu: future input sources register a real handler. */
data class ComposerAction(val id: String, val label: String, val glyph: GlyphName, val invoke: () -> Unit)
class ComposerActionRegistry {
    private val actions = linkedMapOf<String, ComposerAction>()
    fun register(action: ComposerAction): ComposerActionRegistry = apply {
        require(action.id.isNotBlank())
        require(!actions.containsKey(action.id)) { "Duplicate composer action ${action.id}" }
        actions[action.id] = action
    }
    fun actions(): List<ComposerAction> = actions.values.toList()
}
