package dev.sluide.barcodes.model

import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.GenerateOptions

/**
 * What to keep in [GenerateOptions.quietZone] when the user sets [shown] modules for [format]: null (the format's own
 * default, which follows a change of format) when [shown] is that default, else [shown].
 */
internal fun quietZoneToKeep(
    options: GenerateOptions,
    format: CodeFormat,
    shown: Int,
): Int? = shown.takeIf { it != options.copy(quietZone = null).quietZoneFor(format) }
