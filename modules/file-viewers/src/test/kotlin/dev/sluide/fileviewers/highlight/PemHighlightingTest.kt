package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class PemHighlightingTest {
    /** The test-only key of the PEM previews' fixtures (`test-only-traditional-encrypted.key`). */
    private val base64 = "s4/F6cQV/syEpDnLlCyTysL+3nDcT6gJF8BDHIxotN0rLp3YSPg7u8Blzuj/cNyh"

    private val pem =
        Highlighted(
            """
            |-----BEGIN EC PRIVATE KEY-----
            |Proc-Type: 4,ENCRYPTED
            |DEK-Info: AES-128-CBC,54866E79394D737DE7F78C56C6F1539E
            |
            |$base64
            |M/JRT1V4moQYww9MQZjcp2nKo2mXzAyH226CsQCK2Q/E0n7dvAVkrDRvNt/wSDzB
            |D7nb18QDGrwMGUAyiW3aTWLZNPgp6P5x0s1H6V6RYLo=
            |-----END EC PRIVATE KEY-----
            |
            """.trimMargin(),
            RSyntaxHighlighter.pem,
        )

    @Test
    fun `headers and markers are highlighted, base64 is not`() {
        assertEquals(setOf(TokenStyle.KEYWORD), pem.stylesOf("-----BEGIN EC PRIVATE KEY-----"))
        assertEquals(setOf(TokenStyle.KEYWORD), pem.stylesOf("-----END EC PRIVATE KEY-----"))
        assertEquals(setOf(TokenStyle.PROPERTY), pem.stylesOf("Proc-Type:"))
        assertEquals(setOf(TokenStyle.PROPERTY), pem.stylesOf("DEK-Info:"))
        assertEquals(emptySet(), pem.stylesOf("4,ENCRYPTED"))
        assertEquals(emptySet(), pem.stylesOf(base64))
    }
}
