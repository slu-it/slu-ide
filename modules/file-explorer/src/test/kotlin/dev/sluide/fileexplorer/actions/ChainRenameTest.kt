package dev.sluide.fileexplorer.actions

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChainRenameTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `new names for the folders of a chain are checked one by one, each in its own folder`() {
        val chain = listOf(root.resolve("src").createDirectories(), root.resolve("src/main").createDirectories())
        root.resolve("source").createDirectories()
        root.resolve("src/taken").createDirectories()

        assertNull(chainNameProblem("src/main", chain))
        assertNull(chainNameProblem("source2/other", chain))
        assertEquals("“source” already exists in this folder.", chainNameProblem("source/main", chain))
        assertEquals("“taken” already exists in this folder.", chainNameProblem("src/taken", chain))
        assertEquals("The name must not contain / or \\.", chainNameProblem("src/a\\b", chain))
    }

    @Test
    fun `there must be as many names as folders`() {
        val chain = listOf(root.resolve("src").createDirectories(), root.resolve("src/main").createDirectories())
        val message = "Keep the 2 folder names separated by “/”: folders cannot be added or removed here."

        assertEquals(message, chainNameProblem("src", chain))
        assertEquals(message, chainNameProblem("src/main/kotlin", chain))
    }

    @Test
    fun `a folder without a parent cannot be renamed`() {
        val failure = assertFailsWith<IllegalStateException> { chainNameProblem("b", listOf(Path.of("lonely"))) }

        assertEquals("lonely has no folder", failure.message)
    }
}
