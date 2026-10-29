package dev.sluide.coloring.editor

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.detect.ColorFinder
import dev.sluide.coloring.detect.FoundColor
import dev.sluide.coloring.detect.SourceKind
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.util.Subscription

/**
 * Shows the colors written in style, markup and script files in the gutter of code editors and offers "Open in
 * Coloring" in their context menu (ADR 0109); [show] opens a color in the tool.
 *
 * The gutter's rows are created again by the editor for a paragraph whose text or index changes, so the swatches follow
 * edits and scrolling without further work; only a change of the file type ([ExtensibleCodeEditor.file], a rename) asks
 * for all rows again.
 */
internal class ColorEditorExtension(
    private val show: (CssColor) -> Unit,
) : CodeEditorExtension {
    override fun extend(editor: ExtensibleCodeEditor): AutoCloseable = Attached(editor)

    private inner class Attached(
        private val editor: ExtensibleCodeEditor,
    ) : AutoCloseable {
        private var kind: SourceKind? = kindOf()
        private var columnAdded = false
        private var closed = false

        /** Lines found lately by text and kind; typing and scrolling ask for the same lines again and again. */
        private val cache =
            object : LinkedHashMap<Pair<SourceKind, String>, List<FoundColor>>(CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Pair<SourceKind, String>, List<FoundColor>>,
                ) = size > CACHE_SIZE
            }

        private val fileSubscription: Subscription

        init {
            addColumnIfColored()
            editor.addContextMenuItems(::menuItems)
            fileSubscription =
                editor.file.subscribe { _ ->
                    val now = kindOf()
                    if (now != kind) {
                        kind = now
                        val existed = columnAdded
                        addColumnIfColored()
                        // A column added just now creates the rows itself.
                        if (existed) editor.refreshGutter()
                    }
                }
        }

        /** The column exists only for files that can show colors, from when the file becomes one. */
        private fun addColumnIfColored() {
            if (kind == null || columnAdded) return
            columnAdded = true
            editor.addGutterColumn(ColorGutter.COLUMN_WIDTH, ::column)
        }

        private fun kindOf(): SourceKind? =
            SourceKind.of(
                editor.file.value.fileName
                    .toString(),
            )

        private fun found(line: Int): List<FoundColor> {
            val kind = kind
            if (kind == null || line !in 0 until editor.lineCount) return emptyList()
            val text = editor.lineText(line)
            if (text.length > ColorFinder.MAX_LINE_LENGTH) return emptyList()
            return cache.getOrPut(kind to text) { ColorFinder.find(text, kind) }
        }

        private fun column(paragraph: Int): Node? {
            if (closed) return null
            val found = found(paragraph)
            return if (found.isEmpty()) null else ColorGutter.swatch(found) { show(it.color) }
        }

        private fun menuItems(target: EditorMenuTarget): List<MenuItem> {
            val kind = kind ?: return emptyList()
            val at =
                if (target.line in 0 until editor.lineCount) {
                    ColorFinder.at(editor.lineText(target.line), kind, target.column)
                } else {
                    null
                }
            val label = if (at == null) "Open in Coloring" else "Open \"${ColorGutter.shortened(at.text)}\" in Coloring"
            return listOf(
                MenuItem(label).apply {
                    isDisable = at == null
                    setOnAction { at?.let { show(it.color) } }
                },
            )
        }

        override fun close() {
            closed = true
            cache.clear()
            fileSubscription.unsubscribe()
        }
    }

    private companion object {
        const val CACHE_SIZE = 512
    }
}
