package dev.sluide.pdf.engine

/** What [PdfDocument.open] found. Only [Opened] holds a document, which its receiver closes. */
sealed interface PdfOpenResult {
    class Opened(
        val document: PdfDocument,
    ) : PdfOpenResult

    /** The PDF is encrypted with a user password, and none was given. */
    data object NeedsPassword : PdfOpenResult

    /** The PDF is encrypted with a user password, and the given one is not it. */
    data object WrongPassword : PdfOpenResult

    /** The file cannot be read as a PDF; [reason] is short, for "Not a valid PDF: <reason>". */
    data class Invalid(
        val reason: String,
    ) : PdfOpenResult

    /** The PDF has no pages. */
    data object NoPages : PdfOpenResult

    /** Opening the PDF ran out of memory. */
    data object TooLarge : PdfOpenResult
}
