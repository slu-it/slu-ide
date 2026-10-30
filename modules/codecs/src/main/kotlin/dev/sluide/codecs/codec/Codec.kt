package dev.sluide.codecs.codec

/** The groups of the codec list (ADR 0112). */
enum class CodecGroup(
    val title: String,
) {
    BYTES("Bytes"),
    ESCAPES("Escapes"),
    TOKENS("Tokens"),
    HASHES("Hashes"),
}

/** The codecs of the Codecs tool, in the order of the list (ADR 0112). */
enum class CodecId(
    val title: String,
    val group: CodecGroup,
) {
    BASE64("Base64", CodecGroup.BYTES),
    BASE32("Base32", CodecGroup.BYTES),
    BASE58("Base58", CodecGroup.BYTES),
    ASCII85("Ascii85", CodecGroup.BYTES),
    HEX("Hex", CodecGroup.BYTES),
    URL("URL", CodecGroup.ESCAPES),
    HTML("HTML/XML", CodecGroup.ESCAPES),
    JSON("JSON String", CodecGroup.ESCAPES),
    JAVA("Java/Kotlin String", CodecGroup.ESCAPES),
    UNICODE("Unicode Escapes", CodecGroup.ESCAPES),
    JWT("JWT", CodecGroup.TOKENS),
    HASH("Hash", CodecGroup.HASHES),
}

/** The outcome of a conversion that can fail: the value, or why the input is not valid. */
sealed interface Decoded<out T> {
    data class Value<T>(
        val value: T,
    ) : Decoded<T>

    /** [message] says what is wrong ("Invalid Base64 character '!'"); [position] is its 0-based index in the input. */
    data class Error(
        val message: String,
        val position: Int? = null,
    ) : Decoded<Nothing>
}

/** Turns bytes into text and back: Base64, Base32, Base58, Ascii85, Hex. Options are constructor parameters. */
interface ByteCodec {
    /** Fails only where the format cannot hold the bytes (Z85 needs a multiple of 4). */
    fun encode(bytes: ByteArray): Decoded<String>

    fun decode(text: String): Decoded<ByteArray>
}

/** Escapes text and back: URL, HTML/XML, JSON, Java/Kotlin, Unicode. Options are constructor parameters. */
interface TextCodec {
    /** Fails only where the text cannot be written (a character the URL charset cannot hold). */
    fun encode(text: String): Decoded<String>

    fun decode(text: String): Decoded<String>
}
