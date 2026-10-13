package dev.sluide.ide

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * Holds the project folder of one window. It is set once: other folders open in other windows (ADR 0014). Observe
 * [projectFolder] to react to it.
 */
@Service
@Scope(WINDOW_SCOPE)
class ProjectService {
    private val _projectFolder = ReadOnlyObjectWrapper<Path?>()

    val projectFolder: ReadOnlyObjectProperty<Path?> = _projectFolder.readOnlyProperty

    fun open(folder: Path) {
        require(Files.isDirectory(folder)) { "Not a directory: $folder" }
        val path = folder.toAbsolutePath().normalize()
        val current = _projectFolder.get()
        check(current == null || current == path) { "This window already shows $current" }
        _projectFolder.set(path)
    }
}
