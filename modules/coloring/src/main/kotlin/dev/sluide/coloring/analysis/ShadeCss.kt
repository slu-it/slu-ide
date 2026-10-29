package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.cssText

/** One `--<prefix>-<step>: <css>;` line per shade. */
internal fun cssVariablesText(
    shades: List<Shade>,
    prefix: String,
): String = shades.joinToString("\n") { "--$prefix-${it.step}: ${cssText(it.color)};" }
