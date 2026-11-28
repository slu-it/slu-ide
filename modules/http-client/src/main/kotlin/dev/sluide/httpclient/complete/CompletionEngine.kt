package dev.sluide.httpclient.complete

import dev.sluide.httpclient.HttpCompletionItem
import dev.sluide.httpclient.HttpCompletions
import dev.sluide.httpclient.env.Environments
import dev.sluide.httpclient.parse.HttpFile
import dev.sluide.httpclient.parse.HttpFileParser

/**
 * Completes `.http` text at a caret (the rules: [ContextDetector], [VariableItems], [Catalogs]). Every item's label
 * starts, ignoring case, with `text[anchor, wordStart)`, where `wordStart` is the start of the run of word characters
 * before the caret (letters, digits, `_`, as the code intelligence's `isWordCodePoint`): the editor filters the rest
 * of the label by the word.
 *
 * [osEnvNames] gives the names of the operating system environment, offered after `{{$env.`.
 */
internal class CompletionEngine(
    private val osEnvNames: () -> Set<String> = { System.getenv().keys },
) {
    /** [environments] is called only when variables are offered. */
    fun complete(
        text: String,
        caret: Int,
        environments: () -> Environments,
    ): HttpCompletions {
        val at = caret.coerceIn(0, text.length)
        val file = HttpFileParser.parse(text)
        val context = ContextDetector.detect(text, at, file)
        val items = items(context, text.substring(context.anchor, at), file, environments)
        val fixed = text.substring(context.anchor, maxOf(context.anchor, wordStart(text, at)))
        return HttpCompletions(context.anchor, items.filter { it.label.startsWith(fixed, ignoreCase = true) })
    }

    private fun items(
        context: Context,
        partial: String,
        file: HttpFile,
        environments: () -> Environments,
    ): List<HttpCompletionItem> =
        when (context) {
            is Context.Variable -> VariableItems.build(file, context, partial, environments(), osEnvNames)
            is Context.Method -> Catalogs.METHODS.map { HttpCompletionItem(it) }
            is Context.Version -> Catalogs.VERSIONS.map { HttpCompletionItem(it) }
            is Context.Tag -> Catalogs.TAGS.map { HttpCompletionItem(it) }
            is Context.HeaderName -> headerNames(context, file)
            is Context.HeaderValue -> Catalogs.valuesOf(context.header).map { HttpCompletionItem(it) }
            is Context.Nothing -> emptyList()
        }

    private fun headerNames(
        context: Context.HeaderName,
        file: HttpFile,
    ): List<HttpCompletionItem> {
        val suffix = if (context.colonFollows) "" else ": "
        val known = Catalogs.HEADER_NAMES.map { it.lowercase() }.toSet()
        val inFile =
            file.requests
                .flatMap { it.headers }
                .filter { it.line != context.line }
                .map { it.name }
                .distinctBy { it.lowercase() }
                .filter { it.lowercase() !in known }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        return Catalogs.HEADER_NAMES.map { HttpCompletionItem(it, it + suffix) } +
            inFile.map { HttpCompletionItem(it, it + suffix, "in this file") }
    }

    /** The offset where the word before [caret] starts. */
    private fun wordStart(
        text: String,
        caret: Int,
    ): Int {
        var start = caret
        while (start > 0) {
            val codePoint = text.codePointBefore(start)
            if (!isWordCodePoint(codePoint)) break
            start -= Character.charCount(codePoint)
        }
        return start
    }

    /** Mirrors `isWordCodePoint` of the code intelligence's completion: letters, numbers, combining marks and `_`. */
    private fun isWordCodePoint(codePoint: Int): Boolean =
        codePoint == '_'.code ||
            Character.isLetter(codePoint) ||
            when (Character.getType(codePoint).toByte()) {
                Character.DECIMAL_DIGIT_NUMBER,
                Character.LETTER_NUMBER,
                Character.OTHER_NUMBER,
                Character.NON_SPACING_MARK,
                Character.COMBINING_SPACING_MARK,
                -> true

                else -> false
            }
}
