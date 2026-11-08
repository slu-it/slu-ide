package dev.sluide.codeintelligence.completion

import dev.sluide.fileviewers.OffsetEdit
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/**
 * Where a completion is taken, in the editor's current text: the typed word is [wordStart, [caret]) and goes on to
 * [wordEnd]. [firstChange] is the lowest offset the text changed at since the items were asked for (`null`: not
 * changed), so the text before it is still the text the server's ranges are about; [requestCaret] is where the caret
 * was when they were asked for. [offsetsOf] and [positionOf] convert on the current text.
 */
internal class AcceptPlace(
    val wordStart: Int,
    val caret: Int,
    val wordEnd: Int,
    val firstChange: Int?,
    val requestCaret: Int?,
    val offsetsOf: (Range) -> IntRange,
    val positionOf: (Int) -> Position,
)

/** The edits that take a completion item, and how many of its additional edits had to be left out. */
internal data class AcceptEdits(
    val edits: List<OffsetEdit>,
    val droppedAdditional: Int,
)

/**
 * The edits that take [item] at [place] (ADR 0147), for one `applyEdits` call, so one undo step: the item's text
 * (`textEdit`'s, else `insertText`, else `label`; snippet placeholders turned into their text) and its
 * `additionalTextEdits` (an auto-import). An `InsertReplaceEdit` uses its insert range for Enter and its replace range
 * for Tab ([replace]). If neither the text nor the caret changed since the request, the server's ranges are used as
 * they are. Otherwise (typed on, or moved the caret within the word) the item's text replaces from the server's start,
 * if that lies before the first change (untouched text: `import os.pa` keeps the server's start at `os`), else from
 * the word's start, up to the caret for Enter and to the word's end for Tab, as it does for an item without a
 * `textEdit`. An additional edit is kept only if it ends before the first change, where the server's positions still
 * hold; additional edits that overlap the item's own are left out too. The additional edits come first in the list,
 * so an import inserted where the item's text starts goes before it.
 */
internal fun acceptEdits(
    item: CompletionItem,
    replace: Boolean,
    place: AcceptPlace,
): AcceptEdits {
    val textEdit = item.textEdit
    var text =
        when {
            textEdit == null -> item.insertText ?: item.label.orEmpty()
            textEdit.isLeft -> textEdit.left.newText.orEmpty()
            else -> textEdit.right.newText.orEmpty()
        }
    if (item.insertTextFormat == InsertTextFormat.Snippet) text = snippetText(text)
    val serverRange =
        when {
            textEdit == null -> null
            textEdit.isLeft -> textEdit.left.range
            replace -> textEdit.right.replace
            else -> textEdit.right.insert
        }
    val firstChange = place.firstChange?.let(place.positionOf)
    val end = if (replace) place.wordEnd else place.caret
    val main =
        when {
            serverRange == null -> {
                OffsetEdit(place.wordStart, end, text)
            }

            firstChange == null && place.caret == place.requestCaret -> {
                val offsets = place.offsetsOf(serverRange)
                OffsetEdit(offsets.first, offsets.last + 1, text)
            }

            firstChange == null || !isAfter(serverRange.start, firstChange) -> {
                val start = place.offsetsOf(serverRange).first
                OffsetEdit(start, end.coerceAtLeast(start), text)
            }

            else -> {
                OffsetEdit(place.wordStart, end, text)
            }
        }
    val additional = item.additionalTextEdits.orEmpty().filterNotNull()
    val kept =
        additional
            .filter { edit -> firstChange == null || !isAfter(edit.range.end, firstChange) }
            .map { edit -> offsetEdit(edit, place) }
            .filter { edit -> !overlaps(edit, main) }
    return AcceptEdits(kept + main, additional.size - kept.size)
}

/**
 * [snippet] (LSP snippet syntax) as plain text: tab stops (`$1`, `$0`) and variables go, placeholders (`${1:name}`)
 * become their text, choices (`${1|a,b|}`) their first option, and `\$`, `\}`, `\\` their character. Thoreau declares
 * no snippet support, so a server should not send any.
 */
internal fun snippetText(snippet: String): String {
    val out = StringBuilder()
    SnippetReader(snippet).readInto(out, stopAtBrace = false)
    return out.toString()
}

private class SnippetReader(
    private val text: String,
) {
    private var at = 0

    /** Reads text until the end, or until an unescaped `}` if [stopAtBrace] (consumed). */
    fun readInto(
        out: StringBuilder,
        stopAtBrace: Boolean,
    ) {
        while (at < text.length) {
            val c = text[at]
            when {
                c == '\\' && at + 1 < text.length && text[at + 1] in "\$}\\,|" -> {
                    out.append(text[at + 1])
                    at += 2
                }

                c == '}' && stopAtBrace -> {
                    at++
                    return
                }

                c == '$' && at + 1 < text.length && text[at + 1] == '{' -> {
                    at += 2
                    readPlaceholder(out)
                }

                c == '$' && at + 1 < text.length && (text[at + 1].isLetterOrDigit() || text[at + 1] == '_') -> {
                    at++
                    while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
                }

                else -> {
                    out.append(c)
                    at++
                }
            }
        }
    }

    /** After `${`: the tab stop or variable name, then `:placeholder}`, `|choices|}` or `}`. */
    private fun readPlaceholder(out: StringBuilder) {
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
        if (at >= text.length) return
        when (text[at]) {
            ':' -> {
                at++
                readInto(out, stopAtBrace = true)
            }

            '|' -> {
                at++
                val choice = StringBuilder()
                while (at < text.length && text[at] != ',' && text[at] != '|') {
                    if (text[at] == '\\' && at + 1 < text.length) at++
                    choice.append(text[at])
                    at++
                }
                out.append(choice)
                while (at < text.length && text[at] != '}') at++
                at++
            }

            else -> {
                // `}` or anything unexpected: skip to the end of the placeholder.
                while (at < text.length && text[at] != '}') at++
                at++
            }
        }
    }
}

private fun offsetEdit(
    edit: TextEdit,
    place: AcceptPlace,
): OffsetEdit {
    val offsets = place.offsetsOf(edit.range)
    return OffsetEdit(offsets.first, offsets.last + 1, edit.newText.orEmpty())
}

/** Whether [a] lies after [b]. */
private fun isAfter(
    a: Position,
    b: Position,
): Boolean = a.line > b.line || (a.line == b.line && a.character > b.character)

private fun overlaps(
    a: OffsetEdit,
    b: OffsetEdit,
): Boolean = a.start < b.end && b.start < a.end
