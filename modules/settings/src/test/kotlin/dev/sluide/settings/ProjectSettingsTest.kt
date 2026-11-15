package dev.sluide.settings

import javafx.scene.control.Label
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectSettingsTest {
    private class Page(
        override val path: List<String>,
    ) : ProjectSettingsPage {
        override val node get() = Label()

        override fun load(folder: Path) {}

        override fun apply(folder: Path) {}
    }

    @Test
    fun `pages are ordered by path`() {
        val java = Page(listOf("Java"))
        val build = Page(listOf("Build", "Gradle"))
        val buildLonger = Page(listOf("Build Extras"))

        val pages = ProjectSettings(listOf(java, buildLonger, build)).pages

        assertEquals(listOf(build, buildLonger, java), pages)
    }

    @Test
    fun `works with no pages, and show does nothing until it is set`() {
        val settings = ProjectSettings(emptyList())
        assertEquals(emptyList(), settings.pages)

        settings.show(listOf("Java"))
        settings.show(null)
    }
}
