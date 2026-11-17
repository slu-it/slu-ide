package dev.sluide.git

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitConflictCheckTest {
    @Test
    fun `a conflict marker starts a line, is as long as asked and is followed by a space or the line's end`() {
        fun marked(
            text: String,
            size: Int = 7,
        ) = hasConflictMarker(text.toByteArray(), size)

        assertTrue(marked("a\n<<<<<<< HEAD\nb\n"))
        assertTrue(marked(">>>>>>> 1a2b (x)"))
        assertTrue(marked("x\r\n<<<<<<<\r\n"))
        assertTrue(marked("<<<<<<<"))
        assertFalse(marked("=======\n"))
        assertFalse(marked(" <<<<<<< indented\n"))
        assertFalse(marked("<<<<<<<< eight\n"))
        assertFalse(marked("<<<<<<<x\n"))
        assertFalse(marked("<<<<<<< seven\n", size = 10))
        assertTrue(marked("<<<<<<<<<< ten\n", size = 10))
    }

    @Test
    fun `a line too short for a marker or a marker with other characters in it is no marker, and the scan ends`() {
        // A mutant that never moves on to the next line loops for good: the call must end.
        fun marked(text: String) =
            assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                ThrowingSupplier { hasConflictMarker(text.toByteArray(), 7) },
            )

        assertFalse(marked("<<<"))
        assertFalse(marked("<<<<<<= x\n"))
        assertFalse(marked("a\nb\nc\n"))
        assertTrue(marked("a\nb\n>>>>>>> c"))
    }

    @Test
    fun `a file is named by the JVM only if it can, and an invalid path is no file`() {
        val root = Path.of("root")

        assertEquals(root.resolve("a.txt"), localFile(root, "a.txt") { true })
        assertNull(localFile(root, "a.txt") { false })
        assertNull(localFile(root, "a\u0000b") { true })
    }

    @Test
    fun `the refusal names the paths one by one in the singular or the plural`() {
        fun refusal(
            submodules: List<String>,
            files: List<String>,
        ) = ConflictCheck(emptyList(), emptyList(), submodules, files).refusal

        assertEquals(null, refusal(emptyList(), emptyList()))
        assertEquals(
            "Submodule a is not checked out: the commit to keep cannot be taken from disk. Resolve it in the terminal.",
            refusal(listOf("a"), emptyList()),
        )
        assertEquals(
            "Submodules a, b are not checked out: the commit to keep cannot be taken from disk. " +
                "Resolve it in the terminal.",
            refusal(listOf("a", "b"), emptyList()),
        )
        assertEquals(
            "c is not a regular file (a folder or a special file), which git cannot stage. Resolve it in the terminal.",
            refusal(emptyList(), listOf("c")),
        )
        assertEquals(
            "c, d are not regular files (a folder or a special file), which git cannot stage. " +
                "Resolve it in the terminal.",
            refusal(emptyList(), listOf("c", "d")),
        )
    }

    @Test
    fun `what blocks a start says so for each operation git can be in the middle of`() {
        fun busy(operation: GitOperation) = busyMessage(GitPendingOperation(operation))

        assertEquals("A git am session is in progress; finish or abort it first.", busy(GitOperation.AM))
        assertEquals(
            "A cherry-pick sequence is in progress; continue or abort it in the terminal.",
            busy(GitOperation.CHERRY_PICK_SEQUENCE),
        )
        assertEquals(
            "A revert sequence is in progress; continue or abort it in the terminal.",
            busy(GitOperation.REVERT_SEQUENCE),
        )
        assertEquals(
            "A cherry-pick or revert sequence is in progress; continue or abort it in the terminal.",
            busy(GitOperation.SEQUENCE),
        )
    }

    @Test
    fun `what blocks a start says so, and the conflicts in the singular or the plural`() {
        assertEquals(
            "1 file has conflicts; resolve it and mark it resolved first.",
            busyMessage(GitPendingConflicts(listOf("a"))),
        )
        assertEquals(
            "2 files have conflicts; resolve them and mark them resolved first.",
            busyMessage(GitPendingConflicts(listOf("a", "b"))),
        )
    }
}
