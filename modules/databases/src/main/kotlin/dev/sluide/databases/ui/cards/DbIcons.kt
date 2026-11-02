package dev.sluide.databases.ui.cards

import dev.sluide.databases.connect.DatabaseKind
import javafx.scene.layout.Region
import javafx.scene.shape.SVGPath

/**
 * The database logos of the cards and the "+" menu, drawn from the path data in `postgresql.path` and `mariadb.path`
 * (one 24x24 path each) scaled to [SIZE] px.
 *
 * Source: Simple Icons 14.0.0 (CC0 1.0), `icons/postgresql.svg` and `icons/mariadb.svg` from
 * `https://raw.githubusercontent.com/simple-icons/simple-icons/14.0.0/icons/<name>.svg`; sha256 of the downloaded files:
 * postgresql.svg `c8ccadbd88c9312c2ad3734c4861b31d3485ce179ee91d64793143f83cfa745c`,
 * mariadb.svg `477435746bb2024048c7b71529f615fb70d7e679618f37bac49f78fd07e67dfe`.
 * The colours come from `cards.css` (`db-logo-postgresql`, `db-logo-mariadb`).
 */
internal object DbIcons {
    const val SIZE = 16.0

    private val paths = DatabaseKind.entries.associateWith { kind -> lazy { pathData(kind.name.lowercase()) } }

    private fun pathData(name: String): String =
        checkNotNull(DbIcons::class.java.getResource("$name.path")).readText().trim()

    /** A new logo node of [kind], [SIZE] px square. */
    fun logo(kind: DatabaseKind): Region =
        Region().apply {
            styleClass += listOf("db-logo", "db-logo-${kind.name.lowercase()}")
            shape = SVGPath().apply { content = checkNotNull(paths[kind]).value }
            isScaleShape = true
            setMinSize(SIZE, SIZE)
            setPrefSize(SIZE, SIZE)
            setMaxSize(SIZE, SIZE)
        }
}
