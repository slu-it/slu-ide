package dev.sluide.gitlog.area

import dev.sluide.git.GitStash
import javafx.beans.property.SimpleBooleanProperty
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** When the header's quick actions are off (ADRs 0063, 0181, 0182): one case per term, only that term deciding. */
class HeaderFlagsTest {
    private val flags = HeaderFlags()
    private val blocked = SimpleBooleanProperty(false)
    private var remotes: List<String>? = listOf("origin")
    private var stashes: List<GitStash>? = emptyList()
    private val reads = mutableListOf<Runnable>()

    private val quick =
        QuickActionsState(
            { remotes },
            { "refs/heads/main" },
            { "h1" },
            { stashes },
            { false },
            reads::add,
            Runnable::run,
        )

    private fun read() {
        quick.refresh()
        reads.toList().also { reads.clear() }.forEach(Runnable::run)
    }

    private fun stash(index: Int) = GitStash(index, "c$index", 1_700_000_000, "WIP")

    @Test
    fun `Stash is on with a repository and local changes while nothing blocks, and each term alone turns it off`() {
        flags.hasRepository.set(true)
        flags.localChanges.set(true)
        val off = flags.stashOff(blocked)
        assertEquals(false, off.get())

        blocked.set(true)
        assertEquals(true, off.get(), "blocked")
        blocked.set(false)

        flags.hasRepository.set(false)
        assertEquals(true, off.get(), "no repository")
        flags.hasRepository.set(true)

        flags.localChanges.set(false)
        assertEquals(true, off.get(), "no local changes")
        flags.localChanges.set(true)
        assertEquals(false, off.get())
    }

    @Test
    fun `Pop is on with a stash while nothing blocks, and each term alone turns it off`() {
        stashes = listOf(stash(0))
        quick.open(Path.of("/repo"))
        read()
        val off = flags.popOff(quick, blocked)
        assertEquals(false, off.get())

        blocked.set(true)
        assertEquals(true, off.get(), "blocked")
        blocked.set(false)

        stashes = emptyList()
        read()
        assertEquals(true, off.get(), "no stash")
    }

    @Test
    fun `Rebase is on with origin, a branch and a commit while nothing blocks, and each term alone turns it off`() {
        quick.open(Path.of("/repo"))
        read()
        val off = flags.rebaseOff(quick, blocked)
        assertEquals(false, off.get())

        blocked.set(true)
        assertEquals(true, off.get(), "blocked")
        blocked.set(false)

        remotes = emptyList()
        read()
        assertEquals(true, off.get(), "not possible")
    }

    @Test
    fun `New Branch is on with origin while nothing blocks, and each term alone turns it off`() {
        quick.open(Path.of("/repo"))
        read()
        val off = flags.newBranchOff(quick, blocked)
        assertEquals(false, off.get())

        blocked.set(true)
        assertEquals(true, off.get(), "blocked")
        blocked.set(false)

        remotes = emptyList()
        read()
        assertEquals(true, off.get(), "not possible")
    }
}
