package dev.sluide.gradle.view

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.languages.GroovyFileViewer
import dev.sluide.fileviewers.languages.KotlinFileViewer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GradleBuildFileViewersTest {
    @TempDir
    lateinit var dir: Path

    private val kotlinBuild = GradleKotlinFileViewer()
    private val groovyBuild = GradleGroovyFileViewer()
    private val kotlin = KotlinFileViewer()
    private val groovy = GroovyFileViewer()
    private val viewers = FileViewers(listOf(kotlin, groovy, kotlinBuild, groovyBuild), DefaultTextFileViewer())

    private fun file(name: String) = dir.resolve(name).apply { parent.createDirectories() }.apply { writeText("") }

    @Test
    fun `build files open in the Gradle viewers by name, other Kotlin scripts and Gradle files do not`() {
        assertSame(kotlinBuild, viewers.viewerFor(file("build.gradle.kts")))
        assertSame(kotlinBuild, viewers.viewerFor(file("upper/BUILD.gradle.KTS")))
        assertSame(groovyBuild, viewers.viewerFor(file("app/build.gradle")))
        assertSame(kotlin, viewers.viewerFor(file("foo.kts")))
        assertSame(kotlin, viewers.viewerFor(file("settings.gradle.kts")))
        assertSame(groovy, viewers.viewerFor(file("x.gradle")))
        assertSame(groovy, viewers.viewerFor(file("settings.gradle")))
        assertTrue(viewers.showsDiffs(kotlinBuild) && viewers.showsDiffs(groovyBuild))
        assertSame(Highlighters.kotlin, kotlinBuild.sourceHighlighter)
        assertSame(Highlighters.groovy, groovyBuild.sourceHighlighter)
    }

    @Test
    fun `a binary file named like a build file is not opened`() {
        val binary = dir.resolve("build.gradle").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        assertFalse(groovyBuild.canOpen(binary))
        assertFalse(kotlinBuild.canOpen(dir.resolve("build.gradle.kts")), "missing")
    }
}
