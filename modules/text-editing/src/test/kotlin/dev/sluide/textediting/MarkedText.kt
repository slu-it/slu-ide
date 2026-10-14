package dev.sluide.textediting

/** Splits a text marked with `|` for the caret and `^` for the anchor (if there is a selection). */
fun unmark(marked: String): Pair<String, Selection> {
    val caret = marked.replace("^", "").indexOf('|')
    val anchor = marked.replace("|", "").indexOf('^').takeIf { it >= 0 } ?: caret
    require(caret >= 0) { "no caret in $marked" }
    return marked.replace("|", "").replace("^", "") to Selection(anchor, caret)
}

/** [text] with the markers of [selection]. */
fun mark(
    text: String,
    selection: Selection,
): String {
    val markers = listOfNotNull(selection.caret to "|", (selection.anchor to "^").takeIf { !selection.isEmpty })
    return markers.sortedByDescending { it.first }.fold(text) { result, (offset, marker) ->
        result.substring(0, offset) + marker + result.substring(offset)
    }
}

/** The marked text after [operation] on the marked text [marked], or `null` if the operation returns no edit. */
fun edited(
    marked: String,
    operation: (String, Selection) -> TextEdit?,
): String? {
    val (text, selection) = unmark(marked)
    val edit = operation(text, selection) ?: return null
    return mark(edit.applyTo(text), edit.selection)
}
