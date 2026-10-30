package dev.sluide.converting.data

import dev.sluide.converting.ConversionOption

/** Options shared by several data formats; ids are global across families (see [ConversionOption]). */
internal object DataOptions {
    val indent = ConversionOption.Choice("indent", "Indent", listOf("2", "4"), "2")
    val sortKeys = ConversionOption.Toggle("sortKeys", "Sort keys", false)
    val compact = ConversionOption.Toggle("compact", "Compact", false)
}
