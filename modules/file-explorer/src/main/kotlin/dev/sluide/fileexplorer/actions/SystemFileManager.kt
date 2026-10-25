package dev.sluide.fileexplorer.actions

import java.io.IOException
import java.nio.file.Path

/**
 * The file manager of the operating system: the menu [label], its [name] for messages, and the [command] opening a
 * folder. [revealLabel] and [reveal] show a file in its folder: the commands to try in order, the first that starts
 * and succeeds wins (ADR 0093).
 */
internal data class SystemFileManager(
    val label: String,
    val name: String,
    val revealLabel: String,
    val command: (Path) -> List<String>,
    val reveal: (Path) -> List<List<String>>,
)

/** The file manager for [osName]: Finder on macOS, Explorer on Windows, else whatever `xdg-open` opens. */
internal fun systemFileManager(osName: String = System.getProperty("os.name")): SystemFileManager {
    val os = osName.lowercase()
    return when {
        os.contains("mac") -> {
            SystemFileManager(
                "Open in Finder",
                "Finder",
                "Reveal in Finder",
                { listOf("open", it.toString()) },
                { listOf(listOf("open", "-R", it.toString())) },
            )
        }

        os.contains("win") -> {
            // Untested on Windows. One argument, "/select,<file>": Java quotes it as a whole if the path has a space.
            SystemFileManager(
                "Open in Explorer",
                "Explorer",
                "Reveal in Explorer",
                { listOf("explorer.exe", it.toString()) },
                { listOf(listOf("explorer.exe", "/select,$it")) },
            )
        }

        else -> {
            SystemFileManager(
                "Open in File Manager",
                "the file manager",
                "Reveal in File Manager",
                { listOf("xdg-open", it.toString()) },
                { file ->
                    listOf(
                        listOf(
                            "dbus-send",
                            "--session",
                            "--print-reply",
                            "--reply-timeout=5000",
                            "--dest=org.freedesktop.FileManager1",
                            "--type=method_call",
                            "/org/freedesktop/FileManager1",
                            "org.freedesktop.FileManager1.ShowItems",
                            // dbus-send splits an array at every comma, so one in the name is encoded.
                            "array:string:${file.toUri().toString().replace(",", "%2C")}",
                            "string:",
                        ),
                        listOf("xdg-open", (file.parent ?: file).toString()),
                    )
                },
            )
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

/**
 * Runs the [commands] in order until one succeeds. Each but the last is waited for and counts as failed if it cannot
 * start or exits non-zero; the last is only started, like [launch], so a file manager staying in the foreground does
 * not block the thread. Returns null on success, else the failure of the last command. Blocks: not for the JavaFX
 * thread.
 */
internal fun launchFirstSuccess(commands: List<List<String>>): IOException? {
    var failure: IOException? = null
    for ((index, command) in commands.withIndex()) {
        try {
            if (index == commands.lastIndex) {
                launch(command)
                return null
            }
            val status =
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor()
            if (status == 0) return null
            failure = IOException("${command.first()} exited with status $status")
        } catch (e: IOException) {
            failure = e
        }
    }
    return failure
}
