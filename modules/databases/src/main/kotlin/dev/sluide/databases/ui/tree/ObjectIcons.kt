package dev.sluide.databases.ui.tree

import dev.sluide.databases.meta.ObjectKind

/** The Material Design 2 icon literal of each kind of tree node. */
internal object ObjectIcons {
    fun literal(kind: ObjectKind): String =
        when (kind) {
            ObjectKind.SCHEMA -> "mdi2d-database-outline"
            ObjectKind.GROUP -> "mdi2f-folder-outline"
            ObjectKind.TABLE -> "mdi2t-table"
            ObjectKind.VIEW -> "mdi2t-table-eye"
            ObjectKind.MATERIALIZED_VIEW -> "mdi2t-table-large"
            ObjectKind.COLUMN -> "mdi2t-table-column"
            ObjectKind.PRIMARY_KEY -> "mdi2k-key"
            ObjectKind.FOREIGN_KEY -> "mdi2k-key-link"
            ObjectKind.INDEX -> "mdi2s-sort-variant"
            ObjectKind.UNIQUE -> "mdi2k-key-variant"
            ObjectKind.CHECK -> "mdi2c-check-circle-outline"
            ObjectKind.TRIGGER -> "mdi2l-lightning-bolt"
            ObjectKind.FUNCTION -> "mdi2f-function-variant"
            ObjectKind.PROCEDURE -> "mdi2c-code-braces"
            ObjectKind.SEQUENCE -> "mdi2s-sort-numeric-variant"
            ObjectKind.ENUM -> "mdi2f-format-list-bulleted-type"
            ObjectKind.DOMAIN -> "mdi2s-shape-outline"
            ObjectKind.COMPOSITE_TYPE -> "mdi2c-cube-outline"
            ObjectKind.EVENT -> "mdi2c-calendar-clock"
        }
}
