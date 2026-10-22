package dev.sluide.previews.workflow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

/** The workflows of this repository and the parser bundle's fixtures, read from the source tree. */
internal object WorkflowFiles {
    /** The repository's root: the folder with `settings.gradle.kts` above the working directory (the module's). */
    private val root: Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { it.resolve("settings.gradle.kts").exists() }

    val repository: List<Path> = listOf("ci.yml", "release.yml", "macos-ui-tests.yml").map(::workflow)

    val fixtures: List<Path> =
        Files.list(root.resolve("modules/previews/src/main/js/workflow-parser/fixtures")).use { files ->
            files.filter { it.name.endsWith(".yml") }.sorted().toList()
        }

    fun workflow(name: String): Path = root.resolve(".github/workflows").resolve(name)

    /** The small repository of `github-actions`' tests with a reusable workflow and local actions. */
    val nested: Path = root.resolve("modules/github-actions/src/test/resources/dev/sluide/githubactions/nested")

    fun fixture(name: String): Path = fixtures.single { it.name == name }
}
