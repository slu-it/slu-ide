package dev.sluide.git.parse

import dev.sluide.git.cli.refs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefspecDestinationTest {
    @Test
    fun `tells where refspecs write`() {
        assertEquals(
            listOf(
                RefspecDestination("refs/remotes/origin/", glob = true),
                RefspecDestination("refs/remotes/origin/pr-", glob = true),
                RefspecDestination("refs/notes/origin", glob = false),
            ),
            refspecDestinations(
                listOf(
                    "+refs/heads/*:refs/remotes/origin/*",
                    "refs/pull/*/head:refs/remotes/origin/pr-*",
                    "refs/notes/commits:refs/notes/origin",
                    "^refs/heads/secret",
                    "refs/heads/main",
                ),
            ),
        )
        assertTrue(RefspecDestination("refs/remotes/origin/", glob = true).matches("refs/remotes/origin/a/b"))
        assertFalse(RefspecDestination("refs/notes/origin", glob = false).matches("refs/notes/origin2"))
    }
}
