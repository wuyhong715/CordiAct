package com.cordiact.core.workbench

enum class WorkbenchTheme { LIGHT, DARK, SYSTEM }

enum class WorkbenchAccent { AMBER, INDIGO, TEAL }

enum class PanelPlacement { TAB, DOCK, HIDDEN }

data class WorkbenchPanel(val id: String, val placement: PanelPlacement) {
    init {
        require(ID.matches(id)) { "Invalid workbench panel ID" }
    }

    private companion object {
        val ID = Regex("[a-z0-9._-]{1,48}")
    }
}

data class WorkbenchProfile(
    val name: String,
    val theme: WorkbenchTheme = WorkbenchTheme.LIGHT,
    val accent: WorkbenchAccent = WorkbenchAccent.AMBER,
    val panels: List<WorkbenchPanel> = emptyList(),
) {
    init {
        require(isValidName(name)) { "Invalid workbench profile name" }
        require(panels.size <= MAX_PANELS) { "Too many workbench panels" }
        require(panels.map { it.id }.distinct().size == panels.size) { "Duplicate workbench panel" }
    }

    /** Retain settings for plugins that are absent. New contributions start as tabs. */
    fun editorPanels(available: List<String>): List<WorkbenchPanel> {
        val configured = panels.mapTo(hashSetOf()) { it.id }
        return panels + available.filterNot { it in configured }.distinct().map { WorkbenchPanel(it, PanelPlacement.TAB) }
    }

    fun visiblePanels(available: List<String>, canDock: Boolean): List<WorkbenchPanel> {
        val published = available.toHashSet()
        return editorPanels(available).filter { it.id in published && it.placement != PanelPlacement.HIDDEN }
            .map { if (!canDock && it.placement == PanelPlacement.DOCK) it.copy(placement = PanelPlacement.TAB) else it }
    }

    companion object {
        const val MAX_PANELS = 128
        const val MAX_NAME_LENGTH = 48

        fun isValidName(name: String): Boolean = name.isNotBlank() && name.length <= MAX_NAME_LENGTH &&
            name.trim() == name && name.none { it.isISOControl() }
    }
}
