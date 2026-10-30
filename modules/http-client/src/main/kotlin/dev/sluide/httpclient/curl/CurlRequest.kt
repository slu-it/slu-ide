package dev.sluide.httpclient.curl

import dev.sluide.httpclient.parse.HttpVersion
import java.time.Duration

/**
 * A request between a `curl` command and a `.http` request (ADR 0116): what both can say, with the [headers] as sent
 * (curl's implied ones, e.g. the form content type of `-d`, written out). Texts keep `{{variables}}` and `$VARS` as
 * written.
 */
internal data class CurlRequest(
    val url: String,
    val method: String = "GET",
    val headers: List<Pair<String, String>> = emptyList(),
    val body: RequestBody = RequestBody.None,
    val version: HttpVersion? = null,
    val followRedirects: Boolean = false,
    /** The whole exchange: curl's `-m`, the client's `@timeout`. */
    val timeout: Duration? = null,
    val connectTimeout: Duration? = null,
    /** curl's `-k`; the `.http` format has no such tag. */
    val insecure: Boolean = false,
    val credentials: Credentials? = null,
    /** The request's name: `### name` in a `.http` file, a comment line before a command. */
    val name: String? = null,
)

/** curl's `-u user:password` (with `--digest`), the client's `Authorization: Basic user password`. */
internal data class Credentials(
    val user: String,
    val password: String,
    val digest: Boolean = false,
)

internal sealed interface RequestBody {
    data object None : RequestBody

    /** [pieces] sent one after the other, joined with `&` as curl joins several `-d`. */
    data class Data(
        val pieces: List<DataPiece>,
    ) : RequestBody

    /** `multipart/form-data`, curl's `-F`. */
    data class Multipart(
        val parts: List<FormPart>,
    ) : RequestBody
}

internal sealed interface DataPiece {
    data class Text(
        val text: String,
    ) : DataPiece

    /** A file sent as the body: as it is when [binary] (`--data-binary @file`), without line breaks if not (`-d @file`). */
    data class File(
        val path: String,
        val binary: Boolean = true,
    ) : DataPiece
}

internal data class FormPart(
    val name: String,
    val content: FormContent,
    val type: String? = null,
)

internal sealed interface FormContent {
    data class Value(
        val text: String,
    ) : FormContent

    /** A file upload, `name=@file`; [filename] only when it is not the file's own name. */
    data class File(
        val path: String,
        val filename: String? = null,
    ) : FormContent

    /** A field whose value is read from a file, `name=<file`. */
    data class FileText(
        val path: String,
    ) : FormContent
}

/** The headers whose [name] is not [name] (case-insensitive). */
internal fun List<Pair<String, String>>.without(name: String) = filterNot { it.first.equals(name, ignoreCase = true) }

/** The value of the last header named [name] (case-insensitive), if any. */
internal fun List<Pair<String, String>>.valueOf(name: String) =
    lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

internal const val CONTENT_TYPE = "Content-Type"
internal const val FORM_TYPE = "application/x-www-form-urlencoded"
