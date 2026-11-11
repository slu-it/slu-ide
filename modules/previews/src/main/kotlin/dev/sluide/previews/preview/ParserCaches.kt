package dev.sluide.previews.preview

import org.jsoup.internal.SoftPool
import org.jsoup.internal.StringUtil
import java.util.Arrays

private val log = System.getLogger("dev.sluide.previews.preview.ParserCaches")

private const val BORROWED = 16

/**
 * Overwrites what jsoup's thread-local pools keep of the last document it cleaned or parsed (Markdown preview): the
 * serialised HTML in pooled StringBuilders (`releaseBuilder` only resets the length), the input in the reader's char
 * buffers and the short tokens (up to 12 characters, e.g. "pin 4711") in its string cache. Each pool lends up to
 * [BORROWED] entries, which are overwritten in place and given back. Run it on the thread that rendered (the JavaFX
 * thread) when a confidential preview is wiped.
 *
 * Best effort: it uses jsoup's internal API (`org.jsoup.internal`, and `CharacterReader`'s private pools by
 * reflection; version pinned in `gradle/libs.versions.toml`). A pool that cannot be reached is skipped; only the
 * failure's type is logged, at debug.
 */
internal fun scrubParserCaches() {
    scrub("builders") {
        val borrowed = List(BORROWED) { StringUtil.borrowBuilder() }
        borrowed.forEach {
            it.setLength(it.capacity())
            it.setLength(0)
            StringUtil.releaseBuilderVoid(it)
        }
    }
    // The string cache holds the text, so it goes first.
    scrub("string cache") { scrubPool<Array<String?>>("StringPool") { Arrays.fill(it, null) } }
    scrub("char buffers") { scrubPool<CharArray>("BufferPool") { Arrays.fill(it, '\u0000') } }
}

private inline fun <reified T : Any> scrubPool(
    name: String,
    clear: (T) -> Unit,
) {
    val pool =
        Class
            .forName("org.jsoup.parser.CharacterReader")
            .getDeclaredField(name)
            .apply {
                isAccessible = true
            }.get(null)

    @Suppress("UNCHECKED_CAST")
    pool as SoftPool<T>
    val borrowed = List(BORROWED) { pool.borrow() }
    borrowed.forEach {
        clear(it)
        pool.release(it)
    }
}

private fun scrub(
    what: String,
    action: () -> Unit,
) {
    try {
        action()
    } catch (e: Throwable) {
        log.log(System.Logger.Level.DEBUG, "Cannot scrub jsoup's $what: ${e.javaClass.name}")
    }
}
