package dev.sluide.databases.ui.cards

import dev.sluide.databases.connect.DatabaseKind
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.shape.SVGPath

/**
 * The database logos of the cards and the "+" menu, drawn from the path data in `postgresql.path` and `mariadb.path`
 * (one 24x24 path each) scaled to [SIZE] px.
 *
 * Source: Simple Icons 14.0.0 (CC0 1.0), `icons/postgresql.svg` and `icons/mariadb.svg` from
 * `https://raw.githubusercontent.com/simple-icons/simple-icons/14.0.0/icons/<name>.svg`; sha256 of the downloaded files:
 * postgresql.svg `c8ccadbd88c9312c2ad3734c4861b31d3485ce179ee91d64793143f83cfa745c`,
 * mariadb.svg `477435746bb2024048c7b71529f615fb70d7e679618f37bac49f78fd07e67dfe`.
 * The colours are fixed brand-like tones that read on both themes.
 */
internal object DbIcons {
    const val SIZE = 16.0

    private val postgres = Color.web("#4169E1")
    private val maria = Color.web("#C0765A")

    private fun pathData(name: String): String =
        checkNotNull(DbIcons::class.java.getResource("$name.path")).readText().trim()

    /** A new logo node of [kind], [SIZE] px square. */
    fun logo(kind: DatabaseKind): Region {
        val (file, color) =
            when (kind) {
                DatabaseKind.POSTGRESQL -> "postgresql" to postgres
                DatabaseKind.MARIADB -> "mariadb" to maria
            }
        return Region().apply {
            styleClass += "db-logo"
            shape = SVGPath().apply { content = pathData(file) }
            isScaleShape = true
            setMinSize(SIZE, SIZE)
            setPrefSize(SIZE, SIZE)
            setMaxSize(SIZE, SIZE)
            background = Background(BackgroundFill(color, null, null))
        }
    }
}
