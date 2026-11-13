package dev.sluide.gitlog.table

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitMenuEntriesTest {
    private fun entries(
        selected: Int = 1,
        hasHead: Boolean = true,
        onBranch: Boolean = true,
        running: Boolean = false,
        busy: Boolean = false,
    ) = commitMenuEntries(selected, hasHead, onBranch, running, busy)

    private fun ids(sections: List<List<ShownCommitEntry>>) = sections.map { section -> section.map { it.entry } }

    private fun disabled(sections: List<List<ShownCommitEntry>>) =
        sections
            .flatten()
            .filter { !it.enabled }
            .map { it.entry }
            .toSet()

    @Test
    fun `one commit shows four sections in order`() {
        assertEquals(
            listOf(
                listOf(CommitMenuEntry.REVIEW, CommitMenuEntry.CHECKOUT_REVISION, CommitMenuEntry.COPY_REVISION_NUMBER),
                listOf(CommitMenuEntry.CHERRY_PICK, CommitMenuEntry.REVERT, CommitMenuEntry.RESET),
                listOf(CommitMenuEntry.NEW_BRANCH, CommitMenuEntry.NEW_TAG),
                listOf(CommitMenuEntry.COMPARE_WITH_LOCAL, CommitMenuEntry.EDIT_MESSAGE),
            ),
            ids(entries()),
        )
        assertTrue(disabled(entries()).isEmpty())
    }

    @Test
    fun `several commits show Review and Copy only, in the plural`() {
        val sections = entries(selected = 3)
        assertEquals(listOf(listOf(CommitMenuEntry.REVIEW, CommitMenuEntry.COPY_REVISION_NUMBER)), ids(sections))
        assertEquals(listOf("Review Commits", "Copy Revision Numbers"), sections.flatten().map { it.text })
        assertTrue(disabled(sections).isEmpty())
    }

    @Test
    fun `one commit has the singular texts`() {
        val texts = entries().flatten().associate { it.entry to it.text }
        assertEquals("Review Commit", texts[CommitMenuEntry.REVIEW])
        assertEquals("Copy Revision Number", texts[CommitMenuEntry.COPY_REVISION_NUMBER])
        assertEquals("Cherry-Pick", texts[CommitMenuEntry.CHERRY_PICK])
        assertEquals("Revert Commit", texts[CommitMenuEntry.REVERT])
    }

    @Test
    fun `without a HEAD commit there is no Reset and no Edit Commit Message`() {
        val shown = ids(entries(hasHead = false)).flatten()
        assertTrue(CommitMenuEntry.RESET !in shown)
        assertTrue(CommitMenuEntry.EDIT_MESSAGE !in shown)
        assertTrue(CommitMenuEntry.CHERRY_PICK in shown)
    }

    @Test
    fun `a detached HEAD has no Edit Commit Message but a Reset`() {
        val shown = ids(entries(onBranch = false)).flatten()
        assertTrue(CommitMenuEntry.EDIT_MESSAGE !in shown)
        assertTrue(CommitMenuEntry.RESET in shown)
    }

    @Test
    fun `a running operation greys out everything that writes`() {
        assertEquals(
            setOf(
                CommitMenuEntry.CHECKOUT_REVISION,
                CommitMenuEntry.NEW_BRANCH,
                CommitMenuEntry.NEW_TAG,
                CommitMenuEntry.RESET,
                CommitMenuEntry.CHERRY_PICK,
                CommitMenuEntry.REVERT,
                CommitMenuEntry.EDIT_MESSAGE,
            ),
            disabled(entries(running = true)),
        )
    }

    @Test
    fun `a pending merge or pick greys out Cherry-Pick, Revert and Edit Commit Message only`() {
        assertEquals(
            setOf(CommitMenuEntry.CHERRY_PICK, CommitMenuEntry.REVERT, CommitMenuEntry.EDIT_MESSAGE),
            disabled(entries(busy = true)),
        )
    }

    @Test
    fun `no selection shows nothing`() {
        assertTrue(entries(selected = 0).isEmpty())
    }
}
