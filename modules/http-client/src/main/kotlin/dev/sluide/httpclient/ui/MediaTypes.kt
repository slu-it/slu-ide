package dev.sluide.httpclient.ui

/** What a `Content-Type` says about how to show a body. */
internal object MediaTypes {
    /** The media type of [contentType] without parameters, lower case (`text/html; charset=utf-8` → `text/html`). */
    fun mediaType(contentType: String?): String =
        contentType
            .orEmpty()
            .substringBefore(';')
            .trim()
            .lowercase()

    /** The language to highlight a body of [contentType] with: `json`, `xml`, `html` or `null` for none. */
    fun language(contentType: String?): String? {
        val type = mediaType(contentType)
        return when {
            type == "application/json" || type == "text/json" || type.endsWith("+json") -> "json"
            type == "application/xml" || type == "text/xml" || type.endsWith("+xml") -> "xml"
            type == "text/html" -> "html"
            else -> null
        }
    }
}
