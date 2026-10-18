package dev.sluide.settings.pages

import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty

/** The edits on the Fonts page: one draft font per area, applied to [settings] only by [apply]. */
internal class FontsPageModel(
    private val settings: FontSettings,
) {
    val drafts: Map<FontArea, ObjectProperty<FontSpec>> =
        FontArea.entries.associateWith { SimpleObjectProperty(settings.font(it).get()) }

    fun draft(area: FontArea): ObjectProperty<FontSpec> = drafts.getValue(area)

    /** Drops the edits: every draft shows the current setting again. */
    fun load() = drafts.forEach { (area, draft) -> draft.set(settings.font(area).get()) }

    fun restoreDefaults(area: FontArea) = draft(area).set(settings.defaults.getValue(area))

    /** The drafts that differ from the current settings. */
    val changes: Map<FontArea, FontSpec>
        get() =
            drafts
                .mapValues { it.value.get() }
                .filter { (area, spec) -> spec != settings.font(area).get() }

    fun apply() = settings.apply(changes)
}
