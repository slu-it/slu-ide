package dev.sluide.gradle.tasks

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitScriptTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the init script is extracted and replaced`() {
        val file = extractInitScript(dir.resolve("sub"))
        assertTrue("sluideTaskList" in file.readText())
        file.writeText("old")
        assertEquals(file, extractInitScript(dir.resolve("sub")))
        assertTrue("sluideTaskList" in file.readText())
    }
}
