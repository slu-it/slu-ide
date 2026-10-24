package dev.sluide.gradle.detect

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class GradleLocatorTest {
    @TempDir
    lateinit var root: Path

    private fun wrapper(
        folder: Path,
        executable: Boolean = true,
        properties: Boolean = true,
    ) {
        folder.createDirectories()
        folder.resolve("gradlew").apply {
            writeText("#!/bin/sh\n")
            setPosixFilePermissions(PosixFilePermissions.fromString(if (executable) "rwxr-xr-x" else "rw-r--r--"))
        }
        if (properties) {
            folder
                .resolve("gradle/wrapper")
                .createDirectories()
                .resolve("gradle-wrapper.properties")
                .writeText("x=1")
        }
    }

    private fun buildFile(folder: Path): Path =
        folder.createDirectories().resolve("build.gradle").also { it.writeText("") }

    private fun locate(
        file: Path,
        projectRoot: Path? = root,
        global: () -> Boolean = { false },
    ) = GradleLocator.locate(file, projectRoot, global)

    @Test
    fun `a wrapper in the folder of the build file is found`() {
        wrapper(root)
        assertEquals(GradleInstallation.Wrapper(root, true), locate(buildFile(root)))
    }

    @Test
    fun `the nearest wrapper above the build file wins`() {
        wrapper(root)
        wrapper(root.resolve("a"))
        assertEquals(GradleInstallation.Wrapper(root.resolve("a"), true), locate(buildFile(root.resolve("a/b/c"))))
    }

    @Test
    fun `a wrapper in the project root is found from a deep folder`() {
        wrapper(root)
        assertEquals(GradleInstallation.Wrapper(root, true), locate(buildFile(root.resolve("a/b"))))
    }

    @Test
    fun `a wrapper above the project root is ignored`() {
        val project = root.resolve("outer/project")
        wrapper(root.resolve("outer"))
        assertEquals(GradleInstallation.None, locate(buildFile(project), project))
    }

    @Test
    fun `a folder without the wrapper properties is no wrapper`() {
        wrapper(root, properties = false)
        assertEquals(GradleInstallation.None, locate(buildFile(root)))
    }

    @Test
    fun `a gradlew that is not executable is reported so`() {
        wrapper(root, executable = false)
        assertEquals(GradleInstallation.Wrapper(root, false), locate(buildFile(root)))
    }

    @Test
    fun `a build file outside the project root gets nothing, not even the global gradle`() {
        val project = root.resolve("project").createDirectories()
        assertEquals(GradleInstallation.None, locate(buildFile(root.resolve("other")), project, global = { true }))
    }

    @Test
    fun `a sibling folder with the name of the root as prefix is outside it`() {
        val project = root.resolve("proj").createDirectories()
        assertEquals(GradleInstallation.None, locate(buildFile(root.resolve("proj2")), project, global = { true }))
    }

    @Test
    fun `no project root gets nothing`() {
        assertEquals(GradleInstallation.None, locate(buildFile(root), projectRoot = null, global = { true }))
    }

    @Test
    fun `a parent path in the build file does not escape the root`() {
        val project = root.resolve("project").createDirectories()
        wrapper(root)
        assertEquals(GradleInstallation.None, locate(project.resolve("../build.gradle"), project, global = { true }))
    }

    @Test
    fun `without a wrapper the global gradle is used if there is one`() {
        assertEquals(GradleInstallation.Global, locate(buildFile(root), global = { true }))
        assertEquals(GradleInstallation.None, locate(buildFile(root), global = { false }))
    }

    @ParameterizedTest
    @ValueSource(strings = [".hidden/sub", "build/gen", "a/.gradle", "node_modules/x", "out"])
    fun `a build file in a hidden or skipped folder gets nothing, not even the global gradle`(folder: String) {
        wrapper(root)
        assertEquals(GradleInstallation.None, locate(buildFile(root.resolve(folder)), global = { true }))
    }

    @Test
    fun `a build file in a symlinked folder gets nothing`() {
        wrapper(root.resolve("project"))
        val outside = root.resolve("outside").createDirectories()
        val project = root.resolve("project")
        val link = Files.createSymbolicLink(project.resolve("link"), outside)
        assertEquals(GradleInstallation.None, locate(link.resolve("build.gradle"), project, global = { true }))
    }

    @Test
    fun `the depth below the project root is limited like the trust check's`() {
        wrapper(root)
        val deepest = root.resolve("1/2/3/4/5/6/7/8")
        assertEquals(GradleInstallation.Wrapper(root, true), locate(buildFile(deepest)))
        assertEquals(GradleInstallation.None, locate(buildFile(deepest.resolve("9")), global = { true }))
    }

    @Test
    fun `the global check is not asked when a wrapper is found`() {
        wrapper(root)
        locate(buildFile(root), global = { error("asked") })
    }
}
