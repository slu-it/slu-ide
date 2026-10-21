package dev.sluide.ide

import dev.sluide.git.GitHead
import java.nio.file.Path
import java.util.Properties

private const val APP_NAME = "Thoreau"

/** The version Thoreau was built as, from Spring Boot's build info; `null` if it is missing. */
internal val APP_VERSION: String? =
    MainWindow::class.java.getResourceAsStream("/META-INF/build-info.properties")?.use { stream ->
        Properties().apply { load(stream) }.getProperty("build.version")
    }

/** The app name with the [version], if known: `Thoreau v1.2.3`. */
internal fun appTitle(version: String? = APP_VERSION): String = APP_NAME + (version?.let { " v$it" } ?: "")

/**
 * The window title: the folder's name, the checked-out branch (or `detached at` and the short commit) in brackets if
 * [head] is known, and the [appTitle]; just the app title without a [folder].
 */
internal fun windowTitle(
    folder: Path?,
    head: GitHead?,
    version: String? = APP_VERSION,
): String {
    if (folder == null) return appTitle(version)
    val name = folder.fileName ?: folder
    val ref = head?.branch?.let { " [$it]" } ?: head?.shortCommit?.let { " [detached at $it]" } ?: ""
    return "$name$ref – ${appTitle(version)}"
}
