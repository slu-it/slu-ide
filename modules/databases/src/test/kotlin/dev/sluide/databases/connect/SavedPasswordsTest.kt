package dev.sluide.databases.connect

import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import dev.sluide.secrets.Secrets
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SavedPasswordsTest {
    @Test
    fun `nothing can be saved without saved passwords`() {
        assertEquals("not supported on this system", NoSavedPasswords.unavailableReason())
        assertEquals(
            Encrypted.Failed("not supported on this system"),
            NoSavedPasswords.encrypt("a", "pw".toCharArray()),
        )
        assertEquals(Decrypted.NoKey("not supported on this system"), NoSavedPasswords.decrypt("a", "v1:x"))
    }

    @Test
    fun `the Secrets passwords ask Secrets why nothing can be saved`() {
        val secrets = Secrets()

        assertEquals(secrets.saveUnavailableReason(), SecretsPasswords(secrets).unavailableReason())
    }

    @Test
    fun `the Secrets passwords hand a value that is not well formed to Secrets`() {
        // A value that is not well formed is refused before the secret store is asked, so no real store is touched.
        assertEquals(Decrypted.Unreadable, SecretsPasswords(Secrets()).decrypt("a", "no secret"))
    }

    @Test
    fun `the Secrets passwords hand encrypting to Secrets`() {
        val secrets = Secrets()
        // Only where the system has no secret store, so that no real one is asked (and no key created in it).
        assumeTrue(secrets.saveUnavailableReason() != null)

        assertIs<Encrypted.Failed>(SecretsPasswords(secrets).encrypt("a", "pw".toCharArray()))
    }
}
