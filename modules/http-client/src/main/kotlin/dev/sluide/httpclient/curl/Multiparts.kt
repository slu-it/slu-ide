package dev.sluide.httpclient.curl

import dev.sluide.httpclient.parse.BodyPart

/** Reads a `multipart/form-data` body of a `.http` request into curl's `-F` fields, where it can. */
internal object Multiparts {
    private val BOUNDARY = Regex("""boundary\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
    private val PARAMETER = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^;\s]+))""")

    private sealed interface Line {
        data class Text(
            val text: String,
        ) : Line

        data class File(
            val path: String,
        ) : Line
    }

    /**
     * The fields of [body], or null if it is no list of fields with one value or one file each: other headers than
     * `Content-Disposition: form-data` and `Content-Type`, text and files mixed in a field, or text outside the parts.
     */
    fun parts(
        body: List<BodyPart>,
        headers: List<Pair<String, String>>,
    ): List<FormPart>? {
        val boundary = headers.valueOf(CONTENT_TYPE)?.let { BOUNDARY.find(it)?.groupValues?.get(1) } ?: return null
        val lines =
            body.flatMap { part ->
                when (part) {
                    is BodyPart.Text -> part.text.split('\n').map { Line.Text(it.removeSuffix("\r")) }
                    is BodyPart.FileRef -> listOf(Line.File(part.path.removePrefix("./")))
                }
            }
        val delimiter = Line.Text("--$boundary")
        val end = Line.Text("--$boundary--")
        if (lines.firstOrNull() != delimiter) return null
        val parts = mutableListOf<FormPart>()
        var index = 1
        while (true) {
            val next =
                (index until lines.size).firstOrNull { lines[it] == delimiter || lines[it] == end } ?: return null
            parts += part(lines.subList(index, next)) ?: return null
            if (lines[next] == end) {
                return parts.takeIf { lines.drop(next + 1).all { it is Line.Text && it.text.isBlank() } }
            }
            index = next + 1
        }
    }

    private fun part(lines: List<Line>): FormPart? {
        val blank = lines.indexOfFirst { it is Line.Text && it.text.isEmpty() }
        if (blank < 0) return null
        var name: String? = null
        var filename: String? = null
        var type: String? = null
        for (line in lines.subList(0, blank)) {
            val text = (line as? Line.Text)?.text ?: return null
            val headerName = text.substringBefore(':').trim()
            val value = text.substringAfter(':', "").trim()
            when {
                headerName.equals("Content-Disposition", ignoreCase = true) -> {
                    if (!value.substringBefore(';').trim().equals("form-data", ignoreCase = true)) return null
                    val parameters =
                        PARAMETER.findAll(value).associate {
                            it.groupValues[1].lowercase() to
                                it.value(2, 3)
                        }
                    name = parameters["name"]
                    filename = parameters["filename"]
                }

                headerName.equals(CONTENT_TYPE, ignoreCase = true) -> {
                    type = value
                }

                else -> {
                    return null
                }
            }
        }
        val content = lines.subList(blank + 1, lines.size)
        val file = (content.singleOrNull() as? Line.File)?.path
        val value =
            when {
                file != null && filename == null -> {
                    FormContent.FileText(file)
                }

                file != null -> {
                    FormContent.File(file, filename.takeIf { it != HttpRequestWriter.fileName(file) })
                }

                content.all { it is Line.Text } && filename == null -> {
                    FormContent.Value(content.joinToString("\n") { (it as Line.Text).text })
                }

                else -> {
                    return null
                }
            }
        return FormPart(name ?: return null, value, type)
    }

    private fun MatchResult.value(
        quoted: Int,
        plain: Int,
    ) = groups[quoted]?.value ?: groupValues[plain]
}
