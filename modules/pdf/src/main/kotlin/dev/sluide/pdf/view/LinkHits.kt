package dev.sluide.pdf.view

import dev.sluide.pdf.engine.LinkTarget
import dev.sluide.pdf.engine.PageLink

/** The link under the page point ([x], [y]): the topmost, which is the last of [links] whose rect contains it. */
internal fun linkAt(
    links: List<PageLink>,
    x: Double,
    y: Double,
): PageLink? =
    links.lastOrNull { link ->
        val rect = link.rect
        x >= rect.x && x <= rect.x + rect.width && y >= rect.y && y <= rect.y + rect.height
    }

/** What a tooltip says of where a link leads: the address of a web link, "Page N" of an internal one. */
internal fun LinkTarget.label(): String =
    when (this) {
        is LinkTarget.Web -> uri.toString()
        is LinkTarget.Page -> "Page ${target.page + 1}"
    }
