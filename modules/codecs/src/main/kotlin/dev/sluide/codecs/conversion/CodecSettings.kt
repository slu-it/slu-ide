package dev.sluide.codecs.conversion

import dev.sluide.codecs.codec.Ascii85Codec
import dev.sluide.codecs.codec.Ascii85Variant
import dev.sluide.codecs.codec.Base32Codec
import dev.sluide.codecs.codec.Base58Codec
import dev.sluide.codecs.codec.Base64Codec
import dev.sluide.codecs.codec.ByteCodec
import dev.sluide.codecs.codec.CodecGroup
import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.codec.Compression
import dev.sluide.codecs.codec.HashOutput
import dev.sluide.codecs.codec.HexCodec
import dev.sluide.codecs.codec.HexSeparator
import dev.sluide.codecs.codec.HtmlEntities
import dev.sluide.codecs.codec.JavaString
import dev.sluide.codecs.codec.JsonString
import dev.sluide.codecs.codec.TextCodec
import dev.sluide.codecs.codec.UnicodeEscapes
import dev.sluide.codecs.codec.UnicodeForm
import dev.sluide.codecs.codec.UrlCodec
import dev.sluide.codecs.codec.UrlMode
import java.nio.charset.Charset

data class Base64Options(
    val urlSafe: Boolean = false,
    val padding: Boolean = true,
    val wrap76: Boolean = false,
    val dataUri: Boolean = false,
)

data class Base32Options(
    val hexAlphabet: Boolean = false,
    val padding: Boolean = true,
)

data class HexOptions(
    val upperCase: Boolean = false,
    val separator: HexSeparator = HexSeparator.NONE,
)

data class JavaOptions(
    val nonAscii: Boolean = false,
    val kotlinDollar: Boolean = false,
)

data class UnicodeOptions(
    val form: UnicodeForm = UnicodeForm.BACKSLASH_U,
    val allCharacters: Boolean = false,
)

/** [key] is the HMAC key as typed; empty means plain hashes. */
data class HashOptions(
    val key: String = "",
    val output: HashOutput = HashOutput.HEX,
)

/** Everything the user can choose; the options of all codecs are kept when the codec is switched. */
data class CodecSettings(
    val codec: CodecId = CodecId.BASE64,
    val charset: Charset = Charsets.UTF_8,
    val lineByLine: Boolean = false,
    val compression: Compression = Compression.NONE,
    val base64: Base64Options = Base64Options(),
    val base32: Base32Options = Base32Options(),
    val ascii85: Ascii85Variant = Ascii85Variant.ADOBE,
    val hex: HexOptions = HexOptions(),
    val url: UrlMode = UrlMode.COMPONENT,
    val htmlNonAscii: Boolean = false,
    val jsonNonAscii: Boolean = false,
    val java: JavaOptions = JavaOptions(),
    val unicode: UnicodeOptions = UnicodeOptions(),
    val hash: HashOptions = HashOptions(),
) {
    private val isByteCodec get() = codec.group == CodecGroup.BYTES

    val lineByLineApplies: Boolean get() = codec != CodecId.JWT && codec != CodecId.HASH

    val charsetApplies: Boolean get() = isByteCodec || codec == CodecId.URL || codec == CodecId.HASH

    val compressionApplies: Boolean get() = isByteCodec
}

/** The configured byte codec, or null for the other kinds; [dataUriMime] is used when the Base64 option asks for it. */
internal fun CodecSettings.byteCodec(dataUriMime: String?): ByteCodec? =
    when (codec) {
        CodecId.BASE64 -> {
            Base64Codec(
                urlSafe = base64.urlSafe,
                padding = base64.padding,
                wrap76 = base64.wrap76 && !lineByLine,
                dataUriMime = dataUriMime.takeIf { base64.dataUri },
            )
        }

        CodecId.BASE32 -> {
            Base32Codec(base32.hexAlphabet, base32.padding)
        }

        CodecId.BASE58 -> {
            Base58Codec()
        }

        CodecId.ASCII85 -> {
            Ascii85Codec(ascii85)
        }

        CodecId.HEX -> {
            HexCodec(hex.upperCase, hex.separator)
        }

        else -> {
            null
        }
    }

/** The configured escape codec, or null for the other kinds. */
internal fun CodecSettings.textCodec(): TextCodec? =
    when (codec) {
        CodecId.URL -> UrlCodec(url, charset)
        CodecId.HTML -> HtmlEntities(htmlNonAscii)
        CodecId.JSON -> JsonString(jsonNonAscii)
        CodecId.JAVA -> JavaString(java.nonAscii, java.kotlinDollar)
        CodecId.UNICODE -> UnicodeEscapes(unicode.form, unicode.allCharacters)
        else -> null
    }
