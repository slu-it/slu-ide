package dev.sluide.gradle.view

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.NamedFileViewer
import dev.sluide.fileviewers.SourceFileViewer
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.isTextFile
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shows a build file of one name as source beside the build's tasks and their runs ([GradleFileView], ADR 0085). The
 * name match beats the extension viewers (Kotlin for `.kts`, Groovy for `.gradle`).
 */
abstract class GradleBuildFileViewer internal constructor(
    fileName: String,
    final override val sourceHighlighter: SyntaxHighlighter,
) : SourceFileViewer,
    NamedFileViewer {
    final override val fileNames: Set<String> = setOf(fileName)

    final override fun canOpen(file: Path): Boolean {
        val name = file.fileName?.toString() ?: return false
        return fileNames.any { it.equals(name, ignoreCase = true) } && Files.isReadable(file) && isTextFile(file)
    }

    final override fun open(file: Path): FileView =
        GradleFileView(file, CodeEditors.open(file, sourceHighlighter, "//", "/*" to "*/"))
}

/** `build.gradle.kts`, highlighted as Kotlin. */
@Component
class GradleKotlinFileViewer : GradleBuildFileViewer("build.gradle.kts", Highlighters.kotlin)

/** `build.gradle`, highlighted as Groovy. */
@Component
class GradleGroovyFileViewer : GradleBuildFileViewer("build.gradle", Highlighters.groovy)
