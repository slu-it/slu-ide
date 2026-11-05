package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.actions.launch
import dev.sluide.fileexplorer.actions.systemFileManager
import java.io.IOException
import java.nio.file.Path

/** The name of the system's file manager for messages: Finder, Explorer or "the file manager" (ADR 0093). */
val fileManagerName: String get() = systemFileManager().name

/** Opens [folder] in the system's file manager without waiting for it; throws [IOException] if it cannot start. */
fun openInFileManager(folder: Path) = launch(systemFileManager().command(folder))
