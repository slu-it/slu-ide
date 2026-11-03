package dev.sluide.secrets.settings

import dev.sluide.secrets.FakeKeyBackend
import dev.sluide.secrets.KeyRead
import dev.sluide.secrets.NoBackend
import dev.sluide.secrets.SavedSecretEntry
import dev.sluide.secrets.SavedSecretSource
import dev.sluide.secrets.Secrets
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretsSettingsPageModelTest {
    private class FakeSource(
        override val title: String,
        var ids: List<String>,
        var result: Boolean = true,
    ) : SavedSecretSource {
        val removed = mutableListOf<Set<String>>()

        override fun entries() = ids.map { SavedSecretEntry(it, "label $it", "detail $it") }

        override fun remove(ids: Set<String>): Boolean {
            removed += ids
            return result
        }
    }

    private val backend = FakeKeyBackend()
    private val secrets = Secrets(backend, SecureRandom())
    private val first = FakeSource("First", listOf("a", "b"))
    private val second = FakeSource("Second", listOf("c"))
    private val empty = FakeSource("Empty", emptyList())
    private val model = SecretsSettingsPageModel(secrets) { listOf(first, empty, second) }

    @Test
    fun `the store line for each state`() {
        assertEquals("Store: Fake Store", model.storeLine())
        assertFalse(model.denied())

        backend.unavailable = "needs secret-tool"
        assertEquals("Not available: needs secret-tool", model.storeLine())
        assertFalse(model.denied())
    }

    @Test
    fun `access denied is shown with Try Again, which leaves the denied state`() {
        backend.onRead = { KeyRead.Denied("Keychain access denied") }
        secrets.decrypt(VALUE, "x")

        assertEquals("Store: Fake Store, access denied", model.storeLine())
        assertTrue(model.denied())

        model.tryAgain()

        assertEquals("Store: Fake Store", model.storeLine())
        assertFalse(model.denied())
    }

    @Test
    fun `removals are applied per source on apply only`() {
        model.load()
        assertEquals(listOf("First", "Second"), model.sections.map { it.source.title }, "sources without entries")
        assertTrue(model.toggle(0, "b"))
        assertTrue(model.toggle(1, "c"))
        assertFalse(model.toggle(1, "c"), "a second toggle unmarks")
        assertTrue(first.removed.isEmpty())

        model.apply()

        assertEquals(listOf(setOf("b")), first.removed)
        assertTrue(second.removed.isEmpty())
    }

    @Test
    fun `load drops the pending removals`() {
        model.load()
        model.toggle(0, "a")

        model.load()
        model.apply()

        assertTrue(first.removed.isEmpty())
        assertFalse(model.isPending(0, "a"))
    }

    @Test
    fun `a source that cannot remove fails the apply`() {
        first.result = false
        model.load()
        model.toggle(0, "a")
        model.toggle(1, "c")

        val failure = assertFailsWith<IOException> { model.apply() }

        assertTrue("First" in failure.message.orEmpty())
        assertTrue(model.isPending(0, "a"), "the failed source keeps its marks")
        assertFalse(model.isPending(1, "c"), "the applied one does not")
        assertEquals(listOf(setOf("c")), second.removed, "the other source is still applied")
    }

    @Test
    fun `a source that throws counts as failed`() {
        val throwing =
            object : SavedSecretSource by first {
                override fun remove(ids: Set<String>): Boolean = throw IllegalStateException("disk")
            }
        val thrown = SecretsSettingsPageModel(secrets) { listOf(throwing) }
        thrown.load()
        thrown.toggle(0, "a")

        assertFailsWith<IOException> { thrown.apply() }
        assertTrue(thrown.isPending(0, "a"))
    }

    @Test
    fun `forget all is off without a usable store and on when available or denied`() {
        assertTrue(model.canForgetAll())

        backend.unavailable = "needs secret-tool"
        assertFalse(model.canForgetAll())
        assertFalse(SecretsSettingsPageModel(Secrets(NoBackend, SecureRandom())) { emptyList() }.canForgetAll())

        backend.unavailable = null
        backend.onRead = { KeyRead.Denied("Keychain access denied") }
        secrets.decrypt(VALUE, "x")
        assertTrue(model.canForgetAll())
    }

    @Test
    fun `forget all reports the result`() {
        assertEquals("Saved secrets forgotten.", model.forgetAll())

        backend.deleted = false
        assertEquals("The key could not be deleted from Fake Store.", model.forgetAll())
    }

    private companion object {
        /** Well formed, so that decrypting asks the store for the key. */
        const val VALUE = "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
