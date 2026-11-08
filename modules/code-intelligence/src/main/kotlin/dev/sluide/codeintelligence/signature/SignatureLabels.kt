package dev.sluide.codeintelligence.signature

import dev.sluide.codeintelligence.completion.isWordChar
import dev.sluide.codeintelligence.ui.DocBlock
import dev.sluide.codeintelligence.ui.docBlocks
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp

/**
 * What the signature popup shows (ADR 0147): the active signature's [label] with the active parameter's characters
 * [active] (`start until end`, `null` for none), "2 of 3" ([count]) when there are several signatures, and the
 * parameter's and the signature's documentation.
 */
internal data class SignatureShown(
    val label: String,
    val active: IntRange?,
    val count: String?,
    val docs: List<DocBlock>,
)

/**
 * The signature to show for [help]: its active signature (`activeSignature`, clamped; the first without one) and in it
 * the active parameter (the signature's `activeParameter`, else the answer's); `null` without signatures.
 */
internal fun signatureShown(help: SignatureHelp?): SignatureShown? {
    val signatures = help?.signatures.orEmpty().filterNotNull()
    if (signatures.isEmpty()) return null
    val index = (help?.activeSignature ?: 0).coerceIn(0, signatures.lastIndex)
    val signature = signatures[index]
    val label = signature.label.orEmpty()
    val parameterIndex = signature.activeParameter ?: help?.activeParameter
    val parameter = parameterIndex?.let { signature.parameters.orEmpty().getOrNull(it) }
    val parameterDocs = docBlocks(parameter?.documentation)
    val signatureDocs = docBlocks(signature.documentation)
    val docs =
        if (parameterDocs.isNotEmpty() && signatureDocs.isNotEmpty()) {
            parameterDocs + DocBlock.Rule + signatureDocs
        } else {
            parameterDocs + signatureDocs
        }
    return SignatureShown(
        label = label,
        active = parameter?.let { parameterRange(label, it) },
        count = if (signatures.size > 1) "${index + 1} of ${signatures.size}" else null,
        docs = docs,
    )
}

/**
 * Where [parameter] is in the signature [label], as `start until end`: its label's offsets (UTF-16, clamped), or its
 * label text, found after the label's first `(` as a whole word if it can be, else anywhere after it; `null` if it is
 * not there.
 */
internal fun parameterRange(
    label: String,
    parameter: ParameterInformation,
): IntRange? {
    val parameterLabel = parameter.label ?: return null
    if (parameterLabel.isRight) {
        val offsets = parameterLabel.right ?: return null
        val start = (offsets.first ?: return null).coerceIn(0, label.length)
        val end = (offsets.second ?: return null).coerceIn(start, label.length)
        return if (end > start) start until end else null
    }
    val text = parameterLabel.left
    if (text.isNullOrEmpty()) return null
    val from = label.indexOf('(') + 1
    var at = label.indexOf(text, from)
    var first: Int? = null
    while (at >= 0) {
        if (first == null) first = at
        if (isWholeWord(label, at, text.length)) return at until at + text.length
        at = label.indexOf(text, at + 1)
    }
    return first?.let { it until it + text.length }
}

/** Whether [length] chars at [start] of [text] are not part of a longer word. */
private fun isWholeWord(
    text: String,
    start: Int,
    length: Int,
): Boolean {
    val before = text.getOrNull(start - 1)
    val after = text.getOrNull(start + length)
    val wordStart = isWordChar(text[start])
    val wordEnd = isWordChar(text[start + length - 1])
    return !(wordStart && before != null && isWordChar(before)) && !(wordEnd && after != null && isWordChar(after))
}
