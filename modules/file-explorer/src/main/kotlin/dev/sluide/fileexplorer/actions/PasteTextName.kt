package dev.sluide.fileexplorer.actions

import dev.sluide.texttypes.TextType
import java.nio.file.Path

/** The name saved for [typed] and [type]: the extension is always appended; "No extension" keeps the name as typed. */
internal fun finalTextName(
    typed: String,
    type: TextType,
): String {
    val extension = type.extension
    return if (extension == null) typed else "$typed.$extension"
}

/** The problem with the name [typed] saved as [type] in [folder], judged on the final name; `null` if none. */
internal fun textNameProblem(
    typed: String,
    type: TextType,
    folder: Path,
): String? = pastedNameProblem(typed, finalTextName(typed, type), folder)

/** What the name [typed] is saved as, for a name without a problem; `null` for a blank name or a folder path. */
internal fun textNameNote(
    typed: String,
    type: TextType,
): String? = if (typed.isBlank() || endsWithSeparator(typed)) null else "Saved as “${finalTextName(typed, type)}”."

/** The combo's text for [type]: `JSON (.json)`, `No extension`; " · detected" is added for the [detected] one. */
internal fun textTypeLabel(
    type: TextType,
    detected: TextType,
): String {
    val extension = type.extension
    val label = if (extension == null) type.name else "${type.name} (.$extension)"
    return if (type == detected) "$label · detected" else label
}
