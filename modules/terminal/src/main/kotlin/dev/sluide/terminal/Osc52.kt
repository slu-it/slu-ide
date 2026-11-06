package dev.sluide.terminal

import java.util.Base64

/** The longest OSC 52 payload accepted, in base64 characters (3 MiB of text); longer ones are ignored (ADR 0133). */
internal const val OSC_52_MAX_PAYLOAD = 4 * 1024 * 1024

/**
 * The text an OSC 52 sequence (`ESC ] 52 ; <targets> ; <base64> BEL`) asks to put on the clipboard, given its
 * [arguments] after `52;`. All targets (`c`, `p`, `s`, `0`-`7`, any mix, none) mean the system clipboard. `null` when
 * there is nothing to copy: a query (`?`, never answered), no `;`, invalid base64, an empty payload or one over
 * [OSC_52_MAX_PAYLOAD]. An empty payload would clear the clipboard in xterm; here it is ignored. Line breaks in the
 * payload are skipped: GNU `base64` breaks its output every 76 characters, and scripts often pass it on unchanged.
 */
internal fun clipboardText(arguments: String): String? {
    val separator = arguments.indexOf(';')
    if (separator < 0) return null
    val payload = arguments.substring(separator + 1)
    if (payload.isEmpty() || payload == "?" || payload.length > OSC_52_MAX_PAYLOAD) return null
    val bytes =
        try {
            Base64.getDecoder().decode(payload.filterNot { it == '\r' || it == '\n' })
        } catch (_: IllegalArgumentException) {
            return null
        }
    return bytes.toString(Charsets.UTF_8).ifEmpty { null }
}
