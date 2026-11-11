package dev.sluide.ebooks

/** Thoreau's own CSS of the page: the reading style and the marks of Read Aloud and search. */
internal object ReadingCss {
    /**
     * The highlight CSS as CSS Custom Highlight API rules, light and dark. Never as classes: with the book's style
     * a book's own `class="mark-word"` would paint.
     */
    val marks: String =
        """
        ::highlight(mark-sentence) { background-color: rgba(255, 214, 102, 0.35); }
        ::highlight(mark-word) { background-color: rgba(255, 176, 0, 0.75); color: #000; }
        ::highlight(mark-start) { text-decoration-line: underline; text-decoration-color: #2f7de1; text-decoration-thickness: 2px; }
        ::highlight(mark-search) { background-color: rgba(255, 235, 59, 0.7); color: #000; }
        body.dark ::highlight(mark-sentence) { background-color: rgba(90, 120, 190, 0.35); }
        body.dark ::highlight(mark-word) { background-color: rgba(110, 150, 230, 0.7); color: #fff; }
        body.dark ::highlight(mark-start) { text-decoration-color: #6aa5ff; }
        body.dark ::highlight(mark-search) { background-color: rgba(190, 160, 30, 0.75); color: #fff; }
        """.trimIndent()

    /** Thoreau's reading style, used when the book's own style is off. */
    val reading: String =
        """
        html { background: #ffffff; }
        body { background: #ffffff; color: #1f1f1f; font-family: Georgia, "Times New Roman", "DejaVu Serif",
               "Liberation Serif", "Noto Serif", serif; font-size: 18px; line-height: 1.6; max-width: 40em; margin: 0 auto; padding: 1.5em 1em 4em; }
        body.dark, html:has(body.dark) { background: #1e1f22; color: #d4d4d4; }
        h1, h2, h3, h4, h5, h6 { line-height: 1.25; margin: 1.6em 0 0.6em; }
        h1 { font-size: 1.8em; } h2 { font-size: 1.5em; } h3 { font-size: 1.25em; } h4, h5, h6 { font-size: 1.05em; }
        p { margin: 0 0 0.9em; }
        blockquote { margin: 1em 1.5em; font-style: italic; }
        pre { white-space: pre-wrap; font-size: 0.9em; }
        code, pre { font-family: Menlo, Consolas, monospace; }
        img, svg { max-width: 100%; height: auto; }
        figure { margin: 1em 0; text-align: center; }
        figcaption { font-size: 0.9em; opacity: 0.8; }
        table { border-collapse: collapse; margin: 1em 0; max-width: 100%; }
        td, th { border: 1px solid #b0b0b0; padding: 0.3em 0.6em; vertical-align: top; }
        body.dark td, body.dark th { border-color: #5a5d63; }
        aside { font-size: 0.85em; opacity: 0.9; margin: 0.8em 0; }
        a { color: #1a5fb4; }
        body.dark a { color: #78aaf0; }
        hr { border: 0; border-top: 1px solid #b0b0b0; margin: 2em 0; }
        section + section { margin-top: 3em; }
        """.trimIndent()

    /** The frame that stays when the book's own style is on: width, images, and the colours under the book's CSS. */
    val frame: String =
        """
        body { max-width: 44em; margin: 0 auto; padding: 1em 1em 4em; }
        body.dark { background: #1e1f22; color: #d4d4d4; }
        img, svg { max-width: 100%; height: auto; }
        section + section { margin-top: 3em; }
        """.trimIndent()
}
