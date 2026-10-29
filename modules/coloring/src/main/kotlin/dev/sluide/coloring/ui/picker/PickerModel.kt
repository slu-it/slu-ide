package dev.sluide.coloring.ui.picker

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.inSrgbGamut
import dev.sluide.coloring.color.toSrgbGamut

/**
 * The picker's own hue, saturation, value (0..100) and alpha. A color in the state cannot hold them all: a gray has no
 * hue and black no saturation, so dragging the saturation to 0 would lose the hue and the marker would jump. Here they
 * stay until the user moves them. User input goes out through [emit] as an HSV color; a color that comes in
 * from outside through [external] is read back, unless it is the one just emitted. No JavaFX.
 */
internal class PickerModel(
    initial: CssColor,
    private val emit: (CssColor) -> Unit,
) {
    var h = 0.0
        private set
    var s = 0.0
        private set
    var v = 0.0
        private set
    var a = 1.0
        private set

    /** Runs after the values changed, from either side. */
    var onChange: () -> Unit = {}

    private var lastEmitted: CssColor? = null

    init {
        read(initial)
    }

    /** The user set some of the values; the others stay. */
    fun input(
        h: Double = this.h,
        s: Double = this.s,
        v: Double = this.v,
        a: Double = this.a,
    ) {
        this.h = h.coerceIn(0.0, MAX_HUE)
        this.s = s.coerceIn(0.0, MAX_PERCENT)
        this.v = v.coerceIn(0.0, MAX_PERCENT)
        this.a = a.coerceIn(0.0, 1.0)
        val color = CssColor(ColorSpace.HSV, this.h, this.s, this.v, this.a)
        lastEmitted = color
        emit(color)
        onChange()
    }

    /** The state's color changed; ignored if it is the color this model emitted. */
    fun external(color: CssColor) {
        if (color == lastEmitted) return
        lastEmitted = null
        read(color)
        onChange()
    }

    private fun read(color: CssColor) {
        val hsv = (if (color.inSrgbGamut()) color else color.toSrgbGamut()).to(ColorSpace.HSV)
        val achromatic = hsv.isHueMissing || hsv.c1 < EPSILON
        if (!achromatic) h = hsv.c0
        if (hsv.c2 >= EPSILON) s = hsv.c1
        v = hsv.c2
        a = hsv.alpha.coerceIn(0.0, 1.0)
    }

    private companion object {
        const val MAX_HUE = 360.0
        const val MAX_PERCENT = 100.0
        const val EPSILON = 1e-6
    }
}
