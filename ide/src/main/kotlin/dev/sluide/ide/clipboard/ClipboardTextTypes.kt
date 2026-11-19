package dev.sluide.ide.clipboard

import dev.sluide.converting.FormatFamily
import dev.sluide.texttypes.TextTypeDetector
import org.springframework.stereotype.Component

/**
 * The detector of the type of a pasted text, shared by the windows: the built-in rules of `text-types` plus Converting's
 * data detection over the format families of all modules ([ConvertingTextTypes], ADR 0187). The explorer's Paste Text
 * and File → New Scratch from Clipboard use it.
 */
@Component
class ClipboardTextTypes(
    families: List<FormatFamily<*>>,
) {
    val detector: TextTypeDetector = TextTypeDetector(listOf(ConvertingTextTypes(families)))
}
