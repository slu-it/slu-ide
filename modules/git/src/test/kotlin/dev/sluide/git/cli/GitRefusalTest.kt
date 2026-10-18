package dev.sluide.git.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitRefusalTest {
    @Test
    fun `refuses names the JVM cannot pass in its locale`() {
        assertEquals(
            "'Gr\u00fc\u00dfe' cannot be passed to git in this locale.",
            refusal("Gr\u00fc\u00dfe", Charsets.US_ASCII),
        )
        assertEquals(null, refusal("Gr\u00fc\u00dfe", Charsets.UTF_8))
        assertEquals(null, refusal("feature/a-1", Charsets.US_ASCII))
        assertEquals("An empty name cannot be passed to git.", refusal("", Charsets.UTF_8))
        assertEquals("'-a' cannot be passed to git: it starts with '-'.", refusal("-a", Charsets.UTF_8))
        assertFalse(canPassArgument("\u00e4", Charsets.US_ASCII))
        assertTrue(canPassArgument("\u00e4", Charsets.ISO_8859_1))
    }
}
