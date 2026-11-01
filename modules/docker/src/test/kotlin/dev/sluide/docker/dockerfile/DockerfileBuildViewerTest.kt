package dev.sluide.docker.dockerfile

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewers
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which files [DockerfileBuildViewer] opens, and that its `Dockerfile.<x>` pattern ranks below a viewer for `.x`, as
 * the code viewer it replaced did.
 */
class DockerfileBuildViewerTest {
    @TempDir
    lateinit var dir: Path

    private val viewer by lazy { DockerfileBuildViewer() }
    private val fallback = DefaultTextFileViewer()

    /** A viewer of the previews module for files with the [fileExtensions], like the Markdown and YAML previews. */
    private class ExtensionsStub(
        override val fileExtensions: Set<String>,
    ) : ExtensionFileViewer {
        override fun canOpen(file: Path) = file.extension.lowercase() in fileExtensions

        override fun open(file: Path): FileView = error("not needed")
    }

    private val markdown = ExtensionsStub(setOf("md"))

    /** Each name in a folder of its own, for case-insensitive file systems. */
    private fun file(name: String): Path =
        dir
            .resolve(name.hashCode().toString())
            .createDirectories()
            .resolve(name)
            .apply { writeText("FROM alpine\n") }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Dockerfile", "Containerfile", "dockerfile", "CONTAINERFILE", "Dockerfile.dev", "dockerfile.prod.local",
            "app.Dockerfile", "web.dockerfile", "Dockerfile.yaml",
        ],
    )
    fun `opens Dockerfiles by name, extension and pattern, ignoring case`(name: String) {
        assertTrue(viewer.canOpen(file(name)), name)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Dockerfile.dockerignore", "Dockerfile.dev.dockerignore", ".dockerignore", "my.Dockerfile.bak",
            "Dockerfile-dev", "notes.txt", "compose.yaml", "Containerfile.dev",
        ],
    )
    fun `leaves other files to other viewers`(name: String) {
        assertFalse(viewer.canOpen(file(name)), name)
    }

    @Test
    fun `does not open a missing or binary Dockerfile`() {
        assertFalse(viewer.canOpen(dir.resolve("Dockerfile")))
        assertFalse(viewer.canOpen(dir.resolve("app.Dockerfile").apply { writeBytes(byteArrayOf(0, 1, 2, 0)) }))
    }

    @Test
    fun `its Dockerfile dot something pattern loses to a viewer for the extension, in any order`() {
        // The whole ranking is FileViewersTest's (with a stand-in) and SpringWiringTest's (among all viewers).
        for (order in listOf(listOf(viewer, markdown), listOf(markdown, viewer))) {
            assertSame(markdown, FileViewers(order, fallback).viewerFor(file("Dockerfile.md")))
        }
    }
}
