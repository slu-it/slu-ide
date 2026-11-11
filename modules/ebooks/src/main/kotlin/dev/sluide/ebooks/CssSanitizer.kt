package dev.sluide.ebooks

/**
 * Makes book CSS safe for a page that must not touch the network, in one pass over the text: `@import` and the
 * functions that load by string (`image-set`, `image`, `cross-fade`, `src`) go, a `url(...)` to a resource of the book
 * becomes a data URI and any other `url(...)` becomes `none`. Comments go; a string ends at a newline as in CSS;
 * escapes outside strings are replaced by a space so that nothing can be spelled with them (`\75rl(`). What is
 * unterminated is dropped to the end. [isClean] checks the result and fails closed (no CSS at all).
 */
internal object CssSanitizer {
    private val dropped = setOf("image-set", "image", "cross-fade", "src")
    private val refused = listOf("@import", "image-set", "image(", "cross-fade(", "src(")
    private val vendorPrefix = Regex("^-[a-z]+-")

    /** [base] is the folder of the document the CSS is in; urls are resolved against it. */
    fun sanitize(
        css: String,
        base: String,
        uris: ResourceUris,
    ): String {
        val out = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            val c = css[i]
            i =
                when {
                    c == '/' && css.startsWith("/*", i) -> {
                        // A comment separates tokens, as in CSS: `ima/**/ge(` is not `image(`.
                        out.append(' ')
                        css.indexOf("*/", i + 2).let { if (it < 0) css.length else it + 2 }
                    }

                    c == '"' || c == '\'' -> {
                        readString(css, i, out).end
                    }

                    c == '\\' -> {
                        escape(css, i, out)
                    }

                    c == '@' && css.startsWith("@import", i, ignoreCase = true) -> {
                        skipImport(css, i)
                    }

                    c.isIdentStart() -> {
                        identifier(css, i, base, uris, out)
                    }

                    else -> {
                        out.append(c)
                        i + 1
                    }
                }
        }
        // The text sits in a <style> element or an attribute: it must not be able to leave it.
        val result = out.toString()
        return if (isClean(result)) result.replace("</", "<\\/") else ""
    }

    private class Str(
        val value: String,
        val end: Int,
        val closed: Boolean,
    )

    /** Reads the string at [start] (a quote), appending it as written to [out]; a newline ends it, unclosed. */
    private fun readString(
        css: String,
        start: Int,
        out: StringBuilder?,
    ): Str {
        val quote = css[start]
        val value = StringBuilder()
        out?.append(quote)
        var j = start + 1
        while (j < css.length) {
            val c = css[j]
            when {
                c == quote -> {
                    out?.append(quote)
                    return Str(value.toString(), j + 1, true)
                }

                c == '\n' || c == '\r' || c == '\u000C' -> {
                    out?.append(quote)
                    return Str(value.toString(), j, false)
                }

                c == '\\' && j + 1 < css.length -> {
                    val next = css[j + 1]
                    if (next != '\n' && next != '\r' && next != '\u000C') {
                        value.append(c).append(next)
                        out?.append(c)?.append(next)
                    }
                    j += 2
                }

                else -> {
                    value.append(c)
                    out?.append(c)
                    j++
                }
            }
        }
        out?.append(quote)
        return Str(value.toString(), css.length, false)
    }

    /**
     * An escape outside a string is replaced by a space. When it sits inside a name (`u\72l`) the name is an attempt to
     * hide one: it is dropped with the arguments of the function it would call.
     */
    private fun escape(
        css: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        val after = skipEscape(css, start)
        val inName = (out.isNotEmpty() && out.last().isIdentPart()) || (after < css.length && css[after].isIdentPart())
        if (!inName) {
            out.append(' ')
            return after
        }
        var j = after
        while (j < css.length && css[j].isIdentPart()) j++
        if (j < css.length && css[j] == '(') {
            out.append("none")
            return skipFunction(css, j + 1)
        }
        out.append(' ')
        return j
    }

    private fun skipEscape(
        css: String,
        start: Int,
    ): Int {
        var j = start + 1
        var digits = 0
        while (j < css.length && digits < 6 && css[j].isHexDigit()) {
            j++
            digits++
        }
        if (digits == 0) return minOf(j + 1, css.length)
        return if (j < css.length && css[j].isWhitespace()) j + 1 else j
    }

    private fun skipImport(
        css: String,
        start: Int,
    ): Int {
        var j = start + "@import".length
        while (j < css.length) {
            val c = css[j]
            when {
                c == '"' || c == '\'' -> j = readString(css, j, null).end
                c == ';' -> return j + 1
                else -> j++
            }
        }
        return css.length
    }

    private fun identifier(
        css: String,
        start: Int,
        base: String,
        uris: ResourceUris,
        out: StringBuilder,
    ): Int {
        var end = start
        while (end < css.length && css[end].isIdentPart()) end++
        val ident = css.substring(start, end)
        if (end >= css.length || css[end] != '(') {
            out.append(ident)
            return end
        }
        return when (ident.lowercase().replace(vendorPrefix, "")) {
            "url" -> {
                url(css, end + 1, base, uris, out)
            }

            in dropped -> {
                skipFunction(css, end + 1).also { out.append("none") }
            }

            else -> {
                out.append(ident).append('(')
                end + 1
            }
        }
    }

    /** Skips to after the `)` that closes a function whose `(` came before [start]; to the end if there is none. */
    private fun skipFunction(
        css: String,
        start: Int,
    ): Int {
        var depth = 1
        var j = start
        while (j < css.length) {
            val c = css[j]
            when {
                c == '"' || c == '\'' -> {
                    j = readString(css, j, null).end
                }

                c == '(' -> {
                    depth++
                    j++
                }

                c == ')' -> {
                    j++
                    if (--depth == 0) return j
                }

                else -> {
                    j++
                }
            }
        }
        return css.length
    }

    private fun url(
        css: String,
        start: Int,
        base: String,
        uris: ResourceUris,
        out: StringBuilder,
    ): Int {
        var j = start
        while (j < css.length && css[j].isWhitespace()) j++
        var target: String? = null
        var end: Int
        if (j < css.length && (css[j] == '"' || css[j] == '\'')) {
            val str = readString(css, j, null)
            var k = str.end
            while (k < css.length && css[k].isWhitespace()) k++
            if (str.closed && k < css.length && css[k] == ')') {
                target = str.value
                end = k + 1
            } else {
                end = skipFunction(css, start)
            }
        } else {
            var k = j
            while (k < css.length && !css[k].isWhitespace() && css[k] !in "()\"'\\") k++
            var after = k
            while (after < css.length && css[after].isWhitespace()) after++
            if (after < css.length && css[after] == ')') {
                target = css.substring(j, k)
                end = after + 1
            } else {
                end = skipFunction(css, start)
            }
        }
        val data = target?.let { uris.dataUri(it, base) }
        out.append(if (data == null) "none" else "url(\"$data\")")
        return end
    }

    /** True when nothing outside strings could load: no escapes, `@import` or `image-set`, `url(` only our own. */
    fun isClean(css: String): Boolean {
        var i = 0
        while (i < css.length) {
            val c = css[i]
            if (c == '"' || c == '\'') {
                i = readString(css, i, null).end
                continue
            }
            if (c == '\\') return false
            if (refused.any { css.startsWith(it, i, ignoreCase = true) }) return false
            if (css.startsWith("url(", i, ignoreCase = true) && (i == 0 || !css[i - 1].isIdentPart())) {
                if (!css.startsWith("\"data:", i + 4)) return false
            }
            i++
        }
        return true
    }

    private fun Char.isIdentStart() = this in 'a'..'z' || this in 'A'..'Z' || this == '_' || this == '-'

    private fun Char.isIdentPart() = isIdentStart() || this in '0'..'9'

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
