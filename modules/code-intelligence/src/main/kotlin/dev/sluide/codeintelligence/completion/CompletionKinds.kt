package dev.sluide.codeintelligence.completion

import org.eclipse.lsp4j.CompletionItemKind
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignK
import org.kordamp.ikonli.materialdesign2.MaterialDesignL
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import org.kordamp.ikonli.materialdesign2.MaterialDesignT
import org.kordamp.ikonli.materialdesign2.MaterialDesignV

/** The colour group of a kind's icon: a style class with a looked-up colour in `code-intelligence-popups.css`. */
internal enum class KindGroup(
    val styleClass: String,
) {
    CALLABLE("completion-kind-callable"),
    TYPE("completion-kind-type"),
    VALUE("completion-kind-value"),
    MODULE("completion-kind-module"),
    OTHER("completion-kind-other"),
}

/** How a completion item kind shows: its icon, colour group and the name shown when the item has no detail. */
internal data class KindLook(
    val icon: Ikon,
    val group: KindGroup,
    val name: String,
)

/**
 * The look of each completion item kind (ADR 0147). ty sends instance and class attributes as `Struct` (Python has no
 * structs), so `Struct` looks like a field and is called an attribute; some of its items have no kind at all
 * ([kindLook] gives `null`: no icon, no name).
 */
private val KIND_LOOKS: Map<CompletionItemKind, KindLook> =
    mapOf(
        CompletionItemKind.Text to KindLook(MaterialDesignF.FORMAT_TEXT, KindGroup.OTHER, "text"),
        CompletionItemKind.Method to KindLook(MaterialDesignF.FUNCTION_VARIANT, KindGroup.CALLABLE, "method"),
        CompletionItemKind.Function to KindLook(MaterialDesignF.FUNCTION, KindGroup.CALLABLE, "function"),
        CompletionItemKind.Constructor to KindLook(MaterialDesignF.FUNCTION_VARIANT, KindGroup.CALLABLE, "constructor"),
        CompletionItemKind.Field to KindLook(MaterialDesignT.TAG_OUTLINE, KindGroup.VALUE, "field"),
        CompletionItemKind.Variable to KindLook(MaterialDesignV.VARIABLE, KindGroup.VALUE, "variable"),
        CompletionItemKind.Class to KindLook(MaterialDesignC.CUBE_OUTLINE, KindGroup.TYPE, "class"),
        CompletionItemKind.Interface to KindLook(MaterialDesignC.CUBE_OUTLINE, KindGroup.TYPE, "interface"),
        CompletionItemKind.Module to KindLook(MaterialDesignP.PACKAGE_VARIANT_CLOSED, KindGroup.MODULE, "module"),
        CompletionItemKind.Property to KindLook(MaterialDesignT.TAG_OUTLINE, KindGroup.VALUE, "property"),
        CompletionItemKind.Unit to KindLook(MaterialDesignF.FORMAT_TEXT, KindGroup.OTHER, "unit"),
        CompletionItemKind.Value to KindLook(MaterialDesignV.VARIABLE, KindGroup.VALUE, "value"),
        CompletionItemKind.Enum to KindLook(MaterialDesignC.CUBE_OUTLINE, KindGroup.TYPE, "enum"),
        CompletionItemKind.Keyword to KindLook(MaterialDesignK.KEY_VARIANT, KindGroup.OTHER, "keyword"),
        CompletionItemKind.Snippet to KindLook(MaterialDesignF.FORMAT_TEXT, KindGroup.OTHER, "snippet"),
        CompletionItemKind.Color to KindLook(MaterialDesignF.FORMAT_TEXT, KindGroup.OTHER, "color"),
        CompletionItemKind.File to KindLook(MaterialDesignF.FILE_OUTLINE, KindGroup.MODULE, "file"),
        CompletionItemKind.Reference to KindLook(MaterialDesignL.LINK_VARIANT, KindGroup.OTHER, "reference"),
        CompletionItemKind.Folder to KindLook(MaterialDesignF.FOLDER_OUTLINE, KindGroup.MODULE, "folder"),
        CompletionItemKind.EnumMember to KindLook(MaterialDesignA.ALPHA_E_BOX_OUTLINE, KindGroup.VALUE, "enum member"),
        CompletionItemKind.Constant to KindLook(MaterialDesignA.ALPHA_C_BOX_OUTLINE, KindGroup.VALUE, "constant"),
        CompletionItemKind.Struct to KindLook(MaterialDesignT.TAG_OUTLINE, KindGroup.VALUE, "attribute"),
        CompletionItemKind.Event to KindLook(MaterialDesignF.FUNCTION, KindGroup.CALLABLE, "event"),
        CompletionItemKind.Operator to KindLook(MaterialDesignF.FORMAT_TEXT, KindGroup.OTHER, "operator"),
        CompletionItemKind.TypeParameter to KindLook(MaterialDesignC.CUBE_OUTLINE, KindGroup.TYPE, "type parameter"),
    )

/** The look of [kind]; `null` for an item without a kind. */
internal fun kindLook(kind: CompletionItemKind?): KindLook? = kind?.let(KIND_LOOKS::get)

/** The longest detail shown beside an item's label; the detail pane shows it whole. */
internal const val DETAIL_LENGTH = 40

/** The secondary text of an item's row: its detail on one line, shortened to [DETAIL_LENGTH], else its kind's name. */
internal fun secondaryText(
    detail: String?,
    kind: CompletionItemKind?,
): String? {
    val oneLine = detail?.replace(Regex("\\s+"), " ")?.trim()
    if (oneLine.isNullOrEmpty()) return kindLook(kind)?.name
    return if (oneLine.length <= DETAIL_LENGTH) oneLine else oneLine.take(DETAIL_LENGTH - 1) + "…"
}
