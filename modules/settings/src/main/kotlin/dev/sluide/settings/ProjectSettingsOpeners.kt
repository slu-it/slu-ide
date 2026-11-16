package dev.sluide.settings

import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Where to open Project Settings for a folder, for code that is not a settings page (a language server's "Choose JDK…"
 * notice, ADR 0179). Each window registers its opener for its folder; the latest registration of a folder wins. FX
 * thread.
 */
@Component
class ProjectSettingsOpeners {
    private val openers = HashMap<Path, MutableList<(List<String>?) -> Unit>>()

    /** Registers [show] as the opener of [folder] until the result is closed; it takes the page path as `ProjectSettings.show` does. */
    fun register(
        folder: Path,
        show: (List<String>?) -> Unit,
    ): AutoCloseable {
        val key = folder.toAbsolutePath().normalize()
        val entry = Opener(show)
        openers.getOrPut(key) { mutableListOf() }.add(entry.call)
        return AutoCloseable {
            openers[key]?.removeAll { it === entry.call }
        }
    }

    /** Opens Project Settings of [folder] at [path] (null: the first page); false if no window registered it. */
    fun show(
        folder: Path,
        path: List<String>?,
    ): Boolean {
        val opener = openers[folder.toAbsolutePath().normalize()]?.lastOrNull() ?: return false
        opener(path)
        return true
    }

    /** Wraps the function so that two registrations of the same function object stay distinct. */
    private class Opener(
        show: (List<String>?) -> Unit,
    ) {
        val call: (List<String>?) -> Unit = { show(it) }
    }
}
