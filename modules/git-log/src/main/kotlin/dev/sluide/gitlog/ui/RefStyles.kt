package dev.sluide.gitlog.ui

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import javafx.scene.control.Label
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignT

/** The coloured tag icon of a [kind] of ref; the colours are looked-up colours of `git-log.css`. */
internal fun refIcon(kind: GitRefKind) =
    FontIcon(MaterialDesignT.TAG_OUTLINE).apply {
        styleClass += "git-ref-icon"
        styleClass += refStyleClass(kind)
    }

internal fun refStyleClass(kind: GitRefKind) =
    when (kind) {
        GitRefKind.HEAD -> "git-ref-head"
        GitRefKind.LOCAL_BRANCH -> "git-ref-local"
        GitRefKind.REMOTE_BRANCH -> "git-ref-remote"
        GitRefKind.TAG -> "git-ref-tag"
    }

/** One line per ref with its icon and short name, as in the tooltip and the details. */
internal fun refLines(refs: List<GitRef>): List<Label> =
    refs.map { ref -> Label(ref.shortName, refIcon(ref.kind)).apply { styleClass += "git-ref-line" } }
