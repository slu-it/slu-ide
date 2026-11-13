package dev.sluide.gitlog.ui

import dev.sluide.git.GitRefKind
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.layout.StackPane
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The icon's colour in a context menu, which only the scene root's stylesheets reach: it follows the highlighted item
 * (ADR 0009, `docs/ui-conventions.md`).
 */
internal const val CONTEXT_MENU_ICON_STYLE = "-fx-icon-color: -fx-text-background-color;"

/** The icon of a context menu item: the glyph font in the icon's own style stays, the colour is appended to it. */
internal fun contextMenuIcon(icon: Ikon) = FontIcon(icon).apply { style = "$style $CONTEXT_MENU_ICON_STYLE".trim() }

/** The menu item [id] with its [icon] and [text]; a ref name with `_` would lose it as a mnemonic. */
internal fun contextMenuItem(
    id: String,
    icon: Ikon,
    text: String,
    action: () -> Unit,
): MenuItem =
    MenuItem(text, contextMenuIcon(icon)).apply {
        this.id = id
        isMnemonicParsing = false
        setOnAction { action() }
    }

/** The items of the non-empty [sections] in one list, a separator between two sections and none at either end. */
internal fun withSeparators(sections: List<List<MenuItem>>): List<MenuItem> =
    sections
        .filter { it.isNotEmpty() }
        .flatMapIndexed { index, section -> (if (index > 0) listOf(SeparatorMenuItem()) else emptyList()) + section }

/**
 * The icon of a ref [kind] in a context menu, which does not get the module's stylesheet: a wrapper carries it (and
 * the `.git-log` colour definitions through its class, `git-log.css`), on the node as JavaFX replaces a popup scene's
 * stylesheets with the owner window's on every show.
 */
internal fun refMenuIcon(kind: GitRefKind) =
    StackPane(refIcon(kind)).apply {
        styleClass += "git-ref-menu-icon"
        stylesheets += GIT_LOG_CSS
    }
