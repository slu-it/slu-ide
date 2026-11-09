package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.pipepuzzle.Look
import javafx.scene.paint.Color

/** The colours of one [Look]. The board is dark in both app themes, so palettes do not follow the theme. */
internal data class LookPalette(
    val surround: Color,
    val tileBase: Color,
    /** Source and drain cannot be turned, so their cells read slightly different. */
    val fixedBase: Color,
    val grid: Color,
    val conduitOutline: Color,
    val conduitBody: Color,
    /** Joint rings at the cell edges (water only). */
    val joint: Color,
    val flow: Color,
    val flowCore: Color,
    val flowGlow: Color,
    val leak: Color,
    /** The leak colour fully transparent, the outer stop of the light leak glow. */
    val leakFade: Color,
    val cursor: Color,
    /** The body of the source and drain symbols, and the accent drawn on them. */
    val symbol: Color,
    val symbolAccent: Color,
    /** The grate's hole in the water look. */
    val grateHole: Color,
    /** The bulb glass while unlit in the power look. */
    val lampOff: Color,
) {
    /** [flowGlow] mixed with the tile colour into an opaque colour, so overlapping strokes and discs do not double up. */
    val haloColor: Color =
        tileBase.interpolate(Color.color(flowGlow.red, flowGlow.green, flowGlow.blue), flowGlow.opacity)

    companion object {
        private val WATER =
            LookPalette(
                surround = Color.web("#10151b"),
                tileBase = Color.web("#1a2129"),
                fixedBase = Color.web("#2a3541"),
                grid = Color.web("#27313b"),
                conduitOutline = Color.web("#b4bec9"),
                conduitBody = Color.web("#69747f"),
                joint = Color.web("#dbe3ea"),
                flow = Color.web("#2f8fe8"),
                flowCore = Color.web("#9fd8ff"),
                flowGlow = Color.web("#2f8fe8", 0.0),
                leak = Color.web("#5cb8ff"),
                leakFade = Color.web("#5cb8ff", 0.0),
                cursor = Color.web("#ffd54a"),
                symbol = Color.web("#8a96a3"),
                symbolAccent = Color.web("#d9e2ea"),
                grateHole = Color.web("#0b0f13"),
                lampOff = Color.web("#3d392f"),
            )
        private val POWER =
            LookPalette(
                surround = Color.web("#14120f"),
                tileBase = Color.web("#1f1c17"),
                fixedBase = Color.web("#312c23"),
                grid = Color.web("#2d2921"),
                conduitOutline = Color.web("#7d7466"),
                conduitBody = Color.web("#3b362f"),
                joint = Color.web("#7d7466"),
                flow = Color.web("#ffc81e"),
                flowCore = Color.web("#fff6c2"),
                flowGlow = Color.web("#ffc81e", 0.28),
                leak = Color.web("#ffe45c"),
                leakFade = Color.web("#ffe45c", 0.0),
                cursor = Color.web("#4fc3f7"),
                symbol = Color.web("#6f675a"),
                symbolAccent = Color.web("#ffd84a"),
                grateHole = Color.web("#0b0f13"),
                lampOff = Color.web("#3d392f"),
            )
        private val LIGHT =
            LookPalette(
                surround = Color.web("#0a0e16"),
                tileBase = Color.web("#101622"),
                fixedBase = Color.web("#1a2335"),
                grid = Color.web("#172030"),
                conduitOutline = Color.web("#1c2638"),
                conduitBody = Color.web("#33445f"),
                joint = Color.web("#33445f"),
                flow = Color.web("#1fd8ee"),
                flowCore = Color.web("#d6ffff"),
                flowGlow = Color.web("#1fd8ee", 0.3),
                leak = Color.web("#3df0ff"),
                leakFade = Color.web("#3df0ff", 0.0),
                cursor = Color.web("#ff6bd6"),
                symbol = Color.web("#4a6a8a"),
                symbolAccent = Color.web("#7fe8ff"),
                grateHole = Color.web("#0b0f13"),
                lampOff = Color.web("#3d392f"),
            )

        fun of(look: Look): LookPalette =
            when (look) {
                Look.WATER -> WATER
                Look.POWER -> POWER
                Look.LIGHT -> LIGHT
            }
    }
}
