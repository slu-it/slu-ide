package dev.sluide.httpclient.ui

/** [text], or its first `limit - 1` characters and an ellipsis if it is longer than [limit]. */
internal fun truncated(
    text: String,
    limit: Int,
): String = if (text.length > limit) text.take(limit - 1) + "…" else text
