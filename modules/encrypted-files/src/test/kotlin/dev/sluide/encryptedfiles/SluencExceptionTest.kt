package dev.sluide.encryptedfiles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SluencExceptionTest {
    @ParameterizedTest
    @CsvSource(
        "WRONG_PASSWORD, Wrong password.",
        "NOT_SLUENC, Not a Thoreau encrypted file.",
        "UNSUPPORTED_PARAMETERS, The file asks for unsupported key parameters.",
        "DAMAGED, The file is damaged.",
        "KEY_MISMATCH, Changed elsewhere with another password.",
    )
    fun `the message is a fixed text per failure`(
        failure: SluencFailure,
        message: String,
    ) {
        assertEquals(message, SluencException(failure).message)
    }

    @Test
    fun `a newer version names its format, or says it is unknown`() {
        assertEquals("Written by a newer Thoreau (format 3).", SluencException(SluencFailure.NEWER_VERSION, 3).message)
        assertEquals(
            "Written by a newer Thoreau (format unknown).",
            SluencException(SluencFailure.NEWER_VERSION).message,
        )
    }
}
