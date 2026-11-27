package dev.sluide.git.askpass

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * One prompt of ssh as the helper relays it (ADR 0194): the session's [token] (`SLUIDE_ASKPASS_TOKEN`), the helper's
 * parent process, which is ssh ([sshPid], -1 if unknown), `SSH_ASKPASS_PROMPT` ([promptVariable], empty if unset) and
 * ssh's prompt text, the helper's only argument ([prompt]).
 */
class AskpassRequest(
    val token: String,
    val sshPid: Long,
    val promptVariable: String,
    val prompt: String,
)

/**
 * The bytes between the askpass helper and Thoreau's server (ADR 0194): one request per connection, then one reply.
 * A request is the token, the ssh pid (8 bytes), the prompt variable and the prompt, each string as a 4-byte length
 * and its UTF-8 bytes; a reply is [NONE] alone, or [ANSWER] followed by a 4-byte length and the answer's bytes. Both
 * are at most [MAX_BYTES]; anything longer, shorter or with bytes left over is no message.
 */
object AskpassProtocol {
    /** The most bytes of a request or a reply. */
    const val MAX_BYTES = 8 * 1024

    private const val NONE: Byte = 0
    private const val ANSWER: Byte = 1

    /** The bytes of [request]; `null` if they would be more than [MAX_BYTES]. */
    fun encodeRequest(request: AskpassRequest): ByteArray? {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeField(request.token.toByteArray())
            data.writeLong(request.sshPid)
            data.writeField(request.promptVariable.toByteArray())
            data.writeField(request.prompt.toByteArray())
        }
        return output.toByteArray().takeIf { it.size <= MAX_BYTES }
    }

    /** The request in [bytes]; `null` if they are none (truncated, too long, malformed or followed by more). */
    fun decodeRequest(bytes: ByteArray): AskpassRequest? {
        if (bytes.size > MAX_BYTES) return null
        return reading(bytes) { data ->
            val token = data.readField()
            val pid = data.readLong()
            val variable = data.readField()
            val prompt = data.readField()
            AskpassRequest(token.decodeToString(), pid, variable.decodeToString(), prompt.decodeToString())
        }
    }

    /**
     * The reply giving ssh [answer]'s bytes, or "no answer" for `null`; also "no answer" for an answer too long for a
     * reply of [MAX_BYTES].
     */
    fun encodeReply(answer: ByteArray?): ByteArray {
        if (answer == null || answer.size > MAX_BYTES - 5) return byteArrayOf(NONE)
        // Exactly sized: a growing buffer would leave copies of a passphrase behind.
        return ByteBuffer
            .allocate(5 + answer.size)
            .put(ANSWER)
            .putInt(answer.size)
            .put(answer)
            .array()
    }

    /** The answer in the reply [bytes]: `null` for "no answer" and for bytes that are no reply. */
    fun decodeReply(bytes: ByteArray): ByteArray? {
        if (bytes.size > MAX_BYTES) return null
        return reading(bytes) { data -> if (data.readByte() == ANSWER) data.readField() else null }
    }

    /** What [read] takes from [bytes]; `null` if it runs past their end or leaves bytes over. */
    private fun <T : Any> reading(
        bytes: ByteArray,
        read: (DataInputStream) -> T?,
    ): T? =
        try {
            val data = DataInputStream(ByteArrayInputStream(bytes))
            read(data)?.takeIf { data.available() == 0 }
        } catch (_: IOException) {
            null
        }

    private fun DataOutputStream.writeField(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    /** A length and that many bytes; an [IOException] if the length is negative or more than there is. */
    private fun DataInputStream.readField(): ByteArray {
        val length = readInt()
        if (length < 0 || length > available()) throw IOException("bad length")
        return readNBytes(length)
    }
}
