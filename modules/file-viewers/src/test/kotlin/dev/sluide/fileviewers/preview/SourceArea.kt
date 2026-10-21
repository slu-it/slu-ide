package dev.sluide.fileviewers.preview

import org.fxmisc.richtext.CodeArea

/** The RichTextFX area of the view's source, which the public [dev.sluide.fileviewers.CodeEditor] hides. */
internal val PreviewFileView.sourceArea: CodeArea
    get() = source.node.lookup(".code-area") as CodeArea
