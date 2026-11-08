package dev.sluide.codeintelligence.signature

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.ui.DOC_MAX_WIDTH
import dev.sluide.codeintelligence.ui.EditorPopupWindow
import dev.sluide.codeintelligence.ui.fxDelay
import dev.sluide.codeintelligence.ui.scrollingMarkup
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Signature help in a bound editor (ADR 0147): [SignatureModel] fed by the editor's keys, text changes and caret,
 * shown in a [SignaturePopup] above the caret line. Shortcut+P asks at the caret, Escape closes it. FX thread.
 *
 * @param debounce how long typing or moving inside a call waits before asking again.
 */
internal class SignatureFeature(
    private val debounce: Duration = 100.milliseconds,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable = ::fxDelay,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val editor = binding.editor
        lateinit var model: SignatureModel
        val popup = SignaturePopup(editor) { model.viewHidden() }
        model = SignatureModel(binding, popup, debounce, schedule)
        val caret = editor.caretOffsetProperty.subscribe { _, _ -> model.caretMoved() }
        val handles =
            listOf(
                popup,
                AutoCloseable { model.close() },
                model.keys.attach(editor),
                editor.onTextChange(model::textChanged),
                AutoCloseable { caret.unsubscribe() },
                editor.addKeyBinding(KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN)) { model.invoke() },
                editor.addKeyBinding(KeyCodeCombination(KeyCode.ESCAPE)) {
                    if (model.isOpen) {
                        model.close()
                        true
                    } else {
                        false
                    }
                },
            )
        return AutoCloseable { handles.asReversed().forEach { it.close() } }
    }
}

/**
 * The signature popup: the signature with the active parameter in bold, "2 of 3" when there are several, the docs
 * below; above the anchor's line ([EditorPopupWindow] with `preferAbove`). [hiddenByItself] is told when it hides on
 * its own. FX thread.
 */
internal class SignaturePopup(
    private val editor: ExtensibleCodeEditor,
    private val hiddenByItself: () -> Unit,
) : SignatureView,
    AutoCloseable {
    private var hiding = false
    private val window = EditorPopupWindow(editor, preferAbove = true) { if (!hiding) hiddenByItself() }

    // Created on first use: the binding may live in a test without a toolkit.
    private val content by lazy(LazyThreadSafetyMode.NONE) {
        VBox().apply {
            styleClass += "signature-popup"
            maxWidth = DOC_MAX_WIDTH
        }
    }

    /** Whether it shows. */
    val isShowing: Boolean get() = window.isShowing

    override fun show(
        anchor: Int,
        shown: SignatureShown,
    ): Boolean {
        val label = shown.label
        val active = shown.active
        val parts =
            if (active == null) {
                listOf(signatureText(label, bold = false))
            } else {
                listOf(
                    signatureText(label.substring(0, active.first), bold = false),
                    signatureText(label.substring(active.first, active.last + 1), bold = true),
                    signatureText(label.substring(active.last + 1), bold = false),
                )
            }
        val flow = TextFlow(*parts.toTypedArray()).apply { styleClass += "signature-label" }
        val nodes =
            listOfNotNull(
                flow,
                shown.count?.let { Label(it).apply { styleClass += "signature-count" } },
                shown.docs.takeIf { it.isNotEmpty() }?.let { scrollingMarkup(it, maxHeight = DOCS_HEIGHT) },
            )
        content.children.setAll(nodes)
        val wasShowing = window.isShowing
        if (!wasShowing && editor.popupOwner?.isFocused != true) return false
        val placed = window.show(content, anchor)
        if (placed) window.relayout()
        return placed
    }

    override fun hide() {
        hiding = true
        try {
            window.hide()
        } finally {
            hiding = false
        }
    }

    override fun close() = hide()

    private companion object {
        const val DOCS_HEIGHT = 200.0
    }
}

private fun signatureText(
    text: String,
    bold: Boolean,
): Text =
    Text(text).apply {
        styleClass += "markup-text"
        styleClass += "markup-code"
        if (bold) styleClass += "markup-strong"
    }
