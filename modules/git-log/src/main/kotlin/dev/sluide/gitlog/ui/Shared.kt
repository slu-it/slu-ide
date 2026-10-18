package dev.sluide.gitlog.ui

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

/** As many characters of the hash as the details show. */
internal const val SHORT_HASH = 8

internal val COPY = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)

private object Resources

internal val GIT_LOG_CSS: String =
    checkNotNull(Resources::class.java.getResource("/dev/sluide/gitlog/git-log.css")).toExternalForm()
