package dev.sluide.java

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class OutputIgnoreChecksTest {
    private val checks = OutputIgnoreChecks()
    private val window = Path.of("/work/app")
    private val folders = listOf(Path.of("/work/app/target"))

    private fun answer(folder: Path = window): List<Path>? {
        val answers = mutableListOf<List<Path>?>()
        checks.check(folder, folders) { answers += it }
        assertEquals(1, answers.size, "answers")
        return answers[0]
    }

    private fun registering(answer: List<Path>?): (List<Path>, (List<Path>?) -> Unit) -> Unit =
        { _, done -> done(answer) }

    @Test
    fun `the registered check answers for its window`() {
        checks.register(window, registering(folders))

        assertEquals(folders, answer())
    }

    @Test
    fun `no check registered answers null at once`() {
        assertEquals(null, answer())
    }

    @Test
    fun `the check gets the folders it was asked about`() {
        val asked = mutableListOf<List<Path>>()
        checks.register(window) { given, done ->
            asked += given
            done(emptyList())
        }

        answer()

        assertEquals(listOf(folders), asked)
    }

    @Test
    fun `the newest registration of a folder wins and the one before comes back when it closes`() {
        checks.register(window, registering(emptyList()))
        val newest = checks.register(window, registering(folders))
        assertEquals(folders, answer())

        newest.close()

        assertEquals(emptyList(), answer())
    }

    @Test
    fun `closing an older registration keeps the newest`() {
        val older = checks.register(window, registering(emptyList()))
        checks.register(window, registering(folders))

        older.close()

        assertEquals(folders, answer())
    }

    @Test
    fun `the same function registered twice is two registrations`() {
        val same = registering(folders)
        val first = checks.register(window, same)
        checks.register(window, same)

        first.close()

        assertEquals(folders, answer())
    }

    @Test
    fun `a window is found by its normalized folder only, not by another window's`() {
        checks.register(Path.of("/work/other/../app"), registering(folders))

        assertEquals(folders, answer())
        assertEquals(null, answer(Path.of("/work/other")))
    }
}
