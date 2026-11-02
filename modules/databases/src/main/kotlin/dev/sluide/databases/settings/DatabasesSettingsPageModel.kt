package dev.sluide.databases.settings

import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty

/**
 * The edit on the Databases page: three draft texts, applied to [settings] only by [apply]. A draft that is not a
 * number keeps the current value; numbers outside the range are clamped by the settings.
 */
internal class DatabasesSettingsPageModel(
    private val settings: DatabasesSettings,
) {
    val pageSize: StringProperty = SimpleStringProperty(settings.pageSize.get().toString())
    val fetchAllCap: StringProperty = SimpleStringProperty(settings.fetchAllCap.get().toString())
    val statementTimeoutSeconds: StringProperty =
        SimpleStringProperty(settings.statementTimeoutSeconds.get().toString())

    /** Drops the edit: the drafts show the current values again. */
    fun load() {
        pageSize.set(settings.pageSize.get().toString())
        fetchAllCap.set(settings.fetchAllCap.get().toString())
        statementTimeoutSeconds.set(settings.statementTimeoutSeconds.get().toString())
    }

    /** Stores the drafts and shows the resulting (clamped) values. */
    fun apply() {
        settings.apply(
            number(pageSize, settings.pageSize.get()),
            number(fetchAllCap, settings.fetchAllCap.get()),
            number(statementTimeoutSeconds, settings.statementTimeoutSeconds.get()),
        )
        load()
    }

    private fun number(
        draft: StringProperty,
        current: Int,
    ): Int = draft.get()?.trim()?.toIntOrNull() ?: current
}
