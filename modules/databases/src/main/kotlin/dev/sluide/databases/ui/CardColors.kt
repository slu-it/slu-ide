package dev.sluide.databases.ui

import dev.sluide.databases.connect.CardColor
import javafx.scene.paint.Color

/** The colour of a card stripe or swatch, saturated enough to read on both themes; `null` for [CardColor.NONE]. */
fun color(c: CardColor): Color? =
    when (c) {
        CardColor.NONE -> null
        CardColor.RED -> Color.web("#e5484d")
        CardColor.ORANGE -> Color.web("#f76b15")
        CardColor.YELLOW -> Color.web("#e5b800")
        CardColor.GREEN -> Color.web("#30a46c")
        CardColor.BLUE -> Color.web("#3e63dd")
        CardColor.PURPLE -> Color.web("#8e4ec6")
    }
