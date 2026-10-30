package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HashesTest {
    private fun hashes(
        text: String,
        key: String? = null,
        output: HashOutput = HashOutput.HEX,
    ) = Hashes.compute(text.toByteArray(), key?.toByteArray(), output).associate { it.algorithm to it.value }

    @ParameterizedTest
    @CsvSource(
        "MD5,900150983cd24fb0d6963f7d28e17f72",
        "SHA1,a9993e364706816aba3e25717850c26c9cd0d89d",
        "SHA256,ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "SHA384,cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7",
        "SHA512,ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
        "SHA3_256,3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
    )
    fun `hashes of abc match the published vectors`(
        algorithm: HashAlgorithm,
        expected: String,
    ) {
        assertEquals(expected, hashes("abc")[algorithm])
    }

    @Test
    fun `without a key all seven hashes come in enum order`() {
        val result = Hashes.compute("abc".toByteArray(), null, HashOutput.HEX)
        assertEquals(HashAlgorithm.entries, result.map { it.algorithm })
        assertEquals(
            HashAlgorithm.entries,
            Hashes.compute("abc".toByteArray(), ByteArray(0), HashOutput.HEX).map {
                it.algorithm
            },
        )
    }

    @Test
    fun `CRC32 is four bytes big-endian`() {
        assertEquals("cbf43926", hashes("123456789")[HashAlgorithm.CRC32])
        assertEquals("00000000", hashes("")[HashAlgorithm.CRC32])
    }

    @Test
    fun `HMAC-SHA256 matches RFC 4231 test case 2 and CRC32 is left out`() {
        val result = hashes("what do ya want for nothing?", key = "Jefe")
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", result[HashAlgorithm.SHA256])
        assertEquals(
            "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea2505549758bf75c05a994a6d034f65f8f0e6fd" +
                "caeab1a34d4a6b4b636e070a38bce737",
            result[HashAlgorithm.SHA512],
        )
        assertEquals(HashAlgorithm.entries - HashAlgorithm.CRC32, result.keys.toList())
    }

    @Test
    fun `Base64 output encodes the raw digest`() {
        assertEquals("kAFQmDzST7DWlj99KOF/cg==", hashes("abc", output = HashOutput.BASE64)[HashAlgorithm.MD5])
    }
}
