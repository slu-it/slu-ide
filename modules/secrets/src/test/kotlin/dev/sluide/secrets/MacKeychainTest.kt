package dev.sluide.secrets

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.UUID

/** Talks to the real login keychain with a random service; runs on macOS only. */
@EnabledOnOs(OS.MAC)
class MacKeychainTest {
    private val keychain = MacKeychain("Thoreau Test " + UUID.randomUUID())

    @AfterEach
    fun cleanUp() {
        keychain.delete()
    }

    @Test
    fun `the key goes through create, read and delete`() {
        val first = keychain.read()
        assumeTrue(
            first != KeyRead.Failed("Keychain is locked") && first != KeyRead.Failed("no default keychain"),
            "the keychain is locked or missing",
        )
        assertEquals(KeyRead.Missing, first)

        val key = ByteArray(32) { (it * 7).toByte() }
        assertEquals(KeyCreate.Created, keychain.create(key))
        assertEquals(KeyCreate.AlreadyExists, keychain.create(key))

        val found = keychain.read()
        assertTrue(found is KeyRead.Found)
        assertArrayEquals(key, (found as KeyRead.Found).key)

        assertTrue(keychain.delete())
        assertEquals(KeyRead.Missing, keychain.read())
        assertTrue(keychain.delete())
    }
}
