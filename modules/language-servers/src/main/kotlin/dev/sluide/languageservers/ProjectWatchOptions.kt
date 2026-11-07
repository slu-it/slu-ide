package dev.sluide.languageservers

/**
 * Which folders of the project the window's language servers watch for [FileChange]s (ADR 0142): every folder below
 * the project folder except hidden ones (`.git`, `.venv`, `.gradle`…), links, those named in [excludedDirectories]
 * anywhere and those named in [excludedTopLevelDirectories] directly in the project folder (a Python package may be
 * called `build`), at most [maxDirectories] of them.
 */
data class ProjectWatchOptions(
    val excludedDirectories: Set<String> = setOf("node_modules", "__pycache__", "venv"),
    val excludedTopLevelDirectories: Set<String> = setOf("build", "target", "dist"),
    val maxDirectories: Int = defaultMaxDirectories(System.getProperty("os.name").orEmpty()),
) {
    companion object {
        /**
         * The cap on watched folders for the OS named [osName]: the macOS JDK lists every watched folder again about
         * every 2 s (ADR 0006), so it watches 300 there; inotify costs nothing while quiet, so 2,000 elsewhere.
         */
        fun defaultMaxDirectories(osName: String): Int = if (osName.startsWith("Mac", ignoreCase = true)) 300 else 2_000
    }
}
