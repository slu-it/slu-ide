package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.EnvironmentFiles
import tools.jackson.databind.JsonNode

/** The environment file a value lives in. */
internal enum class FileSide(
    val fileName: String,
) {
    PUBLIC(EnvironmentFiles.PUBLIC),
    PRIVATE(EnvironmentFiles.PRIVATE),
    ;

    val other: FileSide get() = if (this == PUBLIC) PRIVATE else PUBLIC
}

/** The type of a row, choosing its value editor; [MIXED] when the environments disagree (then read-only). */
internal enum class ValueType { TEXT, NUMBER, YES_NO, NULL, OBJECT, LIST, MIXED }

/** One step of a [ValuePath]: a key of an object or an index into a list. */
internal sealed interface Segment {
    data class Key(
        val name: String,
    ) : Segment {
        override fun toString() = name
    }

    data class Index(
        val index: Int,
    ) : Segment {
        override fun toString() = "[$index]"
    }
}

/**
 * A path inside an environment object, e.g. `Security`, `Auth`, `api`, `Token URL`. The empty path is the
 * environment object itself.
 */
internal data class ValuePath(
    val segments: List<Segment>,
) {
    val isRoot: Boolean get() = segments.isEmpty()
    val size: Int get() = segments.size
    val last: Segment? get() = segments.lastOrNull()

    /** This path without its last segment; the root for a path of one segment. */
    val parent: ValuePath get() = ValuePath(segments.dropLast(1))

    fun key(name: String) = ValuePath(segments + Segment.Key(name))

    fun index(index: Int) = ValuePath(segments + Segment.Index(index))

    /** The first [count] segments. */
    fun take(count: Int) = ValuePath(segments.take(count))

    override fun toString() =
        segments.withIndex().joinToString("") { (i, segment) ->
            if (i > 0 && segment is Segment.Key) ".$segment" else segment.toString()
        }

    companion object {
        val ROOT = ValuePath(emptyList())

        fun of(vararg keys: String) = ValuePath(keys.map { Segment.Key(it) })
    }
}

/**
 * The value of a row in one environment. [value] is null when the environment does not define it.
 *
 * [side] is the file the value is taken from as a whole: for a text, number, yes/no, null or list the file holding it
 * (the private one when both do, then [overridesPublic]); for anything inside a list the list's file. It is null for
 * objects that merge key by key, whichever file holds them, since their entries each have their own file; and for
 * undefined values.
 */
internal data class Cell(
    val value: JsonNode?,
    val side: FileSide?,
    val overridesPublic: Boolean,
) {
    companion object {
        val UNDEFINED = Cell(null, null, false)
    }
}

/**
 * One row of the table: a key (or list index) at [path] in every environment.
 *
 * [cells] has an entry for every environment. [children] are the entries of object and list rows. [defaultSide] is
 * the file a new value goes to, [sideFixed] whether the lock can be toggled at all. [readOnly] rows ([ValueType.MIXED],
 * structures the form cannot show) are not edited in the form. [choices] are the values offered for choice fields
 * (an empty text meaning "not given" where that applies). [applicable] are the environments where the field applies,
 * e.g. an auth configuration's Username only where its grant type is Password.
 */
internal data class Row(
    val path: ValuePath,
    val label: String,
    val type: ValueType,
    val cells: Map<String, Cell>,
    val children: List<Row>,
    val defaultSide: FileSide,
    val sideFixed: Boolean,
    val readOnly: Boolean,
    val choices: List<String>?,
    val applicable: Set<String>,
)

/** The rows of one OAuth2 configuration `Security.Auth.<id>`: all known fields, then unknown keys. */
internal data class AuthGroup(
    val id: String,
    val rows: List<Row>,
)

/**
 * Both environment files merged into one table.
 *
 * [environments] are the top-level keys of both files (public first), without `$` keys. Columns of
 * [readOnlyEnvironments] are not objects in one of the files and are not edited. [certificate] is null when no
 * environment has an `SSLConfiguration`. [problems] are what the form does not show; those values stay untouched. With
 * an [error] (a file is not valid JSON or not an object) the table is empty and the form read-only.
 */
internal data class EnvTable(
    val environments: List<String>,
    val readOnlyEnvironments: Set<String>,
    val variables: List<Row>,
    val auth: List<AuthGroup>,
    val certificate: List<Row>?,
    val problems: List<String>,
    val error: String?,
) {
    companion object {
        fun failed(error: String) =
            EnvTable(emptyList(), emptySet(), emptyList(), emptyList(), null, emptyList(), error)
    }
}

/**
 * The result of an edit: the new full text of each changed file, null for a file that did not change. A non-null
 * text for a file the document did not have (its text was null) means that file is to be created.
 */
internal data class EnvEdit(
    val public: String?,
    val private: String?,
) {
    val isEmpty: Boolean get() = public == null && private == null
}

/** The two files of an `SSLConfiguration` entry that have a path and an optional format. */
internal enum class CertificatePart { CERTIFICATE, KEY }
