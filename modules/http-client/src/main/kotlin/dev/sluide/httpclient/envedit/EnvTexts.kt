package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.EnvKeys
import java.nio.file.Path

/** What the environment table shows, told apart from its controls so that it is tested without a toolkit. */
internal object EnvTexts {
    /** What a private value shows while Show values is off. */
    const val MASK = "••••••"

    /** What a value not defined in an environment shows. */
    const val UNDEFINED = "—"

    /** The types a variable can be given, in the order offered. */
    val VARIABLE_TYPES =
        listOf(ValueType.TEXT, ValueType.NUMBER, ValueType.YES_NO, ValueType.NULL, ValueType.OBJECT, ValueType.LIST)

    fun typeLabel(type: ValueType): String =
        when (type) {
            ValueType.TEXT -> "Text"
            ValueType.NUMBER -> "Number"
            ValueType.YES_NO -> "Yes/No"
            ValueType.NULL -> "Null"
            ValueType.OBJECT -> "Object"
            ValueType.LIST -> "List"
            ValueType.MIXED -> "Mixed"
        }

    /** The type shown for [row]: Choice for fields with [choices][Row.choices], else its [type][Row.type]. */
    fun typeLabel(row: Row): String = if (row.choices != null) "Choice" else typeLabel(row.type)

    /**
     * Whether [cell] is hidden behind [MASK]: a private text or number while values are not shown. An empty text is
     * not, so that a secret not set yet does not look set.
     */
    fun isMasked(
        cell: Cell,
        showValues: Boolean,
    ): Boolean {
        val value = cell.value ?: return false
        val shown = value.isNumber || (value.isString && value.stringValue().isNotEmpty())
        return !showValues && cell.side == FileSide.PRIVATE && shown
    }

    /**
     * The text of [cell]: [UNDEFINED], the raw text, the number as written, `true`/`false`, `null`, `{n}` or `[n]`
     * with the number of entries; [MASK] when [masked].
     */
    fun display(
        cell: Cell,
        masked: Boolean,
    ): String {
        val value = cell.value ?: return UNDEFINED
        if (masked) return MASK
        return when {
            value.isString -> value.stringValue()
            value.isNumber -> EnvJson.numberText(value)
            value.isBoolean -> value.booleanValue().toString()
            value.isObject -> "{${value.size()}}"
            value.isArray -> "[${value.size()}]"
            else -> "null"
        }
    }

    /** The text a value editor starts with: the text or number as written, empty for anything else. */
    fun editText(cell: Cell): String {
        val value = cell.value ?: return ""
        return when {
            value.isString -> value.stringValue()
            value.isNumber -> EnvJson.numberText(value)
            else -> ""
        }
    }

    /** The environments that have the auth configuration of [group]. */
    fun authEnvironments(group: AuthGroup): Set<String> = row(group, EnvKeys.Auth.TYPE)?.applicable.orEmpty()

    /** The grant type of the first environment that has one, Client Credentials if none has. */
    fun grantType(group: AuthGroup): String = firstText(group, EnvKeys.Auth.GRANT_TYPE) ?: EnvSchema.GRANT_TYPES.first()

    /** The type and grant type of the first environment that gives them, e.g. "OAuth2 · Client Credentials". */
    fun authSummary(group: AuthGroup): String =
        listOfNotNull(firstText(group, EnvKeys.Auth.TYPE), firstText(group, EnvKeys.Auth.GRANT_TYPE))
            .joinToString(" · ")

    /** The environments that have a client certificate. */
    fun certificateEnvironments(table: EnvTable): Set<String> =
        table.certificate
            ?.firstOrNull()
            ?.applicable
            .orEmpty()

    private fun row(
        group: AuthGroup,
        key: String,
    ): Row? = group.rows.firstOrNull { it.path == EnvSchema.authPath(group.id).key(key) }

    private fun firstText(
        group: AuthGroup,
        key: String,
    ): String? =
        row(group, key)
            ?.cells
            ?.values
            ?.firstNotNullOfOrNull { cell ->
                cell.value
                    ?.takeIf { it.isString }
                    ?.stringValue()
                    ?.ifBlank { null }
            }

    /**
     * How a certificate file chosen in a file chooser is written: relative to [folder] (the private file's folder,
     * with `/`) when it is inside it, else absolute.
     */
    fun certificatePath(
        folder: Path,
        file: Path,
    ): String {
        val base = folder.toAbsolutePath().normalize()
        val target = file.toAbsolutePath().normalize()
        return if (target != base && target.startsWith(base)) {
            base.relativize(target).joinToString("/")
        } else {
            target.toString()
        }
    }
}

/** The texts the environment files would have after [edit]. */
internal fun EnvDocument.after(edit: EnvEdit): EnvDocument =
    EnvDocument(edit.public ?: publicText, edit.private ?: privateText)

/**
 * The edits of [steps] applied one after the other, starting from [document], as one edit of the files that changed;
 * each step gets the document the steps before left.
 */
internal fun chain(
    document: EnvDocument,
    steps: List<(EnvDocument) -> EnvEdit>,
): EnvEdit {
    val last = steps.fold(document) { current, step -> current.after(step(current)) }
    return EnvEdit(
        public = last.publicText.takeIf { it != document.publicText },
        private = last.privateText.takeIf { it != document.privateText },
    )
}
