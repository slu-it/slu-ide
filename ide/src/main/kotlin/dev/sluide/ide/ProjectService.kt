package dev.sluide.ide

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/** Holds the folder that is currently opened as project. Observe [projectFolder] to react to changes. */
@Service
class ProjectService {

    private val _projectFolder = ReadOnlyObjectWrapper<Path?>()

    val projectFolder: ReadOnlyObjectProperty<Path?> = _projectFolder.readOnlyProperty

    fun open(folder: Path) {
        require(Files.isDirectory(folder)) { "Not a directory: $folder" }
        _projectFolder.set(folder.toAbsolutePath().normalize())
    }
}
