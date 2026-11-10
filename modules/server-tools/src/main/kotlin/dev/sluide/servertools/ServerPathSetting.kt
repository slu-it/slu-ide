package dev.sluide.servertools

import dev.sluide.settings.SettingsStore
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import java.io.IOException

/**
 * The server executable the user chose instead of Thoreau's download: an absolute path stored under [key] in the
 * [SettingsStore] (`python.ty.path`); empty means Thoreau's download. A language module's settings bean extends it.
 * [apply] stores it; the property belongs to the FX thread, [currentPath] can be read from any thread.
 */
open class ServerPathSetting(
    private val store: SettingsStore,
    private val key: String,
) {
    private val pathWrapper = ReadOnlyStringWrapper(store.load()[key]?.trim() ?: "")

    @Volatile private var pathInUse = pathWrapper.get()

    /** The custom executable; empty means Thoreau's download. */
    val path: ReadOnlyStringProperty = pathWrapper.readOnlyProperty

    fun currentPath(): String = pathInUse

    /**
     * Stores the path (trimmed) and then sets it; FX thread.
     *
     * @throws IOException if it cannot be stored; it is then not changed either.
     */
    @Throws(IOException::class)
    fun apply(executable: String) {
        val trimmed = executable.trim()
        if (trimmed == pathInUse) return
        store.update(mapOf(key to trimmed))
        pathInUse = trimmed
        pathWrapper.set(trimmed)
    }
}
