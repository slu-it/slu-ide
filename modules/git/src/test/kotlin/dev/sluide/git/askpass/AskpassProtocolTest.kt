package dev.sluide.git.askpass

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AskpassProtocolTest {
    private val prompt = "Enter passphrase for key '/home/u/.ssh/kéy\nwith 'quote'': "

    @Test
    fun `a request goes through whole, also with line breaks, quotes and non-ASCII`() {
        val bytes = assertNotNull(AskpassProtocol.encodeRequest(AskpassRequest("ab12", 4711L, "", prompt)))

        val decoded = assertNotNull(AskpassProtocol.decodeRequest(bytes))

        assertEquals("ab12", decoded.token)
        assertEquals(4711L, decoded.sshPid)
        assertEquals("", decoded.promptVariable)
        assertEquals(prompt, decoded.prompt)
        val withVariable = AskpassProtocol.encodeRequest(AskpassRequest("t", -1L, "confirm", "p"))
        assertEquals(
            "confirm",
            assertNotNull(AskpassProtocol.decodeRequest(assertNotNull(withVariable))).promptVariable,
        )
    }

    @Test
    fun `a request of up to 8 KiB is encoded, a longer one is refused`() {
        // Token, pid and prompt variable take 4 + 1 + 8 + 4 + 4 bytes besides the prompt.
        val fitting = AskpassRequest("t", 1L, "", "x".repeat(AskpassProtocol.MAX_BYTES - 21))
        val bytes = assertNotNull(AskpassProtocol.encodeRequest(fitting))
        assertEquals(AskpassProtocol.MAX_BYTES, bytes.size)
        assertEquals(fitting.prompt, AskpassProtocol.decodeRequest(bytes)?.prompt)

        assertNull(
            AskpassProtocol.encodeRequest(AskpassRequest("t", 1L, "", "x".repeat(AskpassProtocol.MAX_BYTES - 20))),
        )
    }

    @Test
    fun `bytes that are no whole request are refused`() {
        val bytes = assertNotNull(AskpassProtocol.encodeRequest(AskpassRequest("token", 7L, "v", "prompt")))

        for (length in 0 until bytes.size) {
            assertNull(AskpassProtocol.decodeRequest(bytes.copyOf(length)), "cut to $length bytes")
        }
        assertNull(AskpassProtocol.decodeRequest(bytes + 0), "a byte left over")
        // Well-formed, written by hand: at the limit, and one byte over it.
        assertEquals(AskpassProtocol.MAX_BYTES, request(AskpassProtocol.MAX_BYTES - 21).size)
        assertNotNull(AskpassProtocol.decodeRequest(request(AskpassProtocol.MAX_BYTES - 21)))
        assertNull(AskpassProtocol.decodeRequest(request(AskpassProtocol.MAX_BYTES - 20)), "more than 8 KiB")
        // A length that says more than there is, and a negative one.
        assertNull(AskpassProtocol.decodeRequest(byteArrayOf(0, 0, 0x7f, 0) + bytes.copyOfRange(4, bytes.size)))
        assertNull(AskpassProtocol.decodeRequest(byteArrayOf(-1, -1, -1, -1) + bytes.copyOfRange(4, bytes.size)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["yes", "", "päss w'o\"rd \$HOME \\n"])
    fun `an answer goes through byte for byte`(answer: String) {
        val bytes = answer.toByteArray()

        assertContentEquals(bytes, AskpassProtocol.decodeReply(AskpassProtocol.encodeReply(bytes)))
    }

    @Test
    fun `no answer, and bytes that are no reply, give no answer`() {
        val none = AskpassProtocol.encodeReply(null)
        assertContentEquals(byteArrayOf(0), none)
        assertNull(AskpassProtocol.decodeReply(none))

        val answer = AskpassProtocol.encodeReply("yes".toByteArray())
        assertContentEquals(byteArrayOf(1, 0, 0, 0, 3) + "yes".toByteArray(), answer)
        for (length in 0 until answer.size) {
            assertNull(AskpassProtocol.decodeReply(answer.copyOf(length)), "cut to $length bytes")
        }
        assertNull(AskpassProtocol.decodeReply(answer + 0), "a byte left over")
        assertNull(AskpassProtocol.decodeReply(none + 0), "a byte after no answer")
        assertNull(AskpassProtocol.decodeReply(byteArrayOf(2, 0, 0, 0, 0)), "an unknown kind")
        assertNull(AskpassProtocol.decodeReply(byteArrayOf(1, -1, -1, -1, -1)), "a negative length")
    }

    @Test
    fun `an answer longer than a reply may be becomes no answer`() {
        val fitting = ByteArray(AskpassProtocol.MAX_BYTES - 5) { 'a'.code.toByte() }
        assertContentEquals(fitting, AskpassProtocol.decodeReply(AskpassProtocol.encodeReply(fitting)))

        assertContentEquals(byteArrayOf(0), AskpassProtocol.encodeReply(fitting + 'a'.code.toByte()))
        // A well-formed reply one byte over the limit.
        val oversized =
            byteArrayOf(1) + ByteBuffer.allocate(4).putInt(fitting.size + 1).array() + fitting + 'a'.code.toByte()
        assertEquals(AskpassProtocol.MAX_BYTES + 1, oversized.size)
        assertNull(AskpassProtocol.decodeReply(oversized))
    }

    /** A request with the token "t", pid 0, no variable and a prompt of [length] `x`, framed by hand. */
    private fun request(length: Int): ByteArray =
        ByteBuffer
            .allocate(21 + length)
            .putInt(1)
            .put('t'.code.toByte())
            .putLong(0)
            .putInt(0)
            .putInt(length)
            .put(ByteArray(length) { 'x'.code.toByte() })
            .array()
}
