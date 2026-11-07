package dev.sluide.python

import dev.sluide.settings.SettingsStore
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import org.springframework.stereotype.Component
import java.io.IOException

private const val TY_PATH_KEY = "python.ty.path"

/**
 * The Python settings: the ty executable the user chose (`python.ty.path`, an absolute path; empty: Thoreau's
 * download). [apply] stores it in the [SettingsStore]; the property belongs to the FX thread, [currentTyPath] can be
 * read from any thread.
 */
@Component
class PythonSettings(
    private val store: SettingsStore,
) {
    private val tyPathWrapper = ReadOnlyStringWrapper(store.load()[TY_PATH_KEY]?.trim() ?: "")

    @Volatile private var tyPathInUse = tyPathWrapper.get()

    /** The custom ty executable; empty means Thoreau's download. */
    val tyPath: ReadOnlyStringProperty = tyPathWrapper.readOnlyProperty

    fun currentTyPath(): String = tyPathInUse

    /**
     * Stores the path (trimmed) and then sets it; FX thread.
     *
     * @throws IOException if it cannot be stored; it is then not changed either.
     */
    @Throws(IOException::class)
    fun apply(tyPath: String) {
        val path = tyPath.trim()
        if (path == tyPathInUse) return
        store.update(mapOf(TY_PATH_KEY to path))
        tyPathInUse = path
        tyPathWrapper.set(path)
    }
}
