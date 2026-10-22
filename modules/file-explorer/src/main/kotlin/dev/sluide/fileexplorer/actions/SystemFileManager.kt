package dev.sluide.fileexplorer.actions

import java.io.IOException
import java.nio.file.Path

/** The file manager of the operating system: the menu [label], its [name] for messages, and the [command] opening a folder. */
internal data class SystemFileManager(
    val label: String,
    val name: String,
    val command: (Path) -> List<String>,
)

/** The file manager for [osName]: Finder on macOS, Explorer on Windows, else whatever `xdg-open` opens. */
internal fun systemFileManager(osName: String = System.getProperty("os.name")): SystemFileManager {
    val os = osName.lowercase()
    return when {
        os.contains("mac") -> {
            SystemFileManager("Open in Finder", "Finder") { listOf("open", it.toString()) }
        }

        os.contains("win") -> {
            SystemFileManager("Open in Explorer", "Explorer") { listOf("explorer.exe", it.toString()) }
        }

        else -> {
            SystemFileManager("Open in File Manager", "the file manager") { listOf("xdg-open", it.toString()) }
        }
    }
}

/** Starts [command] without waiting for it and discards its output; throws [IOException] if it cannot start. */
internal fun launch(command: List<String>) {
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}
