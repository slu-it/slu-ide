package dev.sluide.gitlog.area

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import kotlin.test.Test
import kotlin.test.assertEquals

class RebaseEnablementTest {
    private val head = GitRef("HEAD", GitRefKind.HEAD, "h1")
    private val main = GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1")
    private val attached = GitRefs(listOf(head, main), "refs/heads/main")

    @Test
    fun `the rebase needs origin, a branch and a commit`() {
        assertEquals(true, rebaseOntoOriginPossible(listOf("upstream", "origin"), attached))
        assertEquals(false, rebaseOntoOriginPossible(listOf("upstream"), attached), "no origin")
        assertEquals(false, rebaseOntoOriginPossible(null, attached), "remotes unknown")
        assertEquals(false, rebaseOntoOriginPossible(listOf("origin"), null), "refs unknown")
        assertEquals(false, rebaseOntoOriginPossible(listOf("origin"), GitRefs(listOf(head, main), null)), "detached")
        assertEquals(
            false,
            rebaseOntoOriginPossible(listOf("origin"), GitRefs(listOf(main), "refs/heads/main")),
            "no HEAD commit",
        )
    }
}
