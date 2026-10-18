package dev.sluide.httpclient.envedit

/** A line of the environment table: the header of a section, or a row of the model. */
internal sealed interface EnvEntry {
    /** Tells the line apart across refreshes, to keep it expanded or selected. */
    val key: Any

    data object Variables : EnvEntry {
        override val key: Any get() = "variables"
    }

    data class Auth(
        val group: AuthGroup,
    ) : EnvEntry {
        override val key: Any get() = "auth:${group.id}"
    }

    data class Certificate(
        val rows: List<Row>,
    ) : EnvEntry {
        override val key: Any get() = "certificate"
    }

    /** A row; [variable] if it is in the Variables section, whose rows can change their type. */
    data class Item(
        val row: Row,
        val variable: Boolean,
    ) : EnvEntry {
        override val key: Any get() = row.path
    }
}
