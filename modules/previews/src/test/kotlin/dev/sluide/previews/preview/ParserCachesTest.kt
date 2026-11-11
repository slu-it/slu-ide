package dev.sluide.previews.preview

import dev.sluide.previews.markdown.MarkdownRenderer
import org.jsoup.internal.SoftPool
import org.jsoup.internal.StringUtil
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

private const val LENT = 16

/**
 * What jsoup's pools of this thread keep, as text: the whole backing arrays of the StringBuilders (stale bytes
 * included: `setLength` would zero-fill them and `toString` stops at the length, so they are read with Unsafe from
 * jdk.unsupported), the reader's char buffers and its cached short tokens. Everything lent is given back.
 */
@Suppress("DEPRECATION", "removal", "UNCHECKED_CAST")
internal fun parserCacheContents(): List<String> {
    val unsafe =
        Class
            .forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
    val offset = unsafe.objectFieldOffset(Class.forName("java.lang.AbstractStringBuilder").getDeclaredField("value"))
    val builders = List(LENT) { StringUtil.borrowBuilder() }
    val texts = builders.map { String(unsafe.getObject(it, offset) as ByteArray, Charsets.ISO_8859_1) }.toMutableList()
    builders.forEach { StringUtil.releaseBuilderVoid(it) }

    val reader = Class.forName("org.jsoup.parser.CharacterReader")

    fun <T : Any> pool(name: String) =
        reader.getDeclaredField(name).apply { isAccessible = true }.get(null) as SoftPool<T>
    val buffers = pool<CharArray>("BufferPool")
    val strings = pool<Array<String?>>("StringPool")
    val lentBuffers = List(LENT) { buffers.borrow() }
    val lentStrings = List(LENT) { strings.borrow() }
    lentBuffers.forEach { texts += String(it) }
    lentStrings.forEach { texts += it.filterNotNull() }
    lentBuffers.forEach { buffers.release(it) }
    lentStrings.forEach { strings.release(it) }
    return texts
}

class ParserCachesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `scrubbing leaves no rendered text, long or short, in jsoup's pools`() {
        MarkdownRenderer.body("# SCRUB-MARKER\n\nbody SCRUB-MARKER\n\npin 4711\n", dir)
        val before = parserCacheContents()
        assertTrue(before.any { "SCRUB-MARKER" in it }, "the builders or buffers hold the text before the scrub")
        assertTrue(before.any { "pin 4711" in it }, "the pools hold the short secret before the scrub")

        scrubParserCaches()

        val after = parserCacheContents()
        assertTrue(after.none { "SCRUB-MARKER" in it || "4711" in it })
    }
}
