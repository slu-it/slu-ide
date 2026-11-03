package dev.sluide.secrets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class KeyBackendsTest {
    @ParameterizedTest
    @CsvSource(
        "Mac OS X, MacKeychain",
        "Linux, SecretToolBackend",
        "Windows 11, NoBackend",
        "FreeBSD, NoBackend",
        "'', NoBackend",
    )
    fun `the secret store of the system`(
        osName: String,
        backend: String,
    ) {
        assertEquals(backend, KeyBackends.forPlatform(osName)::class.simpleName)
    }
}
