package dev.sluide.secrets

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Base64
import java.util.stream.Stream

class MacKeychainStatusTest {
    @ParameterizedTest
    @MethodSource("readCases")
    internal fun `read status maps to a result`(
        status: Int,
        expected: KeyRead,
    ) {
        assertEquals(expected, keychainReadResult(status, null))
    }

    @Test
    fun `a successful read decodes the base64 and zeroes it`() {
        val key = ByteArray(32) { it.toByte() }
        val base64 = Base64.getEncoder().encode(key)

        val result = keychainReadResult(0, base64)

        assertArrayEquals(key, (result as KeyRead.Found).key)
        assertTrue(base64.all { it == 0.toByte() })
    }

    @Test
    fun `invalid base64 is unreadable`() {
        assertEquals(
            KeyRead.Failed("unreadable key in the Keychain"),
            keychainReadResult(0, "not base64!".toByteArray()),
        )
    }

    @ParameterizedTest
    @MethodSource("createCases")
    internal fun `create status maps to a result`(
        status: Int,
        expected: KeyCreate,
    ) {
        assertEquals(expected, keychainCreateResult(status))
    }

    companion object {
        private const val DENIED = "Keychain access denied"

        @JvmStatic
        fun readCases(): Stream<Arguments> =
            Stream.of(
                arguments(-25300, KeyRead.Missing),
                arguments(-128, KeyRead.Denied(DENIED)),
                arguments(-25293, KeyRead.Denied(DENIED)),
                arguments(-25308, KeyRead.Failed("Keychain is locked")),
                arguments(-25307, KeyRead.Failed("no default keychain")),
                arguments(-25291, KeyRead.Failed("no default keychain")),
                arguments(-50, KeyRead.Failed("Keychain error -50")),
            )

        @JvmStatic
        fun createCases(): Stream<Arguments> =
            Stream.of(
                arguments(0, KeyCreate.Created),
                arguments(-25299, KeyCreate.AlreadyExists),
                arguments(-128, KeyCreate.Failed(DENIED)),
                arguments(-25293, KeyCreate.Failed(DENIED)),
                arguments(-25308, KeyCreate.Failed("Keychain is locked")),
                arguments(-25307, KeyCreate.Failed("no default keychain")),
                arguments(-50, KeyCreate.Failed("Keychain error -50")),
            )
    }
}
